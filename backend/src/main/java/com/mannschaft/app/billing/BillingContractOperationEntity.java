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
 * Billing Center PR6a: cancel/resume/change/migration 等の契約操作 Saga（{@code billing_contract_operations}）。
 *
 * <p>V196 で新設。{@code billing_contracts} に対する非同期・冪等な操作（Stripe 呼出を伴う）を
 * 1行として起票し、{@link BillingOperationStatus} の状態機械で進行を管理する。詳細行
 * （{@code billing_contract_changes} / {@code billing_membership_price_adjustments} /
 * {@code billing_customer_migrations} / {@code billing_invoice_adjustments}）は
 * {@code operation_id} でこの行を参照する（設計書: docs/features/F20.1_entitlement_billing/05_billing_center.md §5）。</p>
 *
 * <p><b>{@link ActiveContractPointerEntity}（{@code active_contract_pointers}）とは別表</b>である。
 * あちらは PLAN/ADDON スロットの一意性を担保する既存表で、本 Entity（契約操作 Saga）とは無関係。
 * 名前が似ているため混同注意（{@link ActiveBillingContractOperationPointerEntity} が本 Entity と
 * 対になる pointer 表）。</p>
 *
 * <p><b>Repository</b>: {@code organization_id} NULL 許容＋{@code deleted_at} 保持のため
 * {@link com.mannschaft.app.common.repository.AbstractTenantAwareRepository} を継承する
 * （{@link BillingContractEntity} 前例）。</p>
 *
 * <p>このフェーズでは Entity/Repo 骨格のみ（遷移ガード・Service/Controller は別部隊）。</p>
 *
 * <p>設計書: docs/features/F20.1_entitlement_billing/05_billing_center.md §5</p>
 */
@Entity
@Table(name = "billing_contract_operations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bco_idempotency", columnNames = {"contract_id", "idempotency_key"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BillingContractOperationEntity extends UuidV7Entity {

    /** 操作対象契約（{@code billing_contracts.id}・同一ドメイン内 FK）。 */
    @Column(name = "contract_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID contractId;

    /** 操作対象契約が属する Stripe Customer（{@code billing_customers.id}・同一ドメイン内 FK）。 */
    @Column(name = "billing_customer_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID billingCustomerId;

    /** テナント（organization_id NULL 許容。USER スコープ契約の操作は NULL）。 */
    @Column(name = "organization_id")
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private BillingOperationKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private BillingOperationStatus status;

    /** kind ごとの遷移局面（{@link BillingOperationStep} 参照。DDL側にCHECKは無い）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "step", nullable = false, length = 32)
    private BillingOperationStep step;

    /** 呼出側が発行する冪等キー（{@code uk_bco_idempotency} により contract_id 単位で一意）。 */
    @Column(name = "idempotency_key", nullable = false, length = 36)
    private String idempotencyKey;

    /** 冪等キー使い回し時の body 相違検出用ハッシュ（SHA-256 hex, 64桁）。 */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    /** Stripe Subscription ID（{@code sub_xxx}・論理参照。呼出後に確定）。 */
    @Column(name = "stripe_subscription_ref", length = 255)
    private String stripeSubscriptionRef;

    /** Stripe Subscription Schedule ID（{@code sub_sched_xxx}・論理参照）。 */
    @Column(name = "stripe_schedule_ref", length = 255)
    private String stripeScheduleRef;

    /**
     * 操作の効力発生日時（kind により意味が異なる。例: CANCEL/DOWNGRADE_TO_CANCEL の期末解約時刻）。
     *
     * <p>Stripe の {@code current_period_end} 由来の<b>瞬間</b>であり、土地の約束（営業時間・締切）では
     * ないため {@link Instant} で持つ（{@code docs/architecture/datetime_policy_utc_instant_vs_wallclock.md}
     * §1・§4）。{@code billing_contracts.current_period_end}（ゾーンを持たない日時型のまま・CMP-023 の返済対象）と
     * 突き合わせる箇所では、注入 {@link java.time.Clock} のゾーンで明示的に変換すること。</p>
     */
    @Column(name = "effective_at")
    private Instant effectiveAt;

    /** FAILED 確定時のエラーコード（{@code ErrorCode} 相当の文字列）。 */
    @Column(name = "error_code", length = 64)
    private String errorCode;

    /** CAS 用 version（Hibernate の暗黙 {@code @Version} にはしない。{@link BillingContractEntity} 前例）。 */
    @Column(name = "version", nullable = false)
    private Long version;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_kind", nullable = false, length = 8)
    private BillingOperationActorKind actorKind;

    /** 操作を起票したユーザー（{@code actor_kind=USER} のとき必須・{@code actor_kind=SYSTEM} のとき NULL）。 */
    @Column(name = "created_by")
    private Long createdBy;

    /**
     * 起票した瞬間（{@code docs/architecture/datetime_policy_utc_instant_vs_wallclock.md} §1・§4）。
     * DB 列は {@code DATETIME(6)} で、格納基準は全プロファイル共通の
     * {@code hibernate.jdbc.time_zone=UTC}（番人 {@code TimeZoneStorageBasisGuardTest} が固定）。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 最後に状態が動いた瞬間（同上）。停止窓の回収（D8）の stale 判定はこの値と
     * {@code Instant.now(clock)} の差で行うため、瞬間同士の自己完結した比較になる。
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 論理削除（操作履歴は原則物理削除しない）。削除した瞬間であり {@link Instant}。 */
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
        if (this.status == null) {
            this.status = BillingOperationStatus.CREATED;
        }
        if (this.step == null) {
            this.step = BillingOperationStep.RECEIVED;
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
