package com.mannschaft.app.resume.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 構造化スキルエンティティ（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §4.6
 *
 * <p>{@code resume_id} で親の {@link ResumeEntity} と紐づく。
 * 職務経歴書の「スキル一覧」表として箇条書き描画される構造化データ。
 *
 * <p>{@link ResumeEntity#getSkillsSummary()} の散文テキストとは役割が異なり、
 * 両者は互いに補完する形で併存する。
 */
@Entity
@Table(name = "resume_skills")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ResumeSkillEntity extends UuidV7Entity {

    /** スキルレベル選択肢。 */
    public enum SkillLevel {
        /** 初心者（学習中・業務経験 1 年未満程度）。 */
        BEGINNER,
        /** 中級者（業務で使用経験あり）。 */
        INTERMEDIATE,
        /** 上級者（業務でリードできるレベル）。 */
        ADVANCED,
        /** エキスパート（第一人者・社内外の専門家として認知されるレベル）。 */
        EXPERT
    }

    @Column(name = "resume_id", nullable = false)
    private UUID resumeId;

    /** スキル名（例: Java、AWS、プロジェクトマネジメント）。 */
    @Column(name = "skill_name", nullable = false, length = 100)
    private String skillName;

    /** 習熟度。任意（null = 習熟度未記入）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "level", length = 12)
    private SkillLevel level;

    /** 補足説明（例: 「Spring Boot 3.x を用いた REST API 設計・実装経験 5 年」）。 */
    @Column(name = "description", length = 500)
    private String description;

    /** 表示順。小さいほど先頭に表示される。 */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

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
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 内容を更新する。 */
    public void update(String skillName, SkillLevel level, String description, int displayOrder) {
        this.skillName = skillName;
        this.level = level;
        this.description = description;
        this.displayOrder = displayOrder;
    }

    /** 論理削除を行う。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
