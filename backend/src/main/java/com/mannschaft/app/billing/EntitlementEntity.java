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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F20.1 課金・エンタイトルメント基盤: 権利の真実源（{@code entitlements}）。
 *
 * <p>1 行 = 1 スコープ × 1 機能 × 1 発行元の権利。判定式（正準）は以下（設計書 01 §3.3）:</p>
 * <pre>{@code
 * scope_kind = :scopeKind AND scope_id = :scopeId AND feature_key = :featureKey
 *   AND revoked_at IS NULL
 *   AND valid_from <= :now
 *   AND (valid_until IS NULL OR :now < valid_until)   -- 半開区間 [from, until)
 * }</pre>
 *
 * <p>行は UPDATE で復活させない（取消の取り消し・期間延長は新しい行の発行で表現・append-only）。
 * {@code deleted_at} は業務上使わない（無効化は常に {@code revoked_at}）。
 * {@link com.mannschaft.app.common.repository.AbstractTenantAwareRepository} が要求する
 * {@code ...DeletedAtIsNull} 派生クエリのために列だけ保持する（{@code fee_recovery_balances} 前例）。</p>
 *
 * <p><b>Repository</b>: {@code organization_id} NULL 許容のため
 * {@link com.mannschaft.app.common.repository.AbstractTenantAwareRepository} を継承する
 * （escrow 前例・設計書 01 §0 / §3.2）。</p>
 *
 * <p>このフェーズでは Entity/Repo 骨格のみ（Service/Controller は別部隊）。</p>
 *
 * <p>設計書: docs/features/F20.1_entitlement_billing/01_data_model.md §3.2</p>
 */
@Entity
@Table(
        name = "entitlements",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ent_grant",
                columnNames = {"scope_kind", "scope_id", "feature_key", "source_kind", "source_ref_id", "valid_from"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class EntitlementEntity extends UuidV7Entity {

    /** USER / TEAM / ORG（payment.connect.ScopeKind と同値）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 8)
    private EntitlementScopeKind scopeKind;

    /** users.id / teams.id / organizations.id（論理参照・FKなし・INDEX）。 */
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /** feature_catalog.feature_key（論理参照）。 */
    @Column(name = "feature_key", nullable = false, length = 64)
    private String featureKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 12)
    private EntitlementSourceKind sourceKind;

    /** 発行元行: PLAN/ADDON=billing_contracts.id / BETA_GRANT=beta_grants.id（論理参照）。 */
    @Column(name = "source_ref_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID sourceRefId;

    /** 有効開始（含む）。 */
    @Column(name = "valid_from", nullable = false)
    private LocalDateTime validFrom;

    /** 有効終了（含まない・半開区間）。NULL=無期限。 */
    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    /** 取消日時。NOT NULL なら期間内でも無効。 */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** 取消操作者（論理参照。システム自動取消は NULL）。 */
    @Column(name = "revoked_by")
    private Long revokedBy;

    /** テナント。ORG=scope_id / TEAM=主所属組織（無所属 NULL）/ USER=NULL。 */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 論理削除（通常運用では使わない。業務上の無効化は revoked_at。基底要求の保持列）。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.validFrom == null) {
            this.validFrom = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
