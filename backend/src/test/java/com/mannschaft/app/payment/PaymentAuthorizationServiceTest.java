package com.mannschaft.app.payment;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.service.PaymentAuthorizationService;
import com.mannschaft.app.payment.service.PaymentItemService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PaymentAuthorizationService} の単体テスト（純 Mockito）。
 *
 * <p>設計書 03_security.md §2「代理払いの認可」の擬似コードに対応する。
 * P1 で実効な経路（SELF / ADMIN_MANUAL）と、P2 注入口（GUARDIAN 等）が
 * P1 では 403 に倒れることをテストで固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentAuthorizationService 単体テスト")
class PaymentAuthorizationServiceTest {

    @Mock private PaymentItemService paymentItemService;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private PaymentAuthorizationService service;

    private static final Long PAYER_ID = 100L;
    private static final Long BENEFICIARY_ID = 200L;
    private static final Long ITEM_ID = 10L;
    private static final Long TEAM_ID = 1L;
    private static final Long ORG_ID = 2L;

    private static PaymentItemEntity teamItem() {
        return PaymentItemEntity.builder().teamId(TEAM_ID).build();
    }

    private static PaymentItemEntity orgItem() {
        return PaymentItemEntity.builder().organizationId(ORG_ID).build();
    }

    @Nested
    @DisplayName("SELF（本人払い）")
    class Self {

        @Test
        @DisplayName("正常系: payer == beneficiary は常に SELF（スコープ解決・ADMIN 判定なし）")
        void 本人払いはSELF() {
            PayerRelationship result =
                    service.authorizePayment(BENEFICIARY_ID, BENEFICIARY_ID, ITEM_ID, false);

            assertThat(result).isEqualTo(PayerRelationship.SELF);
            // 本人払いは権原検証を要さない → スコープ解決も ADMIN 判定も呼ばれない。
            verifyNoInteractions(paymentItemService);
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("正常系: manualRecordByAdmin=true でも payer == beneficiary なら SELF が優先")
        void 本人払いは手動記録フラグより優先() {
            PayerRelationship result =
                    service.authorizePayment(BENEFICIARY_ID, BENEFICIARY_ID, ITEM_ID, true);

            assertThat(result).isEqualTo(PayerRelationship.SELF);
            verifyNoInteractions(accessControlService);
        }
    }

    @Nested
    @DisplayName("ADMIN_MANUAL（管理者手動記録）")
    class AdminManual {

        @Test
        @DisplayName("正常系: team スコープ ADMIN かつ manualRecordByAdmin=true は ADMIN_MANUAL")
        void teamADMINの手動記録はADMIN_MANUAL() {
            when(paymentItemService.findByIdOrThrow(ITEM_ID)).thenReturn(teamItem());
            when(accessControlService.isAdminOrAbove(PAYER_ID, TEAM_ID, "TEAM")).thenReturn(true);

            PayerRelationship result =
                    service.authorizePayment(PAYER_ID, BENEFICIARY_ID, ITEM_ID, true);

            assertThat(result).isEqualTo(PayerRelationship.ADMIN_MANUAL);
            verify(accessControlService).isAdminOrAbove(PAYER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("正常系: organization スコープ ADMIN かつ manualRecordByAdmin=true は ADMIN_MANUAL")
        void orgADMINの手動記録はADMIN_MANUAL() {
            when(paymentItemService.findByIdOrThrow(ITEM_ID)).thenReturn(orgItem());
            when(accessControlService.isAdminOrAbove(PAYER_ID, ORG_ID, "ORGANIZATION")).thenReturn(true);

            PayerRelationship result =
                    service.authorizePayment(PAYER_ID, BENEFICIARY_ID, ITEM_ID, true);

            assertThat(result).isEqualTo(PayerRelationship.ADMIN_MANUAL);
            verify(accessControlService).isAdminOrAbove(PAYER_ID, ORG_ID, "ORGANIZATION");
        }

        @Test
        @DisplayName("異常系: 非 ADMIN は manualRecordByAdmin=true でも 403")
        void 非ADMINの手動記録は403() {
            when(paymentItemService.findByIdOrThrow(ITEM_ID)).thenReturn(teamItem());
            when(accessControlService.isAdminOrAbove(PAYER_ID, TEAM_ID, "TEAM")).thenReturn(false);

            assertThatThrownBy(() ->
                    service.authorizePayment(PAYER_ID, BENEFICIARY_ID, ITEM_ID, true))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED);
        }

        @Test
        @DisplayName("異常系: ADMIN でも manualRecordByAdmin=false なら ADMIN_MANUAL 経路に入らず 403")
        void 手動記録フラグなしのADMINは403() {
            // manualRecordByAdmin=false の場合 ADMIN 判定自体を行わず 403 に倒れる。
            assertThatThrownBy(() ->
                    service.authorizePayment(PAYER_ID, BENEFICIARY_ID, ITEM_ID, false))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED);

            verify(accessControlService, never()).isAdminOrAbove(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("異常系（fail-safe）: スコープ未設定の不整合 payment_item は ADMIN 判定不能で 403")
        void スコープ未設定は拒否側に倒す() {
            when(paymentItemService.findByIdOrThrow(ITEM_ID))
                    .thenReturn(PaymentItemEntity.builder().build()); // team/org 両方 null

            assertThatThrownBy(() ->
                    service.authorizePayment(PAYER_ID, BENEFICIARY_ID, ITEM_ID, true))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED);

            // スコープが無いため ADMIN 判定自体を呼ばない。
            verify(accessControlService, never()).isAdminOrAbove(anyLong(), anyLong(), any());
        }
    }

    @Nested
    @DisplayName("無権原（他人）")
    class NotAuthorized {

        @Test
        @DisplayName("異常系: 権原なき他人の受益者は 403（IDOR 防止）")
        void 無権原の他人は403() {
            // manualRecordByAdmin=false かつ payer != beneficiary → どの権原も成立しない。
            assertThatThrownBy(() ->
                    service.authorizePayment(PAYER_ID, BENEFICIARY_ID, ITEM_ID, false))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED);
        }
    }

    @Nested
    @DisplayName("GUARDIAN / GUARDIAN_PROXY / PROXY_GRANT（P2 注入口）")
    class FutureInjectionPoints {

        @Test
        @DisplayName("将来固定: 保護者/grant 等の代理払い権原は P1 では未評価のため 403")
        void 保護者やgrant経路はP1では403() {
            // P1 では GUARDIAN / GUARDIAN_PROXY / PROXY_GRANT の評価を一切行わない。
            // 仮に保護者リンクや grant が存在しても、P1 ではこの経路に到達せず 403 に倒れることを固定する。
            // （Repository をモックしても本サービスが呼ばないため、403 のまま）
            lenient().when(accessControlService.isAdminOrAbove(anyLong(), anyLong(), eq("TEAM")))
                    .thenReturn(false);

            assertThatThrownBy(() ->
                    service.authorizePayment(PAYER_ID, BENEFICIARY_ID, ITEM_ID, false))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED);
        }
    }
}
