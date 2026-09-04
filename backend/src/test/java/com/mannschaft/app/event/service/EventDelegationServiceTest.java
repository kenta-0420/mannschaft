package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.CheckinType;
import com.mannschaft.app.event.EventDelegationStatus;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.entity.EventAttendanceMode;
import com.mannschaft.app.event.entity.EventCheckinEntity;
import com.mannschaft.app.event.entity.EventDelegationEntity;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.event.EventDelegationAcceptedEvent;
import com.mannschaft.app.event.event.EventDelegationNotificationEvent;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventDelegationRepository;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link EventDelegationService} の単体テスト（F03.10 第二陣）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventDelegationService 単体テスト")
class EventDelegationServiceTest {

    @Mock private EventDelegationRepository delegationRepository;
    @Mock private EventRsvpResponseRepository rsvpResponseRepository;
    @Mock private EventCheckinRepository checkinRepository;
    @Mock private EventService eventService;
    @Mock private EventDelegationValidator validator;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private EventDelegationService service;

    private static final Long EVENT_ID = 10L;
    private static final Long DELEGATOR_ID = 100L;
    private static final Long DELEGATE_ID = 200L;
    private static final UUID DELEGATION_ID = UUID.randomUUID();

    private EventEntity event(boolean autoAccept, EventAttendanceMode mode) {
        EventEntity event = EventEntity.builder()
                .scopeType(EventScopeType.TEAM)
                .scopeId(1L)
                .slug("ev")
                .allowProxyAttendance(true)
                .isProxyAutoAccept(autoAccept)
                .attendanceMode(mode)
                .build();
        // BaseEntity.id は @GeneratedValue 採番のためテストでは reflection で注入する
        org.springframework.test.util.ReflectionTestUtils.setField(event, "id", EVENT_ID);
        return event;
    }

    private EventDelegationEntity delegation(EventDelegationStatus status, Long proxyVoteSessionId) {
        return EventDelegationEntity.builder()
                .eventId(EVENT_ID)
                .delegatorId(DELEGATOR_ID)
                .delegateId(DELEGATE_ID)
                .teamId(1L)
                .status(status)
                .proxyVoteSessionId(proxyVoteSessionId)
                .build();
    }

    @Nested
    @DisplayName("createDelegation")
    class CreateDelegation {

        @Test
        @DisplayName("auto-accept=TRUE + RSVP: ACCEPTED で作成し RSVP 反映・ACCEPTED イベント発火")
        void 自動承認RSVP() {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event(true, EventAttendanceMode.RSVP));
            given(delegationRepository.save(any())).willAnswer(EventDelegationServiceTest.this::saveWithId);
            given(rsvpResponseRepository.findByEventIdAndUserId(any(), any())).willReturn(Optional.empty());

            EventDelegationEntity result =
                    service.createDelegation(EVENT_ID, DELEGATOR_ID, DELEGATE_ID, "急病", null);

            assertThat(result.getStatus()).isEqualTo(EventDelegationStatus.ACCEPTED);
            verifyNotificationPublished(EventDelegationNotificationEvent.Kind.AUTO_ACCEPTED);
            verify(eventPublisher).publishEvent(any(EventDelegationAcceptedEvent.class));
            // 代理人 RSVP 作成・委任者 RSVP 更新（save 2 回以上）
            verify(rsvpResponseRepository, org.mockito.Mockito.atLeastOnce()).save(any());
        }

        @Test
        @DisplayName("auto-accept=FALSE: PENDING で作成し依頼通知・ACCEPTED イベント未発火")
        void 承認待ち() {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event(false, EventAttendanceMode.RSVP));
            given(delegationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(rsvpResponseRepository.findByEventIdAndUserId(any(), any())).willReturn(Optional.empty());

            EventDelegationEntity result =
                    service.createDelegation(EVENT_ID, DELEGATOR_ID, DELEGATE_ID, null, null);

            assertThat(result.getStatus()).isEqualTo(EventDelegationStatus.PENDING);
            verifyNotificationPublished(EventDelegationNotificationEvent.Kind.REQUEST_PENDING);
            verify(eventPublisher, never()).publishEvent(any(EventDelegationAcceptedEvent.class));
        }

        @Test
        @DisplayName("REGISTRATION モード: 代理人 RSVP は自動作成しない")
        void REGISTRATIONはRSVP自動作成なし() {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event(true, EventAttendanceMode.REGISTRATION));
            given(delegationRepository.save(any())).willAnswer(EventDelegationServiceTest.this::saveWithId);

            service.createDelegation(EVENT_ID, DELEGATOR_ID, DELEGATE_ID, null, null);

