package com.mannschaft.app.admin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ベータ登録制限設定エンティティ。
 * ベータテスト期間中の新規登録を招待トークン保有者に限定する設定を管理する。
 */
@Entity
@Table(name = "beta_restriction_config")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class BetaRestrictionConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isEnabled = false;

    /** このID以下のチームが招待可能。null=制限なし */
    private Long maxTeamId;

    /** このID以下の組織が招待可能。null=制限なし */
    private Long maxOrgId;

    private Long updatedBy;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 設定を更新する。
     *
     * @param isEnabled  ベータ制限有効フラグ
     * @param maxTeamId  招待可能チームID上限（null=制限なし）
     * @param maxOrgId   招待可能組織ID上限（null=制限なし）
     * @param updatedBy  更新者ID
     */
    public void update(Boolean isEnabled, Long maxTeamId, Long maxOrgId, Long updatedBy) {
        this.isEnabled = isEnabled;
        this.maxTeamId = maxTeamId;
        this.maxOrgId = maxOrgId;
        this.updatedBy = updatedBy;
        this.updatedAt = LocalDateTime.now();
    }
}
