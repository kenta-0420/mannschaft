package com.mannschaft.app.payment;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.payment.entity.TeamPaymentAdvanceEntity;
import com.mannschaft.app.payment.repository.TeamPaymentAdvanceRepository;
import com.mannschaft.app.payment.service.TeamPaymentAdvanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TeamPaymentAdvanceService} の単体テスト（F08.9 P7 第一波）。
 *
 * <p>confirmSettlement の権原・PENDING のみ・二重確認 409・IDOR を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamPaymentAdvanceService 単体テスト（立替/精算）")
class TeamPaymentAdvanceServiceTest {

    @Mock private TeamPaymentAdvanceRepository teamPaymentAdvanceRepository;
    @Mock private AccessControlService accessControlService;
    @Mock private com.mannschaft.app.auth.service.AuditLogService auditLogService;
    @Mock private com.mannschaft.app.notification.service.NotificationHelper notificationHelper;
    @Mock private com.mannschaft.app.role.repository.UserRoleRepository userRoleRepository;
    @Mock private org.springframework.context.MessageSource messageSource;

    @InjectMocks
    private TeamPaymentAdvanceService service;

    private static final Long ORG_ID = 500L;
    private static final Long TEAM_ID = 600L;
    private static final Long ADMIN_USER_ID = 700L;

    private TeamPaymentAdvanceEntity advance(AdvanceSettlementStatus status, Long teamId) {
        TeamPaymentAdvanceEntity a = TeamPaymentAdvanceEntity.builder()
                .organizationId(ORG_ID)
                .teamId(teamId)
                .payerUserId(ADMIN_USER_ID)
                .paymentRequestId(UUID.randomUUID())
                .advancedAmount(30000)
                .currency("JPY")
                .settlementStatus(status)
                .build();
        a.setId(UUID.randomUUID());
        return a;
    }

    @Nested
    @DisplayName("createAdvance（立替起票）")
    class CreateAdvance {

        @Test
        @DisplayName("正常系: PENDING で起票")
        void 起票成功() {
            UUID prId = UUID.randomUUID();
            given(teamPaymentAdvanceRepository.findByPaymentRequestIdAndDeletedAtIsNull(prId))
                    .willReturn(Optional.empty());
            given(teamPaymentAdvanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            TeamPaymentAdvanceEntity result = service.createAdvance(
                    ORG_ID, TEAM_ID, ADMIN_USER_ID, UUID.randomUUID(), prId, 30000, "JPY");

            assertThat(result.getSettlementStatus()).isEqualTo(AdvanceSettlementStatus.PENDING);
            assertThat(result.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(result.getAdvancedAmount()).isEqualTo(30000);
        }

        @Test
        @DisplayName("冪等: 同一 paymentRequestId の立替が既にあれば再作成しない")
        void 冪等で既存返却() {
            UUID prId = UUID.randomUUID();
            TeamPaymentAdvanceEntity existing = advance(AdvanceSettlementStatus.PENDING, TEAM_ID);
            given(teamPaymentAdvanceRepository.findByPaymentRequestIdAndDeletedAtIsNull(prId))
                    .willReturn(Optional.of(existing));

            TeamPaymentAdvanceEntity result = service.createAdvance(
                    ORG_ID, TEAM_ID, ADMIN_USER_ID, UUID.randomUUID(), prId, 30000, "JPY");

            assertThat(result).isSameAs(existing);
            verify(teamPaymentAdvanceRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("confirmSettlement（精算確認）")
    class ConfirmSettlement {

        @Test
        @DisplayName("正常系: PENDING を SETTLED へ遷移し確認者を記録")
        void 精算確認成功() {
            TeamPaymentAdvanceEntity a = advance(AdvanceSettlementStatus.PENDING, TEAM_ID);
            given(teamPaymentAdvanceRepository.findByIdAndDeletedAtIsNull(a.getId())).willReturn(Optional.of(a));
            given(teamPaymentAdvanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            TeamPaymentAdvanceEntity result = service.confirmSettlement(TEAM_ID, a.getId(), ADMIN_USER_ID);

            assertThat(result.getSettlementStatus()).isEqualTo(AdvanceSettlementStatus.SETTLED);
            assertThat(result.getSettledConfirmedBy()).isEqualTo(ADMIN_USER_ID);
            assertThat(result.getSettledAt()).isNotNull();
            verify(accessControlService).checkAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("正常系: 精算確認の成立を協会 ADMIN へ軽量通知する（第二波）")
        void 精算確認で協会ADMINへ通知() {
            TeamPaymentAdvanceEntity a = advance(AdvanceSettlementStatus.PENDING, TEAM_ID);
            given(teamPaymentAdvanceRepository.findByIdAndDeletedAtIsNull(a.getId())).willReturn(Optional.of(a));
            given(teamPaymentAdvanceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(userRoleRepository.findAdminUserIdsByOrganizationId(ORG_ID)).willReturn(java.util.List.of(21L, 22L));
            given(messageSource.getMessage(any(String.class), any(), any(), any())).willReturn("通知文言");

            service.confirmSettlement(TEAM_ID, a.getId(), ADMIN_USER_ID);

            verify(notificationHelper).notifyAll(
                    eq(java.util.List.of(21L, 22L)), any(), any(), any(),
                    any(), any(), any(), eq(ORG_ID), any(), any());
        }

        @Test
        @DisplayName("異常系: 既に SETTLED は二重確認防止（ADVANCE_ALREADY_SETTLED・409）")
        void 二重確認防止() {
            TeamPaymentAdvanceEntity a = advance(AdvanceSettlementStatus.SETTLED, TEAM_ID);
            given(teamPaymentAdvanceRepository.findByIdAndDeletedAtIsNull(a.getId())).willReturn(Optional.of(a));

            assertThatThrownBy(() -> service.confirmSettlement(TEAM_ID, a.getId(), ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_ADVANCE_ALREADY_SETTLED);
        }

        @Test
        @DisplayName("異常系: チーム ADMIN でない場合 403（NOT_FOR_THIS_TEAM）")
        void 非ADMINで403() {
            TeamPaymentAdvanceEntity a = advance(AdvanceSettlementStatus.PENDING, TEAM_ID);
            given(teamPaymentAdvanceRepository.findByIdAndDeletedAtIsNull(a.getId())).willReturn(Optional.of(a));
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.confirmSettlement(TEAM_ID, a.getId(), ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOR_THIS_TEAM);
        }

        @Test
        @DisplayName("異常系: URL teamId と立替 team_id 不一致は 404 秘匿（IDOR）")
        void 他チーム404() {
            TeamPaymentAdvanceEntity a = advance(AdvanceSettlementStatus.PENDING, TEAM_ID);
            given(teamPaymentAdvanceRepository.findByIdAndDeletedAtIsNull(a.getId())).willReturn(Optional.of(a));

            assertThatThrownBy(() -> service.confirmSettlement(999L, a.getId(), ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_ADVANCE_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: 立替が見つからない場合 404")
        void 立替不在404() {
            UUID id = UUID.randomUUID();
            given(teamPaymentAdvanceRepository.findByIdAndDeletedAtIsNull(id)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmSettlement(TEAM_ID, id, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_ADVANCE_NOT_FOUND);
        }
    }
}