            verify(rsvpResponseRepository, never()).save(any());
            verify(eventPublisher).publishEvent(any(EventDelegationAcceptedEvent.class));
        }
    }

    @Nested
    @DisplayName("proxyCheckin")
    class ProxyCheckin {

        @Test
        @DisplayName("ACCEPTED かつ代理人本人なら PROXY チェックイン作成")
        void 正常() {
            given(delegationRepository.findById(DELEGATION_ID))
                    .willReturn(Optional.of(delegation(EventDelegationStatus.ACCEPTED, null)));
            given(checkinRepository.existsByDelegationId(DELEGATION_ID)).willReturn(false);
            given(checkinRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            EventCheckinEntity checkin = service.proxyCheckin(EVENT_ID, DELEGATION_ID, DELEGATE_ID, false);

            assertThat(checkin.getCheckinType()).isEqualTo(CheckinType.PROXY);
            assertThat(checkin.getDelegationId()).isEqualTo(DELEGATION_ID);
            assertThat(checkin.getTicketId()).isNull();
        }

        @Test
        @DisplayName("代理人本人でなく ADMIN でもないと 403")
        void 権限なし() {
            given(delegationRepository.findById(DELEGATION_ID))
                    .willReturn(Optional.of(delegation(EventDelegationStatus.ACCEPTED, null)));

            assertThatThrownBy(() -> service.proxyCheckin(EVENT_ID, DELEGATION_ID, 999L, false))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(EventErrorCode.DELEGATION_CHECKIN_FORBIDDEN);
        }

        @Test
        @DisplayName("ADMIN なら任意の代理チェックイン可能")
        void ADMIN許可() {
            given(delegationRepository.findById(DELEGATION_ID))
                    .willReturn(Optional.of(delegation(EventDelegationStatus.ACCEPTED, null)));
            given(checkinRepository.existsByDelegationId(DELEGATION_ID)).willReturn(false);
            given(checkinRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            EventCheckinEntity checkin = service.proxyCheckin(EVENT_ID, DELEGATION_ID, 999L, true);
            assertThat(checkin.getCheckinType()).isEqualTo(CheckinType.PROXY);
        }

        @Test
        @DisplayName("ACCEPTED でないと 422")
        void ステータス不正() {
            given(delegationRepository.findById(DELEGATION_ID))
                    .willReturn(Optional.of(delegation(EventDelegationStatus.PENDING, null)));

            assertThatThrownBy(() -> service.proxyCheckin(EVENT_ID, DELEGATION_ID, DELEGATE_ID, false))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(EventErrorCode.DELEGATION_CHECKIN_NOT_ACCEPTED);
        }

        @Test
        @DisplayName("二重チェックインで 409")
        void 二重チェックイン() {
            given(delegationRepository.findById(DELEGATION_ID))
                    .willReturn(Optional.of(delegation(EventDelegationStatus.ACCEPTED, null)));
            given(checkinRepository.existsByDelegationId(DELEGATION_ID)).willReturn(true);

            assertThatThrownBy(() -> service.proxyCheckin(EVENT_ID, DELEGATION_ID, DELEGATE_ID, false))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(EventErrorCode.DELEGATION_ALREADY_CHECKED_IN);
            verify(checkinRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("accept / reject")
    class AcceptReject {

        @Test
        @DisplayName("accept: 代理人本人でないと 403")
        void 承認本人でない() {
            given(delegationRepository.findById(DELEGATION_ID))
                    .willReturn(Optional.of(delegation(EventDelegationStatus.PENDING, null)));
            assertThatThrownBy(() -> service.accept(DELEGATION_ID, 999L))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(EventErrorCode.DELEGATION_NOT_DELEGATE);
        }

        @Test
        @DisplayName("accept: ACCEPTED 確定で RSVP 反映・ACCEPTED イベント発火")
        void 承認でイベント発火() {
            EventDelegationEntity pending = delegation(EventDelegationStatus.PENDING, 99L);
            pending.setId(DELEGATION_ID);
            given(delegationRepository.findById(DELEGATION_ID)).willReturn(Optional.of(pending));
            given(delegationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event(false, EventAttendanceMode.RSVP));
            given(rsvpResponseRepository.findByEventIdAndUserId(any(), any())).willReturn(Optional.empty());

            EventDelegationEntity result = service.accept(DELEGATION_ID, DELEGATE_ID);

            assertThat(result.getStatus()).isEqualTo(EventDelegationStatus.ACCEPTED);
            verify(eventPublisher).publishEvent(any(EventDelegationAcceptedEvent.class));
            verifyNotificationPublished(EventDelegationNotificationEvent.Kind.ACCEPTED);
        }
    }

    @Nested
    @DisplayName("linkProxyDelegation")
    class LinkProxyDelegation {

        @Test
        @DisplayName("proxyDelegationId が null なら何もしない")
        void null時nooop() {
            service.linkProxyDelegation(DELEGATION_ID, null);
            verify(delegationRepository, never()).findById(any());
        }

        @Test
        @DisplayName("proxyDelegationId 設定で逆設定する")
        void 逆設定() {
            EventDelegationEntity d = delegation(EventDelegationStatus.ACCEPTED, 99L);
            given(delegationRepository.findById(DELEGATION_ID)).willReturn(Optional.of(d));
            given(delegationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.linkProxyDelegation(DELEGATION_ID, 555L);

            assertThat(d.getProxyDelegationId()).isEqualTo(555L);
        }
    }

    /**
     * save 時に UUIDv7 主キー採番を模倣するモック実装。
     * 本番では {@code @GeneratedValue} が採番するため、テストでは未採番なら採番する。
     */
    private EventDelegationEntity saveWithId(org.mockito.invocation.InvocationOnMock inv) {
        EventDelegationEntity entity = inv.getArgument(0);
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID());
        }
        return entity;
    }

    /**
     * 代理出席の通知が「業務TX内では publish されるだけ」であることを検証する（Issue #2990 L5）。
     *
     * <p>是正前は {@code EventDelegationNotifier} を直接呼んでおり、その Notifier をモックしていた
     * ため、通知が業務トランザクションに参加している事実（= 通知失敗で業務が巻き戻る）を本 UT は
     * 一切捕まえられなかった。是正後は publish の検証に置き換え、実際の巻き戻り有無は
     * {@code EventDelegationNotificationTransactionIT}（実 DB）で測る。</p>
     */
    private void verifyNotificationPublished(EventDelegationNotificationEvent.Kind expectedKind) {
        verify(eventPublisher).publishEvent(ArgumentMatchers.<Object>argThat(
                published -> published instanceof EventDelegationNotificationEvent notification
                        && notification.kind() == expectedKind));
    }
}
