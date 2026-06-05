package com.mannschaft.app.payment;

import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.entity.PaymentProxyGrantEntity;
import com.mannschaft.app.payment.repository.PaymentProxyGrantRepository;
import com.mannschaft.app.payment.service.PaymentAuthorizationService;
import com.mannschaft.app.payment.service.PaymentItemService;
import com.mannschaft.app.proxy.ProxyInputContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PaymentAuthorizationService} の単体テスト（純 Mockito）。
 *
 * <p>設計書 03_security.md §2「代理払いの認可」の擬似コードに対応する。
 * 実効な経路（SELF / GUARDIAN / PROXY_GRANT / ADMIN_MANUAL）と、後見切替（P3）未実装ゆえ
 * 評価しない GUARDIAN_PROXY 経路の固定をテストで明示する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentAuthorizationService 単体テスト")
class PaymentAuthorizationServiceTest {

    @Mock private PaymentItemService paymentItemService;
    @Mock private AccessControlService accessControlService;
    @Mock private ParentalConsentService parentalConsentService;
    @Mock private CareLinkService careLinkService;
    @Mock private PaymentProxyGrantRepository paymentProxyGrantRepository;
    @Mock private ProxyInputContext proxyInputContext;

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
            // 本人払いは権原検証を要さない → スコープ解決・保護者照会・grant 照会・ADMIN 判定すべて呼ばれない。
            verifyNoInteractions(paymentItemService);
            verifyNoInteractions(accessControlService);
            verifyNoInteractions(parentalConsentService);
            verifyNoInteractions(careLinkService);
            verifyNoInteractions(paymentProxyGrantRepository);
        }

        @Test
        @DisplayName("正常系: manualRecordByAdmin=true でも payer == beneficiary なら SELF が優先")
        void 本人払いは手動記録フラグより優先() {
            PayerRelationship result =
                    service.authorizePayment(BENEFICIARY_ID, BENEFICIARY_ID, ITEM_ID, true);

            assertThat(result).isEqualTo(PayerRelationship.SELF);
            verifyNoInteractions(accessControlService);
            verifyNoInteractions(parentalConsentService);
            verifyNoInteractions(careLinkService);
            verifyNoInteractions(paymentProxyGrantRepository);
        }
    }

    @Nested
    @DisplayName("GUARDIAN（保護者リンク）")
    class Guardian {

        @Test
        @DisplayName("正常系: 保護者同意 APPROVED の払い手は GUARDIAN（grant/ADMIN 判定に進まない）")
        void 保護者同意APPROVEDはGUARDIAN() {
            when(parentalConsentService.isApprovedGuardian(PAYER_ID, BENEFICIARY_ID)).thenReturn(true);

            PayerRelationship result =
                    service.authorizePayment(PAYER_ID, BENEFICIARY_ID, ITEM_ID, false);

            assertThat(result).isEqualTo(PayerRelationship.GUARDIAN);
            verify(parentalConsentService).isApprovedGuardian(PAYER_ID, BENEFICIARY_ID);
            // GUARDIAN 成立で打ち切り → grant 照会・スコープ解決には進まない。
            verifyNoInteractions(paymentProxyGrantRepository);
            verifyNoInteractions(paymentItemService);
        }

        @Test
        @DisplayName("正常系: 見守り PARENT が ACTIVE の払い手は GUARDIAN（同意リンクが無くても成立）")
        void 見守りPARENT_ACTIVEはGUARDIAN() {
            when(parentalConsentService.isApprovedGuardian(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(careLinkService.isActiveParentWatcher(PAYER_ID, BENEFICIARY_ID)).thenReturn(true);

            PayerRelationship result =
                    service.authorizePayment(PAYER_ID, BENEFICIARY_ID, ITEM_ID, false);

            assertThat(result).isEqualTo(PayerRelationship.GUARDIAN);
            verify(careLinkService).isActiveParentWatcher(PAYER_ID, BENEFICIARY_ID);
            verifyNoInteractions(paymentProxyGrantRepository);
        }
    }

    @Nested
    @DisplayName("PROXY_GRANT（第三者代理払い grant）")
    class ProxyGrant {

        @Test
        @DisplayName("正常系: ACTIVE かつ有効期間内の grant がある払い手は PROXY_GRANT")
        void 有効grantはPROXY_GRANT() {
            // 保護者経路は不成立。
            when(parentalConsentService.isApprovedGuardian(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(careLinkService.isActiveParentWatcher(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            // Repository が有効 grant を引き当てる。
            when(paymentProxyGrantRepository.findActiveGrant(
                    eq(BENEFICIARY_ID), eq(PAYER_ID), eq(ITEM_ID),
                    eq(PaymentProxyGrantStatus.ACTIVE), any(LocalDateTime.class)))
                    .thenReturn(Optional.of(PaymentProxyGrantEntity.builder()
                            .beneficiaryUserId(BENEFICIARY_ID)
                            .payerUserId(PAYER_ID)
                            .status(PaymentProxyGrantStatus.ACTIVE)
                            .build()));

            PayerRelationship result =
                    service.authorizePayment(PAYER_ID, BENEFICIARY_ID, ITEM_ID, false);

            assertThat(result).isEqualTo(PayerRelationship.PROXY_GRANT);
            verify(paymentProxyGrantRepository).findActiveGrant(
                    eq(BENEFICIARY_ID), eq(PAYER_ID), eq(ITEM_ID),
                    eq(PaymentProxyGrantStatus.ACTIVE), any(LocalDateTime.class));
            // PROXY_GRANT 成立で打ち切り → ADMIN スコープ解決には進まない。
            verifyNoInteractions(paymentItemService);
        }

        @Test
        @DisplayName("異常系: 期限切れ / REVOKED 等で findActiveGrant が空なら 403")
        void 失効grantは403() {
            when(parentalConsentService.isApprovedGuardian(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(careLinkService.isActiveParentWatcher(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            // findActiveGrant は status/now 条件を満たさない grant を返さない（empty）。
            when(paymentProxyGrantRepository.findActiveGrant(
                    eq(BENEFICIARY_ID), eq(PAYER_ID), eq(ITEM_ID),
                    eq(PaymentProxyGrantStatus.ACTIVE), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.authorizePayment(PAYER_ID, BENEFICIARY_ID, ITEM_ID, false))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED);
        }
    }

    @Nested
    @DisplayName("ADMIN_MANUAL（管理者手動記録）")
    class AdminManual {

        @Test
        @DisplayName("正常系: team スコープ ADMIN かつ manualRecordByAdmin=true は ADMIN_MANUAL")
        void teamADMINの手動記録はADMIN_MANUAL() {
            when(parentalConsentService.isApprovedGuardian(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(careLinkService.isActiveParentWatcher(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(paymentProxyGrantRepository.findActiveGrant(
                    eq(BENEFICIARY_ID), eq(PAYER_ID), eq(ITEM_ID),
                    eq(PaymentProxyGrantStatus.ACTIVE), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());
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
            when(parentalConsentService.isApprovedGuardian(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(careLinkService.isActiveParentWatcher(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(paymentProxyGrantRepository.findActiveGrant(
                    eq(BENEFICIARY_ID), eq(PAYER_ID), eq(ITEM_ID),
                    eq(PaymentProxyGrantStatus.ACTIVE), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());
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
            when(parentalConsentService.isApprovedGuardian(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(careLinkService.isActiveParentWatcher(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(paymentProxyGrantRepository.findActiveGrant(
                    eq(BENEFICIARY_ID), eq(PAYER_ID), eq(ITEM_ID),
                    eq(PaymentProxyGrantStatus.ACTIVE), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());
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
            // 保護者・grant いずれも不成立。manualRecordByAdmin=false の場合 ADMIN 判定自体を行わず 403 に倒れる。
            when(parentalConsentService.isApprovedGuardian(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(careLinkService.isActiveParentWatcher(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(paymentProxyGrantRepository.findActiveGrant(
                    eq(BENEFICIARY_ID), eq(PAYER_ID), eq(ITEM_ID),
                    eq(PaymentProxyGrantStatus.ACTIVE), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());

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
            when(parentalConsentService.isApprovedGuardian(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(careLinkService.isActiveParentWatcher(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(paymentProxyGrantRepository.findActiveGrant(
                    eq(BENEFICIARY_ID), eq(PAYER_ID), eq(ITEM_ID),
                    eq(PaymentProxyGrantStatus.ACTIVE), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());
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
        @DisplayName("異常系: 保護者でも grant 保有者でもない第三者は 403（IDOR 防止）")
        void 無権原の他人は403() {
            // すべての権原経路が不成立。
            when(parentalConsentService.isApprovedGuardian(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(careLinkService.isActiveParentWatcher(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(paymentProxyGrantRepository.findActiveGrant(
                    eq(BENEFICIARY_ID), eq(PAYER_ID), eq(ITEM_ID),
                    eq(PaymentProxyGrantStatus.ACTIVE), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.authorizePayment(PAYER_ID, BENEFICIARY_ID, ITEM_ID, false))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED);
        }
    }

    @Nested
    @DisplayName("GUARDIAN_PROXY（後見切替セッション中の保護者代理払い・F08.9 P3c-2 実評価）")
    class GuardianProxy {

        @Test
        @DisplayName("正常系: isProxy かつ subject==beneficiary の保護者払いは GUARDIAN_PROXY（GUARDIAN より優先）")
        void isProxyかつsubject一致はGUARDIAN_PROXY() {
            // 保護者リンク成立（権原は GUARDIAN と同じ）。
            when(parentalConsentService.isApprovedGuardian(PAYER_ID, BENEFICIARY_ID)).thenReturn(true);
            // 後見切替セッション中（X-Proxy-For-User-Id=子）かつ切替対象の子＝受益者。
            when(proxyInputContext.isProxy()).thenReturn(true);
            when(proxyInputContext.getSubjectUserId()).thenReturn(BENEFICIARY_ID);

            PayerRelationship result =
                    service.authorizePayment(PAYER_ID, BENEFICIARY_ID, ITEM_ID, false);

            assertThat(result).isEqualTo(PayerRelationship.GUARDIAN_PROXY);
            // 区別記録のみ。grant/ADMIN 判定には進まない。
            verifyNoInteractions(paymentProxyGrantRepository);
            verifyNoInteractions(paymentItemService);
        }

        @Test
        @DisplayName("正常系: isProxy だが subject!=beneficiary（別の子へ acting-as 中）の払いは GUARDIAN（誤分類しない）")
        void isProxyだがsubject不一致はGUARDIAN() {
            when(parentalConsentService.isApprovedGuardian(PAYER_ID, BENEFICIARY_ID)).thenReturn(true);
            when(proxyInputContext.isProxy()).thenReturn(true);
            // 切替対象は別の子（999）であり、受益者（BENEFICIARY_ID=200）とは一致しない。
            when(proxyInputContext.getSubjectUserId()).thenReturn(999L);

            PayerRelationship result =
                    service.authorizePayment(PAYER_ID, BENEFICIARY_ID, ITEM_ID, false);

            assertThat(result).isEqualTo(PayerRelationship.GUARDIAN);
        }

        @Test
        @DisplayName("正常系: 非 proxy（通常払い）の保護者払いは GUARDIAN")
        void 非proxyはGUARDIAN() {
            when(parentalConsentService.isApprovedGuardian(PAYER_ID, BENEFICIARY_ID)).thenReturn(true);
            // isProxy() は既定 false（後見切替セッション外）。

            PayerRelationship result =
                    service.authorizePayment(PAYER_ID, BENEFICIARY_ID, ITEM_ID, false);

            assertThat(result).isEqualTo(PayerRelationship.GUARDIAN);
        }

        @Test
        @DisplayName("固定: 保護者リンクが無ければ isProxy でも GUARDIAN_PROXY にならず 403（権原は GUARDIAN に依存）")
        void 保護者リンクなしはisProxyでも403() {
            // isProxy だが保護者リンク・grant いずれも不成立 → 権原なしで 403。
            when(parentalConsentService.isApprovedGuardian(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(careLinkService.isActiveParentWatcher(PAYER_ID, BENEFICIARY_ID)).thenReturn(false);
            when(paymentProxyGrantRepository.findActiveGrant(
                    eq(BENEFICIARY_ID), eq(PAYER_ID), eq(ITEM_ID),
                    eq(PaymentProxyGrantStatus.ACTIVE), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.authorizePayment(PAYER_ID, BENEFICIARY_ID, ITEM_ID, false))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED);
        }
    }
}
