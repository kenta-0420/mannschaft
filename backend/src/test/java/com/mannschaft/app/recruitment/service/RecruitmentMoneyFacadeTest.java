package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.service.RecruitmentCancellationFeeWaiveService.ListingScope;
import com.mannschaft.app.recruitment.service.RecruitmentCancellationFeeWaiveService.WaiveTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link RecruitmentMoneyFacade}（認可は tx の外・tx 本体は認可に依存しない）の単体テスト（CMP-260923-0954 W4）。
 *
 * <p>認可の検証は旧 Service 単体テスト（{@code RecruitmentCancellationFeeWaiveServiceTest} の AC-18/19/27/28、
 * {@code RecruitmentPenaltyServiceTest}・ポリシー・申込確定の認可）からここへ移した。置き場を認可の置き場に
 * 合わせただけで、検証の中身は消していない。実 DB 越しの応答表は {@code RecruitmentMoneyPenaltyScopeContractIT}。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentMoneyFacade 単体テスト（認可の外出し）")
class RecruitmentMoneyFacadeTest {

    @Mock private RecruitmentCancellationFeeWaiveService waiveService;
    @Mock private RecruitmentPenaltyService penaltyService;
    @Mock private RecruitmentListingService listingService;
    @Mock private RecruitmentCancellationPolicyService policyService;
    @Mock private AccessControlService accessControlService;
    @Mock private ConnectChargeService connectChargeService;

    private static final Long RECORD_ID = 77L;
    private static final Long LISTING_ID = 100L;
    private static final Long PARTICIPANT_ID = 200L;
    private static final Long TEAM_ID = 10L;
    private static final Long DEBTOR_ID = 1L;
    private static final Long PAYEE_MANAGER_ID = 55L;
    private static final Long OUTSIDER_ID = 66L;
    private static final Long ADMIN_ID = 2L;
    private static final Long MEMBER_ID = 3L;
    private static final Long SYSTEM_ADMIN_ID = 4L;

    private RecruitmentMoneyFacade facade() {
        return new RecruitmentMoneyFacade(
                waiveService, penaltyService, listingService, policyService, accessControlService,
                connectChargeService);
    }

    private static ErrorCode errorOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    // ==========================================================
    // 免除
    // ==========================================================

    @Nested
    @DisplayName("waive")
    class Waive {

        private void givenTarget() {
            given(waiveService.resolveWaiveTarget(RECORD_ID))
                    .willReturn(new WaiveTarget(RECORD_ID, LISTING_ID, PARTICIPANT_ID, DEBTOR_ID));
        }

        private void givenPayee(Long actor, boolean accepted) {
            given(connectChargeService.isPayeeSettlementManager(
                    EscrowSourceKind.RECRUITMENT, LISTING_ID, PARTICIPANT_ID, actor)).willReturn(accepted);
        }

        @Test
        @DisplayName("AC-27/28: 受取先の判定は payment ドメインの 1 本の入口へ委ね、許可なら tx 本体へ payeeSide=true で進む。許可経路で追加の判定を足さない")
        void payeeSide_isAllowed_withoutExtraChecks() {
            givenTarget();
            givenPayee(PAYEE_MANAGER_ID, true);

            facade().waive(RECORD_ID, PAYEE_MANAGER_ID, "受取先として免除");

            verify(connectChargeService).isPayeeSettlementManager(
                    EscrowSourceKind.RECRUITMENT, LISTING_ID, PARTICIPANT_ID, PAYEE_MANAGER_ID);
            verify(waiveService).waive(RECORD_ID, PAYEE_MANAGER_ID, "受取先として免除", true);
            verifyNoInteractions(accessControlService);
            verify(waiveService, never()).resolveListingScope(anyLong());
        }

        @Test
        @DisplayName("AC-19(対): SYSTEM_ADMIN は受取先でなくても免除でき、tx 本体へ payeeSide=false で進む")
        void systemAdmin_isAllowed() {
            givenTarget();
            givenPayee(SYSTEM_ADMIN_ID, false);
            given(accessControlService.isSystemAdmin(SYSTEM_ADMIN_ID)).willReturn(true);

            facade().waive(RECORD_ID, SYSTEM_ADMIN_ID, "運営判断");

            verify(waiveService).waive(RECORD_ID, SYSTEM_ADMIN_ID, "運営判断", false);
            verify(accessControlService, never()).isMember(any(), any(), any());
        }

