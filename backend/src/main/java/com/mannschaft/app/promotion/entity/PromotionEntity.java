package com.mannschaft.app.promotion.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * プロモーションエンティティ。
 */
@Entity
@Table(name = "promotions")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PromotionEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    private Long createdBy;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(length = 500)
    private String imageUrl;

    private Long couponId;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = "DRAFT";

    private Long approvedBy;

    private LocalDateTime approvedAt;

    private LocalDateTime scheduledAt;

    private LocalDateTime publishedAt;

    private LocalDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private Integer targetCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer deliveredCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer openedCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer skippedCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer failedCount = 0;

    private LocalDateTime deletedAt;

    /**
     * プロモーション内容を更新する。
     */
    public void update(String title, String body, String imageUrl, Long couponId, LocalDateTime expiresAt) {
        this.title = title;
        this.body = body;
        this.imageUrl = imageUrl;
        this.couponId = couponId;
        this.expiresAt = expiresAt;
    }

    /**
     * 編集可能かどうかを判定する。
     */
    public boolean isEditable() {
        return "DRAFT".equals(this.status) || "PENDING_APPROVAL".equals(this.status);
    }

    /**
     * 承認する。
     */
    public void approve(Long approverId) {
        this.status = "APPROVED";
        this.approvedBy = approverId;
        this.approvedAt = LocalDateTime.now();
    }

    /**
     * 即時配信する。
     */
    public void publish(int targetCount) {
        this.status = "PUBLISHING";
        this.targetCount = targetCount;
        this.publishedAt = LocalDateTime.now();
    }

    /**
     * 予約配信を設定する。
     */
    public void schedule(LocalDateTime scheduledAt, int targetCount) {
        this.status = "SCHEDULED";
        this.scheduledAt = scheduledAt;
        this.targetCount = targetCount;
    }

    /**
     * キャンセルする。
     */
    public void cancel() {
        this.status = "CANCELLED";
    }

    /**
     * 配信完了にする。
     */
    public void markPublished() {
        this.status = "PUBLISHED";
    }

    /**
     * 配信失敗にする。
     */
    public void markFailed() {
        this.status = "FAILED";
    }

    /**
     * 承認待ちにする。
     */
    public void submitForApproval() {
        this.status = "PENDING_APPROVAL";
    }

    /**
     * 配信可能かどうかを判定する。
     */
    public boolean isPublishable() {
        return "APPROVED".equals(this.status);
    }

    /**
     * 承認可能かどうかを判定する。
     */
    public boolean isApprovable() {
        return "PENDING_APPROVAL".equals(this.status);
    }

    /**
     * キャンセル可能かどうかを判定する。
     */
    public boolean isCancellable() {
        return "SCHEDULED".equals(this.status) || "APPROVED".equals(this.status);
    }

    /**
     * 配信カウンターを更新する。
     */
    public void updateDeliveryCounters(int delivered, int opened, int skipped, int failed) {
        this.deliveredCount = delivered;
        this.openedCount = opened;
        this.skippedCount = skipped;
        this.failedCount = failed;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
