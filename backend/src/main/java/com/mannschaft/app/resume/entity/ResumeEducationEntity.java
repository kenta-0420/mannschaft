package com.mannschaft.app.resume.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 学歴エンティティ（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §4.3
 *
 * <p>{@code resume_id} で親の {@link ResumeEntity} と紐づく。
 * 1 履歴書に複数行の学歴を保持できる。
 * {@code display_order} で表示順を制御する。
 */
@Entity
@Table(name = "resume_educations")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ResumeEducationEntity extends UuidV7Entity {

    @Column(name = "resume_id", nullable = false)
    private UUID resumeId;

    /** 入学・卒業の年。 */
    @Column(name = "entry_year", nullable = false)
    private Short entryYear;

    /** 入学・卒業の月（null = 月不明）。 */
    @Column(name = "entry_month")
    private Byte entryMonth;

    /** 学校名・学部・学科等の記述（例: 「○○大学 工学部 情報工学科 卒業」）。 */
    @Column(name = "description", nullable = false, length = 255)
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
    public void update(Short entryYear, Byte entryMonth, String description, int displayOrder) {
        this.entryYear = entryYear;
        this.entryMonth = entryMonth;
        this.description = description;
        this.displayOrder = displayOrder;
    }

    /** 論理削除を行う。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
