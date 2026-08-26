package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.ScheduleDelegationStatus;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleDelegationEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleDelegationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleDelegationService} の単体テスト（F03.10 第二陣）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleDelegationService 単体テスト")
class ScheduleDelegationServiceTest {

    @Mock private ScheduleDelegationRepository delegationRepository;
    @Mock private ScheduleAttendanceRepository attendanceRepository;
    @Mock private ScheduleService scheduleService;
    @Mock private ScheduleDelegationValidator validator;
    @Mock private ScheduleDelegationNotifier notifier;
    /** 認可ガードは状態を持たない純粋な判定のため、実体を注入して本物の本人性判定を通す。 */
    @Spy private ScheduleAccessGuard scheduleAccessGuard = new ScheduleAccessGuard();

    @InjectMocks
    private ScheduleDelegationService service;

    private static final Long SCHEDULE_ID = 10L;
    private static final Long DELEGATOR_ID = 100L;
    private static final Long DELEGATE_ID = 200L;
    private static final UUID DELEGATION_ID = UUID.randomUUID();

    private ScheduleEntity schedule(boolean autoAccept) {
        return ScheduleEntity.builder()
                .teamId(1L)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(autoAccept)
                .build();
    }

    private ScheduleDelegationEntity delegation(ScheduleDelegationStatus status) {
        return ScheduleDelegationEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .delegatorId(DELEGATOR_ID)
                .delegateId(DELEGATE_ID)
                .teamId(1L)
                .status(status)
                .build();
    }

    @Nested
    @DisplayName("createDelegation")
    class CreateDelegation {

        @Test
        @DisplayName("auto-accept=TRUE: ACCEPTED で作成し代理人を ATTENDING・自動承認通知")
        void 自動承認() {
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule(true));
            given(delegationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(attendanceRepository.findByScheduleIdAndUserId(any(), any()))
                    .willReturn(Optional.of(ScheduleAttendanceEntity.builder()
                            .scheduleId(SCHEDULE_ID).userId(DELEGATOR_ID).status(AttendanceStatus.UNDECIDED).build()));

            ScheduleDelegationEntity result =
                    service.createDelegation(SCHEDULE_ID, DELEGATOR_ID, DELEGATE_ID, "出張");

            assertThat(result.getStatus()).isEqualTo(ScheduleDelegationStatus.ACCEPTED);
            verify(validator).validateForCreate(any(), eqLong(DELEGATOR_ID), eqLong(DELEGATE_ID));
            verify(notifier).notifyAutoAccepted(any());
            verify(notifier, never()).notifyRequestPending(any());
        }

        @Test
        @DisplayName("auto-accept=FALSE: PENDING で作成し依頼通知")
        void 承認待ち() {
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule(false));
            given(delegationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(attendanceRepository.findByScheduleIdAndUserId(any(), any())).willReturn(Optional.empty());

            ScheduleDelegationEntity result =
                    service.createDelegation(SCHEDULE_ID, DELEGATOR_ID, DELEGATE_ID, null);

            assertThat(result.getStatus()).isEqualTo(ScheduleDelegationStatus.PENDING);
            verify(notifier).notifyRequestPending(any());
            verify(notifier, never()).notifyAutoAccepted(any());
        }
    }

    @Nested
    @DisplayName("accept / reject")
    class AcceptReject {

