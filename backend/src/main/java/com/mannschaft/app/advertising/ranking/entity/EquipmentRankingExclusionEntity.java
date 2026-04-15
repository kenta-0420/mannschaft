package com.mannschaft.app.advertising.ranking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 備品ランキング除外設定エンティティ。
 * チームのオプトアウトまたは特定備品名の除外設定を管理する。
 */
@Entity
@Table(name = "equipment_ranking_exclusions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class EquipmentRankingExclusionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 除外種別 */
    @Enumerated(EnumType.STRING)
    @Column(name = "exclusion_type", nullable = false, length = 20)
    private ExclusionType exclusionType;

    /** オプトアウト対象チームID（TEAM_OPT_OUT のみ） */
    @Column(name = "team_id")
    private Long teamId;

    /** 除外対象の正規化済み備品名（ITEM_EXCLUSION のみ） */
    @Column(name = "normalized_name", length = 200)
    private String normalizedName;

    /** 除外理由 */
    @Column(name = "reason", length = 300)
    private String reason;

    /** 除外操作を行ったユーザーID */
    @Column(name = "excluded_by_user_id")
    private Long excludedByUserId;

    /** 作成日時 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
