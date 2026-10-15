package com.mannschaft.app.payment.dto;

import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * F08.9 P5 第四波: 継続課金一覧アイテムレスポンス（設計書 02 §4.1 / 04 §2）。camelCase 1:1。
 *
 * <p>払い手向け一覧（{@code GET /api/v1/me/membership-subscriptions}）と
 * チーム管理者向け一覧（{@code GET /api/v1/teams/{id}/membership-subscriptions}）の両方で使用する。</p>
 *
 * <h3>応答項目（04 §2 UX 要点に準拠）:</h3>
 * <ul>
 *   <li>{@code itemName} — 会費項目名（payment_item 名・price-lock 加入時に固定）</li>
 *   <li>{@code beneficiaryDisplayName} — 受益者の表示名（user ドメイン Service 経由で取得）</li>
 *   <li>{@code nextBillingDate} — 次回課金日。通常=current_period_end、スキップ中=skip_until</li>
 *   <li>{@code validUntil} — 利用期限（current_period_end）</li>
 *   <li>{@code cancelAtPeriodEnd} — 期末解約予約フラグ（UI に「○月○日まで利用可」を明示）</li>
 *   <li>{@code skipUntil} — スキップ中の再開予定日（null=スキップなし）</li>
 * </ul>
 *
 * <p>PCI 禁則（client_secret 等）は含めない（設計書 03 §1）。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §4.1 / 04_ui_i18n.md §2</p>
 */
@Getter
@Builder
public class MembershipSubscriptionListItemResponse {

    private final UUID id;

    /** 会費項目 ID。 */
    private final Long paymentItemId;

    /** 会費項目名（price-lock 加入時の項目名）。 */
    private final String itemName;

    /** 受益者ユーザー ID。 */
    private final Long beneficiaryUserId;

    /**
     * 受益者の表示名（user ドメインの Service 経由で取得・Entity 直接参照禁止・02 §4.1）。
     *
     * <p>退会済み / 削除済みの場合は「退会済みユーザー」等のプレースホルダが返る（UserEntity.anonymize 済み）。</p>
     */
    private final String beneficiaryDisplayName;

    /** 払い手ユーザー ID。 */
    private final Long payerUserId;

    /** 受領主体の種別（TEAM/ORG）。 */
    private final String scopeKind;

    /** 受領主体 ID（team_id/org_id）。 */
    private final Long scopeId;

    /** 状態（PENDING/ACTIVE/PAST_DUE/CANCELLED/EXPIRED）。 */
    private final String status;

    /** 課金周期（MONTHLY/YEARLY）。 */
    private final String billingInterval;

    /** 額面（円整数・加入時に固定）。 */
    private final Integer faceAmount;

    /** 通貨（加入時に固定）。 */
    private final String currency;

    /**
     * 次回課金日。
     *
     * <p>通常（スキップなし）は {@code current_period_end}（サイクル終了=次回課金日）。
     * スキップ中（{@code skip_until} セット済）は {@code skip_until}（スキップ解除後の次サイクル開始日）。
     * {@code current_period_end} が null（PENDING 等）の場合は null。</p>
     */
    private final LocalDate nextBillingDate;

    /**
     * 利用期限（{@code current_period_end}）。
     *
     * <p>UI に「○月○日まで利用可」を明示するために使う（04 §2）。null の場合は PENDING 等で未確定。</p>
     */
    private final LocalDate validUntil;

    /** 期末解約予約フラグ（true の場合 UI に「○月○日まで利用可」と解約予告を表示）。 */
    private final Boolean cancelAtPeriodEnd;

    /** スキップ中の再開予定日（null=スキップなし）。 */
    private final LocalDate skipUntil;

    /**
     * Entity + 名前解決結果 → Response 変換。
     *
     * @param e                      Entity
     * @param itemName               会費項目名（Service 層で解決済み）
     * @param beneficiaryDisplayName 受益者の表示名（Service 層で解決済み）
     */
    public static MembershipSubscriptionListItemResponse from(
            MembershipSubscriptionEntity e, String itemName, String beneficiaryDisplayName) {

        // nextBillingDate: スキップ中=skip_until、通常=current_period_end（02 §4.3 UX）。
        LocalDate nextBillingDate = (e.getSkipUntil() != null) ? e.getSkipUntil() : e.getCurrentPeriodEnd();

        return MembershipSubscriptionListItemResponse.builder()
                .id(e.getId())
                .paymentItemId(e.getPaymentItemId())
                .itemName(itemName)
                .beneficiaryUserId(e.getBeneficiaryUserId())
                .beneficiaryDisplayName(beneficiaryDisplayName)
                .payerUserId(e.getPayerUserId())
                .scopeKind(e.getScopeKind() != null ? e.getScopeKind().name() : null)
                .scopeId(e.getScopeId())
                .status(e.getStatus() != null ? e.getStatus().name() : null)
                .billingInterval(e.getBillingInterval() != null ? e.getBillingInterval().name() : null)
                .faceAmount(e.getFaceAmount())
                .currency(e.getCurrency())
                .nextBillingDate(nextBillingDate)
                .validUntil(e.getCurrentPeriodEnd())
                .cancelAtPeriodEnd(e.getCancelAtPeriodEnd())
                .skipUntil(e.getSkipUntil())
                .build();
    }
}
