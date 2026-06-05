package com.mannschaft.app.payment.service;

import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.payment.PayerRelationship;
import com.mannschaft.app.payment.PaymentProxyGrantStatus;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.PaymentProxyGrantRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * F08.9 代理払い認可サービス（払い手 ≠ 受益者の核心 IDOR 対策）。
 *
 * <p>決済起票の直前に「この払い手がこの受益者のこの会費項目を払う権原」を必ず検証する。
 * 権原が欠落する場合は一切起票させず {@code MEMBERSHIP_PAYER_NOT_AUTHORIZED}（403）を投げる。</p>
 *
 * <p><b>権原はキャッシュせず毎回実行時評価する</b>（03_security §2「権原の失効」）。
 * 保護者リンク取消・grant 失効・受益者退会で権原は即時消失するため、判定結果を保持してはならない。</p>
 *
 * <p><b>本サービスで実効な経路（P2 注入完了）:</b>
 * <ul>
 *   <li>{@code SELF} — 受益者本人が支払う（常に許可）</li>
 *   <li>{@code GUARDIAN} — 払い手が受益者の承認済み保護者（parental_consent_links / user_care_links）</li>
 *   <li>{@code PROXY_GRANT} — payment_proxy_grants の ACTIVE かつ有効期間内 grant</li>
 *   <li>{@code ADMIN_MANUAL} — 当該 payment_item のスコープ（team/org）の ADMIN が手動記録する</li>
 * </ul>
 * 上記いずれにも該当しなければ 403。
 * {@code GUARDIAN_PROXY}（後見切替セッション中の保護者代理払い）は P3c-2 で実評価する:
 * GUARDIAN 成立 かつ {@code isProxy()} かつ「切替対象の子＝受益者」のとき GUARDIAN_PROXY を
 * GUARDIAN より優先して返す（別の子へ acting-as 中は GUARDIAN）。</p>
 *
 * <p><b>ドメイン境界:</b> {@code @Transactional(readOnly)} は payment ドメインに閉じる。
 * user/team は ID のみ受け取り Entity を直接参照しない。ADMIN 判定は共通ヘルパー
 * {@link AccessControlService} を経由する（role ドメインへの直接越境を避ける）。
 * GUARDIAN 経路の保護者リンク照会は auth の {@link ParentalConsentService} / family の
 * {@link CareLinkService} の公開 boolean メソッド経由で行い、両ドメインの Entity / Repository を
 * 直接参照しない。PROXY_GRANT は payment ドメイン自身の {@link PaymentProxyGrantRepository} を参照する。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/03_security.md §2</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentAuthorizationService {

    private final PaymentItemService paymentItemService;
    private final AccessControlService accessControlService;
    private final ParentalConsentService parentalConsentService;
    private final CareLinkService careLinkService;
    private final PaymentProxyGrantRepository paymentProxyGrantRepository;
    /**
     * 後見切替セッション（acting-as）判定用の RequestScope Bean（F14.1）。
     * 単一スコープの本 Service へは Spring の scoped proxy（{@code @RequestScope} 既定の
     * {@code TARGET_CLASS} プロキシ）経由で注入される（{@link AuthenticationCriticalOperationGuard} 同型）。
     */
    private final ProxyInputContext proxyInputContext;

    /**
     * 払い手が受益者の会費項目を支払う権原を検証し、成立した関係を返す。
     *
     * <p>評価順序（03_security §2 の擬似コードに準拠・毎回実行時評価・キャッシュしない）:</p>
     * <ol>
     *   <li>{@code payerUserId == beneficiaryUserId} → {@link PayerRelationship#SELF}（常に許可）</li>
     *   <li>GUARDIAN（payer が beneficiary の承認済み保護者 parental_consent_links=APPROVED
     *       または 見守り PARENT user_care_links=ACTIVE）→ {@link PayerRelationship#GUARDIAN}</li>
     *   <li>PROXY_GRANT（payment_proxy_grants に ACTIVE かつ {@code now ∈ [effective_from, effective_until]}
     *       の有効 grant が存在）→ {@link PayerRelationship#PROXY_GRANT}</li>
     *   <li>{@code manualRecordByAdmin} かつ呼び出し元が当該 paymentItem スコープの ADMIN
     *       → {@link PayerRelationship#ADMIN_MANUAL}</li>
     *   <li>いずれも不成立 → {@code MEMBERSHIP_PAYER_NOT_AUTHORIZED}（403）</li>
     * </ol>
     *
     * <p>{@link PayerRelationship#GUARDIAN_PROXY} は P3c-2 で実評価する: 上記 2 の GUARDIAN 成立時、
     * 後見切替セッション中（{@code isProxy()}）かつ「切替対象の子（subjectUserId）＝受益者」なら
     * GUARDIAN_PROXY を GUARDIAN より優先して返す（subject≠beneficiary の acting-as 中は GUARDIAN）。</p>
     *
     * <p>IDOR 防止: {@code beneficiaryUserId} は呼出側 payload から渡るが、上記権原検証なしには
     * 一切「許可」を返さない。権原なき他人の受益者については常に 403。</p>
     *
     * @param payerUserId         実際に決済する払い手ユーザーID
     * @param beneficiaryUserId   会費の受益者ユーザーID
     * @param paymentItemId       支払い対象の payment_items.id（スコープ解決に使用）
     * @param manualRecordByAdmin ADMIN による手動記録か（true のとき ADMIN_MANUAL 経路を評価）
     * @return 成立した払い手・受益者の関係（{@link PayerRelationship}）
     * @throws BusinessException 権原が欠落する場合（{@code MEMBERSHIP_PAYER_NOT_AUTHORIZED} 403）
     */
    public PayerRelationship authorizePayment(Long payerUserId,
                                              Long beneficiaryUserId,
                                              Long paymentItemId,
                                              boolean manualRecordByAdmin) {
        // 1. 本人払い（SELF）は常に許可。スコープ解決すら不要。
        if (payerUserId != null && payerUserId.equals(beneficiaryUserId)) {
            return PayerRelationship.SELF;
        }

        // 2. GUARDIAN / GUARDIAN_PROXY — 保護者リンクによる権原（毎回実行時評価・キャッシュしない）。
        //    parental_consent_links(child=beneficiary, parent=payer).status == APPROVED → 権原成立
        //    user_care_links(recipient=beneficiary, watcher=payer, relationship=PARENT).status == ACTIVE → 権原成立
        //    ドメイン境界遵守: auth/family の Entity/Repository を直接参照せず、各 Service の公開 boolean メソッド経由で判定する。
        if (isGuardian(payerUserId, beneficiaryUserId)) {
            // [P3c-2] 後見切替セッション中（X-Proxy-For-User-Id 付き・isProxy）の保護者代理払いは
            //   GUARDIAN_PROXY として区別記録する（「子の自己払い」と誤読させない・03_security §2）。
            //   ただし区別するのは「切替対象の子（subjectUserId）＝受益者（beneficiaryUserId）」のときのみ。
            //   別の子へ acting-as 中（subject≠beneficiary）の支払いは従来どおり GUARDIAN（誤分類しない）。
            //   権原評価自体は GUARDIAN と同じ（保護者リンク）。本人払い（SELF）は §1 で先に確定済み。
            if (isGuardianProxyContext(beneficiaryUserId)) {
                return PayerRelationship.GUARDIAN_PROXY;
            }
            return PayerRelationship.GUARDIAN;
        }

        // 4. PROXY_GRANT — 第三者代理払い grant（非後見・祖父母・スポンサー等）。
        //    payment_proxy_grants に (beneficiary, payer, item|包括NULL)・status=ACTIVE・
        //    now ∈ [effective_from, effective_until] の有効 grant があれば PROXY_GRANT。
        //    grant の引き当て・有効期間判定は Repository クエリに委譲する（findActiveGrant）。
        if (hasActiveProxyGrant(payerUserId, beneficiaryUserId, paymentItemId)) {
            return PayerRelationship.PROXY_GRANT;
        }

        // 5. ADMIN_MANUAL — 当該 payment_item のスコープ（team/org）の ADMIN による手動記録。
        if (manualRecordByAdmin && isScopeAdmin(payerUserId, paymentItemId)) {
            return PayerRelationship.ADMIN_MANUAL;
        }

        // 6. いずれの権原も不成立。無権原として明示的に 403 に倒す。
        log.info("代理払い権原なし: payer={}, beneficiary={}, itemId={}, manualByAdmin={}",
                payerUserId, beneficiaryUserId, paymentItemId, manualRecordByAdmin);
        throw new BusinessException(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED);
    }

    /**
     * 払い手が受益者の承認済み保護者（GUARDIAN）かを判定する。
     *
     * <p>権原は 2 系統のいずれかで成立する（OR）:</p>
     * <ul>
     *   <li>auth: parental_consent_links（child=beneficiary, parent=payer, status=APPROVED）</li>
     *   <li>family: user_care_links（recipient=beneficiary, watcher=payer, relationship=PARENT, status=ACTIVE）</li>
     * </ul>
     *
     * <p>ドメイン境界遵守のため auth/family の Entity/Repository を直接参照せず、各 Service の
     * 公開 boolean メソッドのみを呼ぶ。判定はキャッシュせず毎回実行時評価する
     * （保護者リンク取消で即時に権原消失するため・03_security §2「権原の失効」）。</p>
     */
    private boolean isGuardian(Long payerUserId, Long beneficiaryUserId) {
        if (payerUserId == null || beneficiaryUserId == null) {
            return false;
        }
        return parentalConsentService.isApprovedGuardian(payerUserId, beneficiaryUserId)
                || careLinkService.isActiveParentWatcher(payerUserId, beneficiaryUserId);
    }

    /**
     * 後見切替セッション中の保護者代理払い（GUARDIAN_PROXY）として記録すべき文脈かを判定する（F08.9 P3c-2）。
     *
     * <p>条件は「{@link ProxyInputContext#isProxy()}（X-Proxy-For-User-Id 付き）かつ
     * 切替対象の子（{@code subjectUserId}）＝受益者（{@code beneficiaryUserId}）」。
     * 切替中でない通常払いや、別の子へ acting-as 中（subject≠beneficiary）の支払いは {@code false}
     * （その場合は GUARDIAN として分類する・「子の自己払い」と誤読させない区別記録の趣旨に沿う）。</p>
     *
     * <p>権原評価そのものは {@link #isGuardian} が担い、本メソッドは分類（GUARDIAN か GUARDIAN_PROXY か）
     * のみを決める。</p>
     */
    private boolean isGuardianProxyContext(Long beneficiaryUserId) {
        return proxyInputContext.isProxy()
                && beneficiaryUserId != null
                && beneficiaryUserId.equals(proxyInputContext.getSubjectUserId());
    }

    /**
     * 払い手が受益者に対する有効な第三者代理払い grant（PROXY_GRANT）を保有するかを判定する。
     *
     * <p>payment ドメイン自身の {@link PaymentProxyGrantRepository#findActiveGrant} に委譲し、
     * status=ACTIVE かつ {@code now ∈ [effective_from, effective_until]} の grant を引き当てる。
     * payment_item_id 指定 grant と包括 grant（item=NULL）の双方を対象とする（Repository クエリ側で吸収）。
     * 判定はキャッシュせず毎回実行時評価する（grant 失効で即時に権原消失するため）。</p>
     */
    private boolean hasActiveProxyGrant(Long payerUserId, Long beneficiaryUserId, Long paymentItemId) {
        if (payerUserId == null || beneficiaryUserId == null) {
            return false;
        }
        return paymentProxyGrantRepository.findActiveGrant(
                beneficiaryUserId,
                payerUserId,
                paymentItemId,
                PaymentProxyGrantStatus.ACTIVE,
                LocalDateTime.now()
        ).isPresent();
    }

    /**
     * 払い手が当該 payment_item のスコープ（team または organization）の ADMIN 以上かを判定する。
     *
     * <p>payment_items は team_id または organization_id のいずれかを持つ。
     * team スコープなら "TEAM"、それ以外（organization_id 設定時）は "ORGANIZATION" で
     * {@link AccessControlService#isAdminOrAbove} を評価する。</p>
     */
    private boolean isScopeAdmin(Long payerUserId, Long paymentItemId) {
        if (payerUserId == null || paymentItemId == null) {
            return false;
        }
        PaymentItemEntity item = paymentItemService.findByIdOrThrow(paymentItemId);
        if (item.getTeamId() != null) {
            return accessControlService.isAdminOrAbove(payerUserId, item.getTeamId(), "TEAM");
        }
        if (item.getOrganizationId() != null) {
            return accessControlService.isAdminOrAbove(payerUserId, item.getOrganizationId(), "ORGANIZATION");
        }
        // スコープ未設定の不整合データは ADMIN 判定不能 → 拒否側（fail-safe）に倒す。
        log.warn("payment_item にスコープ（team/org）が設定されていません: itemId={}", paymentItemId);
        return false;
    }
}
