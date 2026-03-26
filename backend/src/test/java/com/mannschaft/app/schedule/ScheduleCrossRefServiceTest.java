package com.mannschaft.app.schedule;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.dto.CrossInviteRequest;
import com.mannschaft.app.schedule.dto.CrossRefResponse;
import com.mannschaft.app.schedule.entity.ScheduleCrossRefEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleCrossRefRepository;
import com.mannschaft.app.schedule.service.ScheduleCrossRefService;
import com.mannschaft.app.schedule.service.ScheduleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleCrossRefService} の単体テスト。
 * クロス招待の送信・承認・拒否・キャンセル・確認を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleCrossRefService 単体テスト")
class ScheduleCrossRefServiceTest {

    @Mock
    private ScheduleCrossRefRepository crossRefRepository;

    @Mock
    private ScheduleService scheduleService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ScheduleCrossRefService crossRefService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SOURCE_SCHEDULE_ID = 1L;
    private static final Long INVITATION_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final Long TARGET_ID = 200L;

    private ScheduleCrossRefEntity createPendingCrossRef() {
        return ScheduleCrossRefEntity.builder()
                .sourceScheduleId(SOURCE_SCHEDULE_ID)
                .targetType(CrossRefTargetType.TEAM)
                .targetId(TARGET_ID)
                .invitedBy(USER_ID)
                .status(CrossRefStatus.PENDING)
                .message("招待メッセージ")
                .build();
    }

