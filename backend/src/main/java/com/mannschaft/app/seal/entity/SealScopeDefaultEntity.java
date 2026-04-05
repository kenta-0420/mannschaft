package com.mannschaft.app.seal.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.seal.SealScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 印鑑スコープデフォルトエンティティ。スコープ（全体/チーム/組織）ごとのデフォルト印鑑を管理する。
 */
@Entity
@Table(name = "seal_scope_defaults")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SealScopeDefaultEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SealScopeType scopeType = SealScopeType.DEFAULT;

    private Long scopeId;

    @Column(nullable = false)
    private Long sealId;

    /**
     * デフォルト印鑑を変更する。
     *
     * @param newSealId 新しい印鑑ID
     */
    public void changeSeal(Long newSealId) {
        this.sealId = newSealId;
    }
}
