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

/**
 * F20.1 課金・エンタイトルメント基盤: PLAN/ADDON 契約行（{@code billing_contracts}）。
 *
 * <p>{@code entitlements(source_kind IN ('PLAN','ADDON'))} の発行元。ベータ中は決済を伴わない
 * 契約状態のみを管理し、Phase 2 で PSP 列を Expand する（設計書 01 §3.1）。</p>
 *
 * <p>契約履歴は append-only で保持する（{@code status} を含む UNIQUE は張らない・
 * CANCELLED→再契約の履歴を壊すため）。アクティブ契約の一意性は
 * {@link ActiveContractPointerEntity}（{@code uk_acp_slot}）が DB で物理担保する（H-1・§3.1.1）。</p>
 *
 * <p><b>Repository</b>: {@code organization_id} NULL 許容＋{@code deleted_at} 保持のため
 * {@link com.mannschaft.app.common.repository.AbstractTenantAwareRepository} を継承する
 * （escrow/fee_recovery_balances 前例・設計書 01 §0）。基底の derived query が要求する
 * {@code organizationId}/{@code deletedAt} プロパティを両方持つ。</p>
 *
 * <p>このフェーズでは Entity/Repo 骨格のみ（Service/Controller は別部隊）。</p>
 *
 * <p>設計書: docs/features/F20.1_entitlement_billing/01_data_model.md §3.1</p>
 */
@Entity
@Table(name = "billing_contracts")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BillingContractEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 8)
    private EntitlementScopeKind scopeKind;

    /** users.id / teams.id / organizations.id（論理参照・FKなし）。 */
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /** テナント。ORG=scope_id 自身 / TEAM=主所属組織（無所属は NULL）/ USER=NULL。 */
    @Column(name = "organization_id")
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_kind", nullable = false, length = 8)
    private ContractKind contractKind;

    /** contract_kind=PLAN のとき必須（論理参照・plans）。 */
    @Column(name = "plan_key", length = 32)
    private String planKey;

    /** contract_kind=ADDON のとき必須（論理参照・feature_catalog）。 */
    @Column(name = "feature_key", length = 64)
    private String featureKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private ContractStatus status;

    /** 契約時アクティブ人数スナップショット（TEAM/ORG のみ・memberships left_at IS NULL 数）。 */
    @Column(name = "member_count_snapshot")
    private Integer memberCountSnapshot;

    /** 契約時に解決した plan_price_bands.band_no（TEAM/ORG の PLAN のみ）。 */
    @Column(name = "band_no_snapshot")
    private Short bandNoSnapshot;

    /**
     * 契約時単価スナップショット（円）。ベータ中=NULL（無償）。遡及防止の焼き付け
     * （F22.1 fee_policy_key と同型）。
     */
    @Column(name = "price_jpy_snapshot")
    private Integer priceJpySnapshot;

    @Column(name = "contracted_at", nullable = false)
    private LocalDateTime contractedAt;

    /** 解約日時（status=CANCELLED と同時にセット）。 */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** 契約操作者（論理参照。シスアド手動付与時はシスアドの userId）。 */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 論理削除（契約記録は原則物理削除しない）。 */
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
        if (this.status == null) {
            this.status = ContractStatus.ACTIVE;
        }
        if (this.contractedAt == null) {
            this.contractedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
