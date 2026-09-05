package com.mannschaft.app.billing;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 柱③-B 組織契約の請求担当引継（CMP-260901-1538・V203）: {@code billing_payer_handover_requests}。
 *
 * <p>{@code billing_contracts} の payer（請求担当）引継要求を管理する。クロスドメイン FK 禁止方針
 * （{@code docs/architecture/domain_db_design_principles.md} 原則1）に従い、{@code old_payer_user_id}/
 * {@code new_payer_user_id} は auth ドメインへの直接 FK を張らずインデックスのみで論理参照する。</p>
 *
 * <p>新規テーブルのため {@link UuidV7Entity}（UUIDv7 主キー）を継承する（CLAUDE.md 原則6）。
 * {@code deleted_at} を持たない append-only 履歴（終端状態は {@code open_old_contract_id} 生成列で
 * NULL 化され UNIQUE から除外される）ため {@code AbstractTenantAwareRepository} は継承しない
 * （{@link ActiveContractPointerEntity} と同型の判断・設計書 §4.2）。</p>
 *
 * <p>{@code open_old_contract_id}（生成列・DB側で自動計算）は JPA では書き込み対象にならないため
 * このエンティティにはマッピングしない（DDL 側のみで UNIQUE 制約を担保する）。</p>
 *
 * <p>設計書: {@code docs/architecture/billing_payer_handover_design.md} §4.2</p>
 */
@Entity
@Table(name = "billing_payer_handover_requests")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BillingPayerHandoverRequestEntity extends UuidV7Entity {

    /** 引継元 {@code billing_contracts.id}。 */
    @Column(name = "old_contract_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID oldContractId;

    /** 引継先 {@code billing_contracts.id}（ACCEPTED 以降で確定・PENDING_HANDOVER 状態で作成）。 */
    @Column(name = "new_contract_id", columnDefinition = "BINARY(16)")
    private UUID newContractId;

    /** TEAM または ORG のみ許容（USER は引継対象外・アプリ層で拒否）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 8)
    private EntitlementScopeKind scopeKind;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /** 退会予定・引継元の payer。 */
    @Column(name = "old_payer_user_id", nullable = false)
    private Long oldPayerUserId;

    /** 承諾した引継先 ADMIN（ACCEPTED 以降で確定）。 */
    @Column(name = "new_payer_user_id")
    private Long newPayerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PayerHandoverStatus status;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    /** 既定 requested_at + 14日。期限内未引継は期末解約へフォールバック（設計書 §5.4）。 */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** P0-2: 新サブスク作成成功時点で永続化する一次防衛の要。 */
    @Column(name = "psp_new_subscription_ref", length = 64)
    private String pspNewSubscriptionRef;

    /**
     * R4-P1-2: 旧サブスクへの {@code cancel_at_period_end=true} 設定 API が成功した時点で永続化。
     * NULLのままACCEPTED以降に残る行は設定が未完了/未確認であることを示し、夜次照合バッチの検出対象になる。
     */
    @Column(name = "old_cancel_scheduled_at")
    private LocalDateTime oldCancelScheduledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.status == null) {
            this.status = PayerHandoverStatus.REQUESTED;
        }
        if (this.requestedAt == null) {
            this.requestedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
