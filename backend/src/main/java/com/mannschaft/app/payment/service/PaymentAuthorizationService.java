package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.payment.PayerRelationship;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F08.9 代理払い認可サービス（払い手 ≠ 受益者の核心 IDOR 対策）。
 *
 * <p>決済起票の直前に「この払い手がこの受益者のこの会費項目を払う権原」を必ず検証する。
 * 権原が欠落する場合は一切起票させず {@code MEMBERSHIP_PAYER_NOT_AUTHORIZED}（403）を投げる。</p>
 *
 * <p><b>権原はキャッシュせず毎回実行時評価する</b>（03_security §2「権原の失効」）。
 * 保護者リンク取消・grant 失効・受益者退会で権原は即時消失するため、判定結果を保持してはならない。</p>
 *
 * <p><b>本サービスのスコープ（P1 で実効な経路）:</b>
 * <ul>
 *   <li>{@code SELF} — 受益者本人が支払う（常に許可）</li>
 *   <li>{@code ADMIN_MANUAL} — 当該 payment_item のスコープ（team/org）の ADMIN が手動記録する</li>
 * </ul>
 * 上記いずれにも該当しなければ 403。
 * GUARDIAN（保護者リンク）/ GUARDIAN_PROXY（後見切替セッション）/ PROXY_GRANT（payment_proxy_grants）は
 * <b>P2 で評価を注入する分岐口</b>として本クラス内に TODO 明示する。P1 では評価せず 403 に倒す
 * （未実装の経路を黙って通さない・症状を隠さない）。</p>
 *
 * <p><b>ドメイン境界:</b> {@code @Transactional(readOnly)} は payment ドメインに閉じる。
 * user/team は ID のみ受け取り Entity を直接参照しない。ADMIN 判定は共通ヘルパー
 * {@link AccessControlService} を経由する（role ドメインへの直接越境を避ける）。</p>
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

    /**
     * 払い手が受益者の会費項目を支払う権原を検証し、成立した関係を返す。
     *
     * <p>評価順序（03_security §2 の擬似コードに準拠）:</p>
     * <ol>
     *   <li>{@code payerUserId == beneficiaryUserId} → {@link PayerRelationship#SELF}（常に許可）</li>
     *   <li><b>[P2 注入口]</b> GUARDIAN（parental_consent_links / user_care_links）</li>
     *   <li><b>[P2 注入口]</b> GUARDIAN_PROXY（後見切替セッション {@code X-Proxy-For-User-Id} 下の保護者代理）</li>
     *   <li><b>[P2 注入口]</b> PROXY_GRANT（payment_proxy_grants の ACTIVE かつ有効期間内 grant）</li>
     *   <li>{@code manualRecordByAdmin} かつ呼び出し元が当該 paymentItem スコープの ADMIN
     *       → {@link PayerRelationship#ADMIN_MANUAL}</li>
     *   <li>いずれも不成立 → {@code MEMBERSHIP_PAYER_NOT_AUTHORIZED}（403）</li>
     * </ol>
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

        // 2. [P2 注入口] GUARDIAN — 保護者リンクによる権原。
        //    parentalConsentLink(child=beneficiary, parent=payer).status == APPROVED → GUARDIAN
        //    userCareLink(recipient=beneficiary, watcher=payer, relationship=PARENT).status == ACTIVE → GUARDIAN
        //    P1 では評価しない（リポジトリ未配線）。実装時は accessControlService.checkCareLink 等を流用しつつ
        //    parental_consent_links（APPROVED）も併せて評価する。
        // TODO(F08.9 P2): GUARDIAN 経路の評価をここに注入する。

        // 3. [P2 注入口] GUARDIAN_PROXY — 後見切替セッション中の保護者代理払い。
        //    権原成立は GUARDIAN と同じ（保護者リンク）だが、X-Proxy-For-User-Id 付き
        //    （ProxyInputContextFilter / isProxy()）の場合に GUARDIAN_PROXY として区別記録する。
        //    「子の自己払い」と誤読させないための分類であり、権原評価自体は GUARDIAN に依存する。
        // TODO(F08.9 P2): 後見切替セッション判定（isProxy）を注入し、GUARDIAN 成立時に GUARDIAN_PROXY を返す。

        // 4. [P2 注入口] PROXY_GRANT — 第三者代理払い grant（非後見・祖父母・スポンサー等）。
        //    paymentProxyGrantRepository.findActiveGrant(beneficiary, payer, item, ACTIVE, now) が存在し、
        //    now が [effective_from, effective_until] 範囲内なら PROXY_GRANT。grant_id を記録する。
        //    P1 では評価しない（Repository は Wave2 で配線済みだが本サービスでは未評価）。
        // TODO(F08.9 P2): PaymentProxyGrantRepository.findActiveGrant を注入して PROXY_GRANT を評価する。

        // 5. ADMIN_MANUAL — 当該 payment_item のスコープ（team/org）の ADMIN による手動記録。
        if (manualRecordByAdmin && isScopeAdmin(payerUserId, paymentItemId)) {
            return PayerRelationship.ADMIN_MANUAL;
        }

        // 6. いずれの権原も不成立。未実装経路（GUARDIAN/GUARDIAN_PROXY/PROXY_GRANT）も
        //    黙って通さず、無権原として明示的に 403 に倒す。
        log.info("代理払い権原なし: payer={}, beneficiary={}, itemId={}, manualByAdmin={}",
                payerUserId, beneficiaryUserId, paymentItemId, manualRecordByAdmin);
        throw new BusinessException(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED);
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
