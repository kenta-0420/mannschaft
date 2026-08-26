package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantType;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.CancelMyApplicationRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.event.RecruitmentCancellationFeeChargeRequestedEvent;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantHistoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F03.11.1 キャンセル成立からキャンセル料徴収へ橋を架ける部分の試練（設計書 §3.3 ステップ 1）。
 *
 * <p>受け入れ条件 AC-3（料金 0 では決済を呼ばない）と AC-4（決済はキャンセルのトランザクションの外側で
 * 走らせる）を担う。</p>
 *
 * <p>AC-4 の要点は「キャンセル自体は決済失敗でロールバックしない」ことであり、その構造上の担保が
 * 「キャンセル成立の中で決済を呼ばず、コミット後に動くイベントへ渡す」ことである（§3.1-2）。
 * したがってここでは、キャンセル処理が {@link ConnectChargeService} を直接呼ばず
 * {@link RecruitmentCancellationFeeChargeRequestedEvent} を発火することを観測する。</p>
 *
 * <p>本クラスは実装より前に書かれた red テストである。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F03.11.1 キャンセル成立→徴収要求の発火 試練")
class RecruitmentParticipantServiceCancellationFeeChargeTest {

    @Mock private RecruitmentParticipantRepository participantRepository;
    @Mock private RecruitmentListingRepository listingRepository;
    @Mock private RecruitmentParticipantHistoryRepository historyRepository;
    @Mock private RecruitmentCancellationRecordRepository cancellationRecordRepository;
    @Mock private RecruitmentCancellationPolicyService policyService;
    @Mock private RecruitmentListingService listingService;
    @Mock private AccessControlService accessControlService;
    @Mock private RecruitmentMapper mapper;
    @Mock private MarketFinalizeService marketFinalizeService;
    @Mock private ContentVisibilityChecker visibilityChecker;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ConnectChargeService connectChargeService;

    private static final Long LISTING_ID = 200L;
    private static final Long USER_ID = 1L;
    private static final Long PARTICIPANT_ID = 999L;
    private static final Long RECORD_ID = 77L;

    private RecruitmentParticipantService service() {
        return new RecruitmentParticipantService(
                participantRepository, listingRepository, historyRepository, cancellationRecordRepository,
                policyService, listingService, accessControlService, mapper, marketFinalizeService,
                visibilityChecker, eventPublisher);
    }

    @Test
    @DisplayName("AC-4: 料金>0 のキャンセルでは徴収要求イベントを発火する（決済はキャンセルのトランザクションの外で走る）")
    void ac4_cancellationWithFee_publishesChargeRequestedEvent() throws Exception {
        RecruitmentParticipantService svc = service();
        givenCancellableApplication(3_000);

        svc.cancelMyApplication(LISTING_ID, USER_ID, new CancelMyApplicationRequest(true, null));

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(eventCaptor.capture());
        RecruitmentCancellationFeeChargeRequestedEvent event = eventCaptor.getAllValues().stream()
                .filter(RecruitmentCancellationFeeChargeRequestedEvent.class::isInstance)
                .map(RecruitmentCancellationFeeChargeRequestedEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("徴収要求イベントが発火していない"));

        assertThat(event.cancellationRecordId()).isEqualTo(RECORD_ID);
        assertThat(event.listingId()).isEqualTo(LISTING_ID);
        assertThat(event.participantId()).isEqualTo(PARTICIPANT_ID);
        assertThat(event.payerUserId()).isEqualTo(USER_ID);
        assertThat(event.feeAmount()).isEqualTo(3_000);

        // 業務トランザクションを外部 API の所要時間・失敗に巻き込まないため、直接呼び出しはしない（§3.1-1）。
        verify(connectChargeService, never()).settleCancellationFee(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    @DisplayName("AC-4: キャンセル記録は PENDING で作られる（徴収の成否は後から反映される）")
    void ac4_recordIsCreatedAsPending() throws Exception {
        RecruitmentParticipantService svc = service();
        givenCancellableApplication(3_000);

        svc.cancelMyApplication(LISTING_ID, USER_ID, new CancelMyApplicationRequest(true, null));

        ArgumentCaptor<RecruitmentCancellationRecordEntity> captor =
                ArgumentCaptor.forClass(RecruitmentCancellationRecordEntity.class);
        verify(cancellationRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getPaymentStatus()).isEqualTo(CancellationPaymentStatus.PENDING);
    }

    @Test
    @DisplayName("AC-3: 料金=0 のキャンセルでは決済を呼ばず NOT_REQUIRED のまま（徴収要求も発火しない）")
    void ac3_zeroFee_doesNotTriggerAnySettlement() throws Exception {
        RecruitmentParticipantService svc = service();
        givenCancellableApplication(0);

        svc.cancelMyApplication(LISTING_ID, USER_ID, new CancelMyApplicationRequest(true, null));

        ArgumentCaptor<RecruitmentCancellationRecordEntity> captor =
                ArgumentCaptor.forClass(RecruitmentCancellationRecordEntity.class);
        verify(cancellationRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getPaymentStatus()).isEqualTo(CancellationPaymentStatus.NOT_REQUIRED);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeast(0)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .as("料金 0 では徴収の経路に一切入らない")
                .noneMatch(RecruitmentCancellationFeeChargeRequestedEvent.class::isInstance);
        verify(connectChargeService, never()).settleCancellationFee(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    // ==========================================================
    // ヘルパー
    // ==========================================================

    private void givenCancellableApplication(int feeAmount) throws Exception {
        given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(paidListing()));
        given(participantRepository.findActiveByListingAndUser(LISTING_ID, USER_ID))
                .willReturn(Optional.of(confirmedParticipant()));
        given(policyService.calculateFee(any(), any()))
                .willReturn(new RecruitmentCancellationPolicyService.CalculatedFee(
                        1L, 2L, 1, "PERCENTAGE", feeAmount, feeAmount == 0, 6.0));
        given(cancellationRecordRepository.save(any())).willAnswer(invocation -> {
            RecruitmentCancellationRecordEntity saved = invocation.getArgument(0);
            setField(saved, "id", RECORD_ID);
            return saved;
        });
    }

    private RecruitmentListingEntity paidListing() throws Exception {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(10L)
                .categoryId(100L)
                .title("試練用の札")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(LocalDateTime.now().plusDays(2))
                .endAt(LocalDateTime.now().plusDays(2).plusHours(2))
                .applicationDeadline(LocalDateTime.now().plusDays(1))
                .autoCancelAt(LocalDateTime.now().plusDays(1))
                .capacity(10)
                .minCapacity(1)
                .visibility(RecruitmentVisibility.SCOPE_ONLY)
                .createdBy(USER_ID)
                .paymentEnabled(true)
                .price(10_000)
                .payeeKind("TEAM")
                .build();
        setField(listing, "id", LISTING_ID);
        setField(listing, "status", RecruitmentListingStatus.OPEN);
        return listing;
    }

    private RecruitmentParticipantEntity confirmedParticipant() throws Exception {
        RecruitmentParticipantEntity p = RecruitmentParticipantEntity.builder()
                .listingId(LISTING_ID)
                .participantType(RecruitmentParticipantType.USER)
                .userId(USER_ID)
                .appliedBy(USER_ID)
                .status(RecruitmentParticipantStatus.CONFIRMED)
                .build();
        setField(p, "id", PARTICIPANT_ID);
        return p;
    }

    private static void setField(Object entity, String name, Object value) {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(entity, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("フィールドが見つからない: " + name);
    }
}