        @Test
        @DisplayName("AC-18: 債務者本人は免除できず 403（存在を知っている）。tx 本体へ進まない")
        void debtor_isForbidden() {
            givenTarget();
            givenPayee(DEBTOR_ID, false);
            given(accessControlService.isSystemAdmin(DEBTOR_ID)).willReturn(false);

            assertThatThrownBy(() -> facade().waive(RECORD_ID, DEBTOR_ID, "自分で消したい"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(errorOf(e)).isEqualTo(CommonErrorCode.COMMON_002));

            verify(waiveService, never()).waive(any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("AC-19: 受取側でないスコープの在籍者・user_roles の管理者は 403、無関係の部外者は不在と同一の 404")
        void nonPayee_forbiddenForKnowersAndNotFoundForOutsiders() {
            givenTarget();
            given(connectChargeService.isPayeeSettlementManager(any(), anyLong(), anyLong(), anyLong()))
                    .willReturn(false);
            given(accessControlService.isSystemAdmin(any())).willReturn(false);
            given(waiveService.resolveListingScope(LISTING_ID))
                    .willReturn(Optional.of(new ListingScope(RecruitmentScopeType.TEAM, TEAM_ID)));
            given(accessControlService.isMember(MEMBER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isMember(ADMIN_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.isAdminOrAbove(ADMIN_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isMember(OUTSIDER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.isAdminOrAbove(OUTSIDER_ID, TEAM_ID, "TEAM")).willReturn(false);

            for (Long knower : new Long[] {MEMBER_ID, ADMIN_ID}) {
                assertThatThrownBy(() -> facade().waive(RECORD_ID, knower, "免除したい"))
                        .satisfies(e -> assertThat(errorOf(e)).isEqualTo(CommonErrorCode.COMMON_002));
            }
            assertThatThrownBy(() -> facade().waive(RECORD_ID, OUTSIDER_ID, "他人の債権を消したい"))
                    .satisfies(e -> assertThat(errorOf(e)).isEqualTo(CommonErrorCode.COMMON_005));

            verify(waiveService, never()).waive(any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("PERSONAL 募集: 在籍判定の列挙値に無いので判定を撃たず（500 にしない）、債務者以外は 404")
        void personalListing_doesNotFireMembershipCheck() {
            givenTarget();
            givenPayee(OUTSIDER_ID, false);
            given(accessControlService.isSystemAdmin(OUTSIDER_ID)).willReturn(false);
            given(waiveService.resolveListingScope(LISTING_ID))
                    .willReturn(Optional.of(new ListingScope(RecruitmentScopeType.PERSONAL, 9L)));

            assertThatThrownBy(() -> facade().waive(RECORD_ID, OUTSIDER_ID, "免除したい"))
                    .satisfies(e -> assertThat(errorOf(e)).isEqualTo(CommonErrorCode.COMMON_005));

            verify(accessControlService, never()).isMember(any(), any(), any());
            verify(accessControlService, never()).isAdminOrAbove(any(), any(), any());
        }

        @Test
        @DisplayName("親の募集が論理削除済みなら、債務者以外は 404（スコープを解けない者に存在を明かさない）")
        void listingGone_isNotFound() {
            givenTarget();
            givenPayee(OUTSIDER_ID, false);
            given(accessControlService.isSystemAdmin(OUTSIDER_ID)).willReturn(false);
            given(waiveService.resolveListingScope(LISTING_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> facade().waive(RECORD_ID, OUTSIDER_ID, "免除したい"))
                    .satisfies(e -> assertThat(errorOf(e)).isEqualTo(CommonErrorCode.COMMON_005));
        }

        @Test
        @DisplayName("理由の形式違反は存在判定より前に 400（記録の解決も認可も走らない）")
        void invalidReason_failsBeforeResolve() {
            assertThatThrownBy(() -> facade().waive(RECORD_ID, PAYEE_MANAGER_ID, "  "))
                    .satisfies(e -> assertThat(errorOf(e)).isEqualTo(CommonErrorCode.COMMON_001));

            verifyNoInteractions(waiveService, connectChargeService, accessControlService);
        }

        @Test
        @DisplayName("記録が不在なら認可の前に 404 で、認可は走らない")
        void recordMissing_isNotFoundBeforeAuthorization() {
            given(waiveService.resolveWaiveTarget(RECORD_ID))
                    .willThrow(new BusinessException(CommonErrorCode.COMMON_005));

            assertThatThrownBy(() -> facade().waive(RECORD_ID, PAYEE_MANAGER_ID, "免除したい"))
                    .satisfies(e -> assertThat(errorOf(e)).isEqualTo(CommonErrorCode.COMMON_005));

            verifyNoInteractions(connectChargeService, accessControlService);
        }
    }

    // ==========================================================
    // 管理者系（lift・confirm・policy 共通の判定）
    // ==========================================================

    @Nested
    @DisplayName("lift・confirm・policy の管理者判定")
    class AdminChecks {

        private void givenLiftScope() {
            given(penaltyService.resolveLiftScope("TEAM", TEAM_ID, 5L))
                    .willReturn(new RecruitmentPenaltyService.LiftScope(RecruitmentScopeType.TEAM, TEAM_ID));
        }

        @Test
        @DisplayName("管理者: isAdminOrAbove 1 本だけで許可し、SYSTEM_ADMIN・在籍の判定を許可経路で撃たない。認可の後に tx 本体へ")
        void admin_isAllowedWithSingleQuery() {
            givenLiftScope();
            given(accessControlService.isAdminOrAbove(ADMIN_ID, TEAM_ID, "TEAM")).willReturn(true);
            // Facade は Entity を DTO へ変換して返す（D-1 API 境界）。tx 本体の戻りは中身の無い Entity で足りる。
            given(penaltyService.liftPenalty(5L, ADMIN_ID))
                    .willReturn(Mockito.mock(com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity.class));

            facade().liftPenalty("TEAM", TEAM_ID, 5L, ADMIN_ID);

            InOrder order = Mockito.inOrder(penaltyService, accessControlService);
            order.verify(penaltyService).resolveLiftScope("TEAM", TEAM_ID, 5L);
            order.verify(accessControlService).isAdminOrAbove(ADMIN_ID, TEAM_ID, "TEAM");
            order.verify(penaltyService).liftPenalty(5L, ADMIN_ID);
            verify(accessControlService, never()).isSystemAdmin(any());
            verify(accessControlService, never()).isMember(any(), any(), any());
        }

        @Test
        @DisplayName("同スコープ一般メンバーと SYSTEM_ADMIN は 403（是正前どおり）、越境者は対象の不在コードで 404。tx 本体へ進まない")
        void denial_splitsByKnowledge() {
            givenLiftScope();
            given(accessControlService.isAdminOrAbove(any(), anyLong(), any())).willReturn(false);
            given(accessControlService.isSystemAdmin(SYSTEM_ADMIN_ID)).willReturn(true);
            given(accessControlService.isSystemAdmin(MEMBER_ID)).willReturn(false);
            given(accessControlService.isSystemAdmin(OUTSIDER_ID)).willReturn(false);
            given(accessControlService.isMember(MEMBER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isMember(OUTSIDER_ID, TEAM_ID, "TEAM")).willReturn(false);

            for (Long knower : new Long[] {SYSTEM_ADMIN_ID, MEMBER_ID}) {
                assertThatThrownBy(() -> facade().liftPenalty("TEAM", TEAM_ID, 5L, knower))
                        .satisfies(e -> assertThat(errorOf(e)).isEqualTo(CommonErrorCode.COMMON_002));
            }
            assertThatThrownBy(() -> facade().liftPenalty("TEAM", TEAM_ID, 5L, OUTSIDER_ID))
                    .satisfies(e -> assertThat(errorOf(e)).isEqualTo(RecruitmentErrorCode.PENALTY_NOT_FOUND));

            verify(penaltyService, never()).liftPenalty(any(), any());
        }

        @Test
        @DisplayName("スコープが PERSONAL・GLOBAL でも在籍判定・管理者判定を撃たず（500 にしない）、SYSTEM_ADMIN 以外は 404")
        void nonTeamOrOrganization_isConcealedWithoutFiringScopeChecks() {
            given(penaltyService.resolveLiftScope("PERSONAL", 9L, 5L))
                    .willReturn(new RecruitmentPenaltyService.LiftScope(RecruitmentScopeType.PERSONAL, 9L));
            given(accessControlService.isSystemAdmin(OUTSIDER_ID)).willReturn(false);

            assertThatThrownBy(() -> facade().liftPenalty("PERSONAL", 9L, 5L, OUTSIDER_ID))
                    .satisfies(e -> assertThat(errorOf(e)).isEqualTo(RecruitmentErrorCode.PENALTY_NOT_FOUND));

            verify(accessControlService, never()).isAdminOrAbove(any(), any(), any());
            verify(accessControlService, never()).isMember(any(), any(), any());
        }

        @Test
        @DisplayName("解除: 解決（ペナルティ・設定・パス scope の不一致）で不在なら認可の前に 404 で、認可は走らない")
        void lift_resolveFailure_skipsAuthorization() {
            given(penaltyService.resolveLiftScope("TEAM", TEAM_ID, 5L))
                    .willThrow(new BusinessException(RecruitmentErrorCode.PENALTY_NOT_FOUND));

            assertThatThrownBy(() -> facade().liftPenalty("TEAM", TEAM_ID, 5L, ADMIN_ID))
                    .satisfies(e -> assertThat(errorOf(e)).isEqualTo(RecruitmentErrorCode.PENALTY_NOT_FOUND));

            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("確定: 管理者は認可の後に tx 本体（FOR UPDATE はそこで初めて）へ。越境者は LISTING_NOT_FOUND")
        void confirm_adminAllowedOutsiderConcealed() {
            given(listingService.resolveConfirmScope(LISTING_ID, PARTICIPANT_ID))
                    .willReturn(new RecruitmentListingService.ConfirmScope(RecruitmentScopeType.TEAM, TEAM_ID));
            given(accessControlService.isAdminOrAbove(ADMIN_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(OUTSIDER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.isSystemAdmin(OUTSIDER_ID)).willReturn(false);
            given(accessControlService.isMember(OUTSIDER_ID, TEAM_ID, "TEAM")).willReturn(false);

            facade().confirmApplication(LISTING_ID, PARTICIPANT_ID, ADMIN_ID);
            verify(listingService).confirmApplication(PARTICIPANT_ID, ADMIN_ID);

            assertThatThrownBy(() -> facade().confirmApplication(LISTING_ID, PARTICIPANT_ID, OUTSIDER_ID))
                    .satisfies(e -> assertThat(errorOf(e)).isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND));
            verify(listingService, never()).confirmApplication(PARTICIPANT_ID, OUTSIDER_ID);
        }

        @Test
        @DisplayName("ポリシー GET・PATCH・archive: 同じ判定。越境者は LISTING_NOT_FOUND、管理者は tx 本体へ")
        void policy_threeEndpointsShareTheCheck() {
            given(policyService.resolvePolicyScope(9L))
                    .willReturn(new RecruitmentCancellationPolicyService.PolicyScope(
                            RecruitmentScopeType.TEAM, TEAM_ID));
            given(accessControlService.isAdminOrAbove(ADMIN_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(OUTSIDER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.isSystemAdmin(OUTSIDER_ID)).willReturn(false);
            given(accessControlService.isMember(OUTSIDER_ID, TEAM_ID, "TEAM")).willReturn(false);

            facade().getPolicy(9L, ADMIN_ID);
            facade().updatePolicy(9L, ADMIN_ID, null);
            facade().archivePolicy(9L, ADMIN_ID);
            verify(policyService).getPolicy(9L);
            verify(policyService).updatePolicy(9L, null);
            verify(policyService).archivePolicy(9L);

            assertThatThrownBy(() -> facade().getPolicy(9L, OUTSIDER_ID))
                    .satisfies(e -> assertThat(errorOf(e)).isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND));
            assertThatThrownBy(() -> facade().updatePolicy(9L, OUTSIDER_ID, null))
                    .satisfies(e -> assertThat(errorOf(e)).isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND));
            assertThatThrownBy(() -> facade().archivePolicy(9L, OUTSIDER_ID))
                    .satisfies(e -> assertThat(errorOf(e)).isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND));
        }
    }
}