    private ScheduleEntity createSourceSchedule() {
        return ScheduleEntity.builder()
                .teamId(10L)
                .title("試合")
                .startAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 1, 12, 0))
                .allDay(false)
                .eventType(EventType.MATCH)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .isException(false)
                .build();
    }

    // ========================================
    // sendCrossInvite
    // ========================================

    @Nested
    @DisplayName("sendCrossInvite")
    class SendCrossInvite {

        @Test
        @DisplayName("招待送信_正常_保存されてイベント発行される")
        void 招待送信_正常_保存されてイベント発行される() {
            // given
            given(scheduleService.getSchedule(SOURCE_SCHEDULE_ID)).willReturn(createSourceSchedule());
            given(crossRefRepository.findBySourceScheduleIdAndTargetTypeAndTargetId(
                    SOURCE_SCHEDULE_ID, CrossRefTargetType.TEAM, TARGET_ID))
                    .willReturn(Optional.empty());
            given(crossRefRepository.save(any(ScheduleCrossRefEntity.class)))
                    .willAnswer(invocation -> {
                        ScheduleCrossRefEntity e = invocation.getArgument(0);
                        java.lang.reflect.Method m =
                                ScheduleCrossRefEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });

            CrossInviteRequest req = new CrossInviteRequest("TEAM", TARGET_ID, "招待します");

            // when
            CrossRefResponse result = crossRefService.sendCrossInvite(SOURCE_SCHEDULE_ID, req, USER_ID);

            // then
            assertThat(result.getStatus()).isEqualTo("PENDING");
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("招待送信_重複あり_例外スロー")
        void 招待送信_重複あり_例外スロー() {
            // given
            given(scheduleService.getSchedule(SOURCE_SCHEDULE_ID)).willReturn(createSourceSchedule());
            ScheduleCrossRefEntity existing = createPendingCrossRef();
            given(crossRefRepository.findBySourceScheduleIdAndTargetTypeAndTargetId(
                    SOURCE_SCHEDULE_ID, CrossRefTargetType.TEAM, TARGET_ID))
                    .willReturn(Optional.of(existing));

            CrossInviteRequest req = new CrossInviteRequest("TEAM", TARGET_ID, "重複招待");

            // when & then
            assertThatThrownBy(() -> crossRefService.sendCrossInvite(SOURCE_SCHEDULE_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.CROSS_INVITE_ALREADY_EXISTS);
        }
    }

    // ========================================
    // cancelCrossInvite
    // ========================================

    @Nested
    @DisplayName("cancelCrossInvite")
    class CancelCrossInvite {

        @Test
        @DisplayName("招待キャンセル_正常_キャンセルされる")
        void 招待キャンセル_正常_キャンセルされる() {
            // given
            ScheduleCrossRefEntity crossRef = createPendingCrossRef();
            given(crossRefRepository.findById(INVITATION_ID)).willReturn(Optional.of(crossRef));

            // when
            crossRefService.cancelCrossInvite(INVITATION_ID, USER_ID);

            // then
            assertThat(crossRef.getStatus()).isEqualTo(CrossRefStatus.CANCELLED);
            verify(crossRefRepository).save(crossRef);
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("招待キャンセル_不存在_例外スロー")
        void 招待キャンセル_不存在_例外スロー() {
            // given
            given(crossRefRepository.findById(INVITATION_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> crossRefService.cancelCrossInvite(INVITATION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.CROSS_INVITE_NOT_FOUND);
        }

        @Test
        @DisplayName("招待キャンセル_ステータス不正_例外スロー")
        void 招待キャンセル_ステータス不正_例外スロー() {
            // given
            ScheduleCrossRefEntity accepted = ScheduleCrossRefEntity.builder()
                    .sourceScheduleId(SOURCE_SCHEDULE_ID)
                    .targetType(CrossRefTargetType.TEAM)
                    .targetId(TARGET_ID)
                    .invitedBy(USER_ID)
                    .status(CrossRefStatus.ACCEPTED)
                    .build();
            given(crossRefRepository.findById(INVITATION_ID)).willReturn(Optional.of(accepted));

            // when & then
            assertThatThrownBy(() -> crossRefService.cancelCrossInvite(INVITATION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.CROSS_INVITE_INVALID_STATUS);
        }
    }

    // ========================================
    // listReceivedInvitations
    // ========================================

    @Nested
    @DisplayName("listReceivedInvitations")
    class ListReceivedInvitations {

        @Test
        @DisplayName("受信招待一覧_正常_PENDING招待のみ返す")
        void 受信招待一覧_正常_PENDING招待のみ返す() {
            // given
            ScheduleCrossRefEntity crossRef = createPendingCrossRef();
            given(crossRefRepository.findByTargetTypeAndTargetIdAndStatus(
                    CrossRefTargetType.TEAM, TARGET_ID, CrossRefStatus.PENDING))
                    .willReturn(List.of(crossRef));

            // when
            List<CrossRefResponse> result = crossRefService.listReceivedInvitations("TEAM", TARGET_ID);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("PENDING");
        }
    }

    // ========================================
    // rejectInvitation
    // ========================================

    @Nested
    @DisplayName("rejectInvitation")
    class RejectInvitation {

        @Test
        @DisplayName("招待拒否_正常_拒否される")
        void 招待拒否_正常_拒否される() {
            // given
            ScheduleCrossRefEntity crossRef = createPendingCrossRef();
            given(crossRefRepository.findById(INVITATION_ID)).willReturn(Optional.of(crossRef));

            // when
            crossRefService.rejectInvitation(INVITATION_ID);

            // then
            assertThat(crossRef.getStatus()).isEqualTo(CrossRefStatus.REJECTED);
            verify(crossRefRepository).save(crossRef);
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("招待拒否_ステータス不正_例外スロー")
        void 招待拒否_ステータス不正_例外スロー() {
            // given
            ScheduleCrossRefEntity cancelled = ScheduleCrossRefEntity.builder()
                    .sourceScheduleId(SOURCE_SCHEDULE_ID)
                    .targetType(CrossRefTargetType.TEAM)
                    .targetId(TARGET_ID)
                    .invitedBy(USER_ID)
                    .status(CrossRefStatus.CANCELLED)
                    .build();
            given(crossRefRepository.findById(INVITATION_ID)).willReturn(Optional.of(cancelled));

            // when & then
            assertThatThrownBy(() -> crossRefService.rejectInvitation(INVITATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.CROSS_INVITE_INVALID_STATUS);
        }
    }

    // ========================================
    // confirmInvitation
    // ========================================

    @Nested
    @DisplayName("confirmInvitation")
    class ConfirmInvitation {

        @Test
        @DisplayName("招待確認_正常_ACCEPTEDに遷移する")
        void 招待確認_正常_ACCEPTEDに遷移する() {
            // given
            ScheduleCrossRefEntity awaiting = ScheduleCrossRefEntity.builder()
                    .sourceScheduleId(SOURCE_SCHEDULE_ID)
                    .targetType(CrossRefTargetType.TEAM)
                    .targetId(TARGET_ID)
                    .invitedBy(USER_ID)
                    .status(CrossRefStatus.AWAITING_CONFIRMATION)
                    .build();
            given(crossRefRepository.findById(INVITATION_ID)).willReturn(Optional.of(awaiting));

            // when
            crossRefService.confirmInvitation(INVITATION_ID);

            // then
            assertThat(awaiting.getStatus()).isEqualTo(CrossRefStatus.ACCEPTED);
            verify(crossRefRepository).save(awaiting);
        }

        @Test
        @DisplayName("招待確認_ステータス不正_例外スロー")
        void 招待確認_ステータス不正_例外スロー() {
            // given
            ScheduleCrossRefEntity pending = createPendingCrossRef();
            given(crossRefRepository.findById(INVITATION_ID)).willReturn(Optional.of(pending));

            // when & then
            assertThatThrownBy(() -> crossRefService.confirmInvitation(INVITATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.CROSS_INVITE_INVALID_STATUS);
        }
    }
}
