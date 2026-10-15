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

import java.time.LocalDateTime;

/**
 * F09.16 年次居住実態更新キャンペーン。
 *
 * <p>理事長が年に 1 度発動するキャンペーン。対象区分所有者全員に通知が送信され、
 * 居住実態（自ら居住/賃貸/長期不在）・連絡先最新性確認・緊急連絡先最新性確認を回答してもらう。</p>
 *
 * <p>1 組織 1 年度 1 キャンペーン（{@code uq_ar_org_year}）。</p>
 */
@Entity
@Table(name = "annual_reviews")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class AnnualReview extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** 対象年度（西暦） */
    @Column(name = "review_year", nullable = false)
    private Integer reviewYear;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "deadline_at", nullable = false)
    private LocalDateTime deadlineAt;

    /** 締切後のクローズ日時 */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** 対象者数（スナップショット） */
    @Column(name = "target_count", nullable = false)
    private Integer targetCount;

    /** 回答済み件数（denormalize） */
    @Column(name = "response_count", nullable = false)
    private Integer responseCount;

    /** 起票理事長（user_id・クロスドメイン弱参照・FK なし） */
    @Column(name = "created_by")
    private Long createdBy;

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
        if (this.targetCount == null) {
            this.targetCount = 0;
        }
        if (this.responseCount == null) {
            this.responseCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /** 手動クローズ。 */
    public void close() {
        this.closedAt = LocalDateTime.now();
    }
}
