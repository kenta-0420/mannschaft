package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.MembershipSubscriptionStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F08.9 P5 会員継続課金サービス（membership_subscriptions）。
 *
 * <p><b>第一波（P5-1）の責務は取得系のみ。</b> subscribe（加入）/ cancel（期末解約）/ skip（今月スキップ）/
 * resume（再開）/ Webhook 連携（invoice.* / subscription.deleted）の本体は後続波で実装する。</p>
 *
 * <p><b>方針（症状を隠すガワは作らない）:</b> 未実装メソッドのプレースホルダ（{@code UnsupportedOperationException} を
 * 投げるガワ）は<b>あえて置かない</b>。公開メソッドは波ごとに本物を追加する方針とし、本波では
 * {@link MembershipSubscriptionRepository} を包む素朴な取得系（払い手視点 / 受領主体視点）のみを公開する。
 * これにより「存在するが動かない API」を残さず、未実装は API 自体が未存在であることで正直に表現する。</p>
 *
 * <h2>後続波の実装計画（本波では未実装）</h2>
 * <ul>
 *   <li><b>第二波（subscribe）:</b> SetupIntent で保存した PM・受領者 Connect 口座・billing_anchor_day で
 *       Stripe Subscription を作成し PENDING で起票。加入時に {@code FeePolicyResolver(MEMBERSHIP)} で
 *       {@code fee_policy_key} を焼き付け（遡及防止）。初回は単発 destination charge、Subscription は次サイクル開始
 *       （案 b・PoC 実証 2026-06-05）。<b>前提として P1 の SetupIntent/PM 保存導線が必要（現状未整備・報告参照）。</b></li>
 *   <li><b>第三波（cancel/skip/resume）:</b> {@link MembershipSubscriptionEntity} の状態遷移メソッド
 *       （{@code scheduleCancelAtPeriodEnd}/{@code applySkipUntil}/{@code clearSkip}）＋ Stripe
 *       {@code pause_collection} 連携。所有権認可（payer_user_id / 後見保護者）。</li>
 *   <li><b>第四波（Webhook）:</b> {@code invoice.created} の固定手数料上書き（焼付 fee_policy_key で算出）・
 *       {@code invoice.paid} の escrow CAPTURED 起票＋valid_until 延長・{@code invoice.payment_failed} の
 *       PAST_DUE 遷移（{@code markPastDue}/{@code markRecovered}/{@code markCancelled} を使用）。</li>
 * </ul>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §4 / 01_data_model.md §2.1</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MembershipSubscriptionService {

    private final MembershipSubscriptionRepository membershipSubscriptionRepository;

    /**
     * 払い手視点の継続課金一覧を取得する（「自分が払い手の継続課金一覧」API の本体・02_api §4.1）。
     *
     * @param payerUserId 払い手ユーザーID（呼出側で SecurityUtils 解決）
     * @return 作成日時降順の継続課金一覧（論理削除を除外）
     */
    @Transactional(readOnly = true)
    public List<MembershipSubscriptionEntity> findForPayer(Long payerUserId) {
        return membershipSubscriptionRepository
                .findByPayerUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(payerUserId);
    }

    /**
     * 払い手視点の継続課金一覧を状態で絞って取得する。
     *
     * @param payerUserId 払い手ユーザーID
     * @param statuses    抽出する状態（例: ACTIVE/PAST_DUE）
     * @return 作成日時降順の継続課金一覧（論理削除を除外）
     */
    @Transactional(readOnly = true)
    public List<MembershipSubscriptionEntity> findForPayer(
            Long payerUserId, List<MembershipSubscriptionStatus> statuses) {
        return membershipSubscriptionRepository
                .findByPayerUserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(payerUserId, statuses);
    }

    /**
     * 受領主体（チーム/組織）視点の継続課金一覧を取得する（管理者向け一覧 API の本体・02_api §4.1）。
     *
     * @param scopeKind 受領主体の種別（TEAM/ORG）
     * @param scopeId   受領主体 ID（team_id/org_id）
     * @return 作成日時降順の継続課金一覧（論理削除を除外）
     */
    @Transactional(readOnly = true)
    public List<MembershipSubscriptionEntity> findForScope(ScopeKind scopeKind, Long scopeId) {
        return membershipSubscriptionRepository
                .findByScopeKindAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(scopeKind, scopeId);
    }

    /**
     * チーム視点の継続課金一覧を取得する（{@link #findForScope} の TEAM 限定の薄いラッパ・02_api §4.1
     * {@code GET /api/v1/teams/{id}/membership-subscriptions}）。
     *
     * @param teamId チーム ID
     * @return 作成日時降順の継続課金一覧（論理削除を除外）
     */
    @Transactional(readOnly = true)
    public List<MembershipSubscriptionEntity> findForTeam(Long teamId) {
        return findForScope(ScopeKind.TEAM, teamId);
    }
}
