package com.mannschaft.app.payment.event;

import com.mannschaft.app.payment.connect.ScopeKind;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 柱③-B（CMP-260901-1538）PR-3: 払い手（payer）の退会に伴い継続課金を期末解約したことを
 * <b>受益者</b>へ知らせる通知の発火イベント（設計書 {@code billing_payer_handover_design.md} §6・AC-13）。
 *
 * <p>{@code MembershipSubscriptionService#cancelAllForPayerOnWithdrawal} は業務トランザクション
 * （{@code cancel_at_period_end=true} の反映）の内側で本イベントを publish するだけに留め、受信者ロケール解決・
 * 文面組み立て・配送は {@link MembershipPayerWithdrawalNotificationListener}（{@code AFTER_COMMIT}）が行う。
 * 業務 tx 内で通知配送を行うと通知側の DB 例外が rollback-only を立て、<b>期末解約の反映ごと巻き戻る</b>
 * （金型: {@link PaymentAdvanceSettledNotificationListener} の是正）。</p>
 *
 * <p><b>受益者自身が新 payer になる導線は本設計のスコープ外</b>（設計書 §6・§9）であり、本通知は
 * 「あなたのメンバーシップは payer の退会に伴い期末で終了します」という予告のみを担う。</p>
 *
 * @param subscriptionId     継続課金 ID（ログの相関キー・遷移先 URL の構成要素）
 * @param beneficiaryUserId  受益者（通知の宛先）
 * @param scopeKind          受領主体の種別（TEAM/ORG・通知スコープの決定に使う）
 * @param scopeId            受領主体 ID（teams.id / organizations.id）
 * @param currentPeriodEnd   期末日（「○月○日まで利用できます」の本文引数・未確定なら null）
 * @param payerUserId        退会する払い手のユーザー ID（{@code actorId}）
 */
public record MembershipPayerWithdrawalNotificationEvent(
        UUID subscriptionId,
        Long beneficiaryUserId,
        ScopeKind scopeKind,
        Long scopeId,
        LocalDate currentPeriodEnd,
        Long payerUserId) {
}