        @Test
        @DisplayName("accept: PENDING かつ代理人本人なら ACCEPTED")
        void 承認成功() {
            given(delegationRepository.findById(DELEGATION_ID)).willReturn(Optional.of(delegation(ScheduleDelegationStatus.PENDING)));
            given(delegationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(attendanceRepository.findByScheduleIdAndUserId(any(), any())).willReturn(Optional.empty());

            ScheduleDelegationEntity result = service.accept(DELEGATION_ID, DELEGATE_ID);

            assertThat(result.getStatus()).isEqualTo(ScheduleDelegationStatus.ACCEPTED);
            verify(notifier).notifyAccepted(any());
        }

        @Test
        @DisplayName("accept: 代理人本人でないと 403")
        void 承認_本人でない() {
            given(delegationRepository.findById(DELEGATION_ID)).willReturn(Optional.of(delegation(ScheduleDelegationStatus.PENDING)));

            assertThatThrownBy(() -> service.accept(DELEGATION_ID, 999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_DELEGATE);
        }

        @Test
        @DisplayName("accept: PENDING でないと 422")
        void 承認_PENDINGでない() {
            given(delegationRepository.findById(DELEGATION_ID)).willReturn(Optional.of(delegation(ScheduleDelegationStatus.ACCEPTED)));

            assertThatThrownBy(() -> service.accept(DELEGATION_ID, DELEGATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_PENDING);
        }

        @Test
        @DisplayName("reject: PENDING かつ代理人本人なら REJECTED")
        void 拒否成功() {
            given(delegationRepository.findById(DELEGATION_ID)).willReturn(Optional.of(delegation(ScheduleDelegationStatus.PENDING)));
            given(delegationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            ScheduleDelegationEntity result = service.reject(DELEGATION_ID, DELEGATE_ID);

            assertThat(result.getStatus()).isEqualTo(ScheduleDelegationStatus.REJECTED);
            verify(notifier).notifyRejected(any());
        }
    }

    @Nested
    @DisplayName("withdraw")
    class Withdraw {

        @Test
        @DisplayName("代理由来(is_proxy_input=TRUE)の代理人出欠のみ UNDECIDED に巻き戻す")
        void 代理由来のみ巻き戻し() {
            ScheduleDelegationEntity d = delegation(ScheduleDelegationStatus.ACCEPTED);
            given(delegationRepository.findFirstByScheduleIdAndDelegatorIdAndStatusIn(any(), any(), any()))
                    .willReturn(Optional.of(d));
            given(delegationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            ScheduleAttendanceEntity proxyAttendance = ScheduleAttendanceEntity.builder()
                    .scheduleId(SCHEDULE_ID).userId(DELEGATE_ID).status(AttendanceStatus.ATTENDING)
                    .isProxyInput(true).build();
            given(attendanceRepository.findByScheduleIdAndUserId(SCHEDULE_ID, DELEGATE_ID))
                    .willReturn(Optional.of(proxyAttendance));

            service.withdraw(SCHEDULE_ID, DELEGATOR_ID);

            assertThat(d.getStatus()).isEqualTo(ScheduleDelegationStatus.CANCELLED);
            ArgumentCaptor<ScheduleAttendanceEntity> captor = ArgumentCaptor.forClass(ScheduleAttendanceEntity.class);
            verify(attendanceRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(AttendanceStatus.UNDECIDED);
            assertThat(captor.getValue().getIsProxyInput()).isFalse();
            verify(notifier).notifyCancelled(any());
        }

        @Test
        @DisplayName("本人入力(is_proxy_input=FALSE)の出欠は温存する")
        void 本人入力は温存() {
            ScheduleDelegationEntity d = delegation(ScheduleDelegationStatus.ACCEPTED);
            given(delegationRepository.findFirstByScheduleIdAndDelegatorIdAndStatusIn(any(), any(), any()))
                    .willReturn(Optional.of(d));
            given(delegationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            ScheduleAttendanceEntity selfAttendance = ScheduleAttendanceEntity.builder()
                    .scheduleId(SCHEDULE_ID).userId(DELEGATE_ID).status(AttendanceStatus.ATTENDING)
                    .isProxyInput(false).build();
            given(attendanceRepository.findByScheduleIdAndUserId(SCHEDULE_ID, DELEGATE_ID))
                    .willReturn(Optional.of(selfAttendance));

            service.withdraw(SCHEDULE_ID, DELEGATOR_ID);

            // is_proxy_input=FALSE のため save は呼ばれない（温存）
            verify(attendanceRepository, never()).save(any());
        }

        @Test
        @DisplayName("アクティブ代理が無ければ 404")
        void 代理なし404() {
            given(delegationRepository.findFirstByScheduleIdAndDelegatorIdAndStatusIn(any(), any(), any()))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.withdraw(SCHEDULE_ID, DELEGATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("onDelegatorAttendanceChanged")
    class OnDelegatorAttendanceChanged {

        @Test
        @DisplayName("委任者が ATTENDING に更新で PENDING 代理を自動 CANCELLED")
        void ATTENDINGでPENDING自動取消() {
            ScheduleDelegationEntity d = delegation(ScheduleDelegationStatus.PENDING);
            given(delegationRepository.findFirstByScheduleIdAndDelegatorIdAndStatusIn(
                    SCHEDULE_ID, DELEGATOR_ID, List.of(ScheduleDelegationStatus.PENDING)))
                    .willReturn(Optional.of(d));
            given(delegationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.onDelegatorAttendanceChanged(SCHEDULE_ID, DELEGATOR_ID, AttendanceStatus.ATTENDING);

            assertThat(d.getStatus()).isEqualTo(ScheduleDelegationStatus.CANCELLED);
            verify(notifier).notifyCancelled(any());
        }

        @Test
        @DisplayName("ATTENDING 以外は何もしない")
        void ABSENTでは何もしない() {
            service.onDelegatorAttendanceChanged(SCHEDULE_ID, DELEGATOR_ID, AttendanceStatus.ABSENT);
            verify(delegationRepository, never()).findFirstByScheduleIdAndDelegatorIdAndStatusIn(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("cancelOnMemberLeft")
    class CancelOnMemberLeft {

        @Test
        @DisplayName("代理人退会なら委任者へ再設定通知")
        void 代理人退会() {
            ScheduleDelegationEntity d = delegation(ScheduleDelegationStatus.ACCEPTED);
            given(delegationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(attendanceRepository.findByScheduleIdAndUserId(any(), any())).willReturn(Optional.empty());

            service.cancelOnMemberLeft(d, DELEGATE_ID);

            assertThat(d.getStatus()).isEqualTo(ScheduleDelegationStatus.CANCELLED);
            verify(notifier).notifyDelegateLeft(d);
        }

        @Test
        @DisplayName("委任者退会なら代理人へ取消通知")
        void 委任者退会() {
            ScheduleDelegationEntity d = delegation(ScheduleDelegationStatus.PENDING);
            given(delegationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(attendanceRepository.findByScheduleIdAndUserId(any(), any())).willReturn(Optional.empty());

            service.cancelOnMemberLeft(d, DELEGATOR_ID);

            verify(notifier).notifyDelegatorLeft(d);
        }
    }

    // Mockito の eq(Long) を import 衝突なしに使うためのヘルパー
    private static Long eqLong(Long value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
