package com.mannschaft.app.payment;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * F22.1 市（Market）統一決済 R1: 手数料パターンの割当（{@code fee_policy_assignments}）。
 *
 * <p>{@code source_kind}（＋任意 {@code sub_key}）に対しどの {@link FeePolicyEntity} を適用するかの割当。
 * テナント横断の運用データで行が増えるため <b>UUIDv7</b>（{@link UuidV7Entity} 継承・設計書 01 §3.7・原則6）。</p>
 *
 * <p>解決順序（{@link FeePolicyResolver}・設計書 02 §3.5.1）: ① {@code (source_kind, sub_key)} 完全一致 →
 * ② {@code (source_kind, sub_key IS NULL)} 既定 → ③ {@code DEFAULT}。{@code enabled=TRUE}・{@code deleted_at IS NULL}
 * かつ参照先 {@code fee_policies.enabled=TRUE} を満たすものに限る。{@code policy_key} は論理参照（FK なし）。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/01_data_model.md §3.7</p>
 */
@Entity
@Table(name = "fee_policy_assignments")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class FeePolicyAssignmentEntity extends UuidV7Entity {

    /** 解決キー（{@code RECRUITMENT}/{@code MEMBERSHIP}/{@code TOURNAMENT}/{@code JOBMATCHING}/{@code FLEAMARKET}）。 */
    @Column(name = "source_kind", nullable = false, length = 12)
    private String sourceKind;

    /** 任意の細分キー（助っ人＝{@code recruitment_category} 値 等）。NULL＝source_kind の既定割当。 */
    @Column(name = "sub_key", length = 40)
    private String subKey;

    /** 適用する {@code fee_policies.policy_key}（論理参照）。 */
    @Column(name = "policy_key", nullable = false, length = 40)
    private String policyKey;

    /** 割当の有効/無効。 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 論理削除。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.enabled == null) {
            this.enabled = Boolean.TRUE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
