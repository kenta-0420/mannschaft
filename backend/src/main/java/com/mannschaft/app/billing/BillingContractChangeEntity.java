package com.mannschaft.app.billing;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * Billing Center PR6b-1: PLAN 変更 Saga（{@code billing_contract_changes}）の詳細行。
 *
 * <p>V196 で新設。{@code billing_contract_operations}（kind=PLAN_CHANGE）の詳細として
 * {@code operation_id} で 1:1 に対応する（{@code uk_bcc_operation}）。UPGRADE（即時反映・本 PR）と
 * DOWNGRADE（期末反映・PR6b-2）の両方をこの1表で扱う（設計書:
 * docs/features/F20.1_entitlement_billing/05_billing_center.md §5）。</p>
 *
 * <p>このフェーズでは Entity/Repo 骨格のみ（遷移ガード・Service/Controller は別部隊）。</p>
 *
 * <p>設計書: docs/features/F20.1_entitlement_billing/05_billing_center.md §5</p>
 */
@Entity
@Table(name = "billing_contract_changes",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_bcc_operation", columnNames = {"operation_id"}),
                @UniqueConstraint(name = "uk_bcc_idempotency", columnNames = {"contract_id", "idempotency_key"}),
                // 【必須・AC-41】uk_bcc_invoice は V196 の DDL には在るのに、ここ（Entity）に無かった。
                // 結合テストのスキーマは Flyway ではなく Hibernate の ddl-auto:create が生成する
                // （application-test.yml）ため、この宣言漏れは「本番にはある一意制約が、試練の DB にだけ
                // 存在しない」という乖離になる。AC-41 は「同じ invoice ref を別契約が既に握っている状態で
                // 確定を試みると uk_bcc_invoice で落ち、確定が丸ごと巻き戻る」ことを測る検体だが、
                // 制約が無いぶん bind が素通りし、change だけが APPLIED でコミットされていた。
                @UniqueConstraint(name = "uk_bcc_invoice", columnNames = {"stripe_invoice_ref"}),
                @UniqueConstraint(name = "uk_bcc_schedule", columnNames = {"stripe_schedule_ref"})
        })
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BillingContractChangeEntity extends UuidV7Entity {

    /** 1:1 に対応する契約操作 Saga（{@code billing_contract_operations.id}）。 */
    @Column(name = "operation_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID operationId;

    /** 変更対象契約（{@code billing_contracts.id}・同一ドメイン内 FK）。 */
    @Column(name = "contract_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID contractId;

    /** 変更対象契約が属する Stripe Customer（{@code billing_customers.id}・同一ドメイン内 FK）。 */
    @Column(name = "billing_customer_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID billingCustomerId;

    /** テナント（organization_id NULL 許容。USER スコープ契約の変更は NULL）。 */
    @Column(name = "organization_id")
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private BillingContractChangeKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private BillingContractChangeStatus status;

    /** 変更前 PLAN の product_key。 */
    @Column(name = "from_plan_key", nullable = false, length = 64)
    private String fromPlanKey;

    /** 変更後 PLAN の product_key。 */
    @Column(name = "to_plan_key", nullable = false, length = 64)
    private String toPlanKey;

    /** 変更前の価格band（{@code billing_price_band_versions.id}・同一ドメイン内 FK）。 */
    @Column(name = "from_price_band_version_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID fromPriceBandVersionId;

    /** 変更後の価格band（{@code billing_price_band_versions.id}・同一ドメイン内 FK）。 */
    @Column(name = "to_price_band_version_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID toPriceBandVersionId;

    /** 変更前の税込金額（最小貨幣単位）。 */
    @Column(name = "from_amount_including_tax", nullable = false)
    private Long fromAmountIncludingTax;

    /** 変更後の税込金額（最小貨幣単位）。 */
    @Column(name = "to_amount_including_tax", nullable = false)
    private Long toAmountIncludingTax;

    /** Stripe Invoice ID（{@code in_xxx}・論理参照。UPGRADE の決済確定後に確定）。 */
    @Column(name = "stripe_invoice_ref", length = 255)
    private String stripeInvoiceRef;

    /** Stripe Subscription ID（{@code sub_xxx}・論理参照）。 */
    @Column(name = "stripe_subscription_ref", length = 255)
    private String stripeSubscriptionRef;

    /** REQUIRES_ACTION（3DS等）の追加認証待ちの期限。期限切れは FAILED へ確定させる。 */
    @Column(name = "pending_update_expires_at")
    private Instant pendingUpdateExpiresAt;

    /** REQUIRES_ACTION 確定後に適用する変更内容のスナップショット（JSON）。 */
    @Column(name = "pending_update_target_snapshot", columnDefinition = "JSON")
    private String pendingUpdateTargetSnapshot;

    /**
     * Stripe Subscription Schedule ID（{@code sub_sched_xxx}・論理参照）。DOWNGRADE 専用
     * （PR6b-2 の担当）で、UPGRADE では常に NULL。
     *
     * <p><b>{@code chk_bcc_refs} の条件（V196 正本 DDL より引用）</b>:</p>
     * <pre>{@code
     * CONSTRAINT chk_bcc_refs CHECK (
     *     (kind = 'UPGRADE' AND stripe_schedule_ref IS NULL)
     *     OR (kind = 'DOWNGRADE' AND status = 'CREATING_SCHEDULE' AND stripe_schedule_ref IS NULL)
     *     OR (kind = 'DOWNGRADE' AND status IN ('SCHEDULED','APPLIED') AND stripe_schedule_ref IS NOT NULL)
     *     OR (kind = 'DOWNGRADE' AND status IN ('FAILED','CANCELLED'))
     * )
     * }</pre>
     * <p>つまり: (1) UPGRADE は kind 全体を通して常に NULL。(2) DOWNGRADE は
     * CREATING_SCHEDULE（Schedule 作成中でまだ ref が確定していない）の間だけ NULL 必須。
     * (3) DOWNGRADE の SCHEDULED/APPLIED は ref が確定済みで NOT NULL 必須。
     * (4) DOWNGRADE の FAILED/CANCELLED は NULL/NOT NULL どちらも許容（Schedule 作成前に
     * 失敗/取消した場合は NULL、作成後の失敗/取消は NOT NULL のまま残る）。PR6b-2 の downgrade
     * 実装（PENDING_PAYMENT/REQUIRES_ACTION から CREATING_SCHEDULE への遷移ガード）はこの4分岐に
     * 正確に乗せること。</p>
     */
    @Column(name = "stripe_schedule_ref", length = 255)
    private String stripeScheduleRef;

    /**
     * 変更の効力発生日時（UPGRADE は即時反映の瞬間、DOWNGRADE は期末反映予定の瞬間）。
     * Stripe 由来の<b>瞬間</b>であり {@link Instant} で持つ
     * （{@code docs/architecture/datetime_policy_utc_instant_vs_wallclock.md} §1・§4）。
     */
    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    /** PENDING_PAYMENT/REQUIRES_ACTION の期限切れ判定に使う瞬間（NULL 許容）。 */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * 呼出側が発行する冪等キー（{@code uk_bcc_idempotency} により contract_id 単位で一意）。
     * <b>operationId（UUID 36文字）を格納する</b>。任意長の HTTP ヘッダ値をそのまま入れない
     * （DB 列は {@code CHAR(36)} 固定長で、それ以外の長さの値は他の制約・比較を壊す）。
     */
    @Column(name = "idempotency_key", nullable = false, length = 36)
    private String idempotencyKey;

    /** 冪等キー使い回し時の body 相違検出用ハッシュ（SHA-256 hex, 64桁）。 */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    /** CAS 用 version（Hibernate の暗黙 {@code @Version} にはしない。{@link BillingContractOperationEntity} 前例）。 */
    @Column(name = "version", nullable = false)
    private Long version;

    /** 変更を起票したユーザー（{@code billing_contract_operations} と異なり SYSTEM 起票は無い）。 */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /**
     * 起票した瞬間（{@code docs/architecture/datetime_policy_utc_instant_vs_wallclock.md} §1・§4）。
     * DB 列は {@code DATETIME(6)} で、格納基準は全プロファイル共通の
     * {@code hibernate.jdbc.time_zone=UTC}（番人 {@code TimeZoneStorageBasisGuardTest} が固定）。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 最後に状態が動いた瞬間（同上）。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 論理削除（変更履歴は原則物理削除しない）。削除した瞬間であり {@link Instant}。 */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.version == null) {
            this.version = 0L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
