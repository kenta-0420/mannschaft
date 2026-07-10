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
 * F20.1 課金・エンタイトルメント基盤: アクティブ契約ポインタ（{@code active_contract_pointers}）。
 *
 * <p>「アクティブな PLAN 契約は 1 スコープ 1 本」「アクティブな ADDON は 1 スコープ×1 feature_key」を
 * {@code uk_acp_slot} の DB UNIQUE で物理担保する（H-1）。履歴（何度契約/解約したか）は
 * {@link BillingContractEntity} に残し、本表は「現在アクティブなポインタ」だけを持つ
 * （設計書 01 §3.1.1）。</p>
 *
 * <p><b>⚠️ 論理削除（deleted_at）規約の意図的な例外</b>: 本 Entity は {@code deleted_at} を持たない。
 * 解約時に {@code uk_acp_slot} スロットを解放して再契約を可能にするには行を物理 DELETE する必要があり、
 * 論理削除で残すと UNIQUE が効き続け再契約が誤って {@code ENTITLEMENT_006}(409) で弾かれるため
 * （設計書 01 §3.1.1 の実装トラップ注記）。</p>
 *
 * <p><b>Repository</b>: {@link com.mannschaft.app.common.repository.AbstractTenantAwareRepository}
 * は継承しない（同基底は {@code ...DeletedAtIsNull} 派生と {@code deleted_at} 列を前提とし、
 * 物理 DELETE 運用と噛み合わない）。素の {@code JpaRepository} とし、検索はスロットキーで行う。</p>
 *
 * <p>このフェーズでは Entity/Repo 骨格のみ（Service/Controller は別部隊）。</p>
 *
 * <p>設計書: docs/features/F20.1_entitlement_billing/01_data_model.md §3.1.1</p>
 */
@Entity
@Table(
        name = "active_contract_pointers",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_acp_slot",
                columnNames = {"scope_kind", "scope_id", "contract_kind", "addon_feature_key"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ActiveContractPointerEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 8)
    private EntitlementScopeKind scopeKind;

    /** 論理参照（users.id / teams.id / organizations.id）。 */
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_kind", nullable = false, length = 8)
    private ContractKind contractKind;

    /**
     * ADDON のとき対象 feature_key。PLAN のとき空文字（UNIQUE を1本化するため NULL でなく
     * {@code ""} 固定・設計書 01 §3.1.1）。
     */
    @Column(name = "addon_feature_key", nullable = false, length = 64)
    private String addonFeatureKey;

    /** 現在アクティブな billing_contracts.id（論理参照・切替時に UPDATE）。 */
    @Column(name = "contract_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID contractId;

    /** テナント（billing_contracts と同値・参考列。検索はスロットキーで行う）。 */
    @Column(name = "organization_id")
    private Long organizationId;

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
        if (this.addonFeatureKey == null) {
            this.addonFeatureKey = "";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
