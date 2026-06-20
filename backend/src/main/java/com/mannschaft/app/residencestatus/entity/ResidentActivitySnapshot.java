package com.mannschaft.app.residencestatus.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * F09.16 居住者アクティビティ日次スナップショット。
 *
 * <p>ResidentActivityAggregator バッチが日次で各居住者のアプリ内アクティビティ
 * （投稿/チャット既読/お知らせ既読/安否確認回答/ログイン/フォーム回答）を集計し、
 * 当日合計重み付きスコアと内訳 JSON を保存する。30 日ローテで物理削除される。</p>
 *
 * <p>クロスドメイン参照（dwelling_unit_id / resident_registry_id / subject_user_id）は
 * すべて INDEX のみで FK なし（CLAUDE.md DB設計原則 1 準拠）。</p>
 */
@Entity
@Table(name = "resident_activity_snapshots")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ResidentActivitySnapshot extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** F09.1 dwelling_units.id（クロスドメイン弱参照・FK なし） */
    @Column(name = "dwelling_unit_id", nullable = false)
    private Long dwellingUnitId;

    /** F09.1 resident_registry.id（クロスドメイン弱参照・FK なし） */
    @Column(name = "resident_registry_id", nullable = false)
    private Long residentRegistryId;

    /** 集計対象ユーザー（クロスドメイン弱参照・FK なし） */
    @Column(name = "subject_user_id", nullable = false)
    private Long subjectUserId;

    /** 集計対象日（日次） */
    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    /** 当日合計重みスコア */
    @Column(name = "activity_score_total", nullable = false)
    private Integer activityScoreTotal;

    /** 各 activity_kind の発生回数 JSON */
    @Column(name = "activity_breakdown_json", columnDefinition = "JSON")
    private String activityBreakdownJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.activityScoreTotal == null) {
            this.activityScoreTotal = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
