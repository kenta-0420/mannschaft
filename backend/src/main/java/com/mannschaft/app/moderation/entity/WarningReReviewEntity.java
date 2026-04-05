package com.mannschaft.app.moderation.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.moderation.ReReviewStatus;
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

import java.time.LocalDateTime;

/**
 * WARNING再レビューエンティティ。2段階再レビューフロー（ADMIN→SYSTEM_ADMIN昇格）を管理する。
 */
@Entity
@Table(name = "warning_re_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class WarningReReviewEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long reportId;

    @Column(nullable = false)
    private Long actionId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReReviewStatus status = ReReviewStatus.PENDING;

    private Long adminReviewedBy;

    @Column(columnDefinition = "TEXT")
    private String adminReviewNote;

    private LocalDateTime adminReviewedAt;

    @Column(columnDefinition = "TEXT")
    private String escalationReason;

    private Long systemAdminReviewedBy;

    @Column(columnDefinition = "TEXT")
    private String systemAdminReviewNote;

    private LocalDateTime systemAdminReviewedAt;

    /**
     * ADMINがレビューする。
     *
     * @param reviewerId レビュアーID
     * @param note       レビューメモ
     * @param newStatus  OVERTURNED/UPHELD/ESCALATED
     */
    public void adminReview(Long reviewerId, String note, ReReviewStatus newStatus) {
        this.adminReviewedBy = reviewerId;
        this.adminReviewNote = note;
        this.status = newStatus;
        this.adminReviewedAt = LocalDateTime.now();
    }

    /**
     * SYSTEM_ADMINに昇格する。
     *
     * @param reason 昇格理由
     */
    public void escalate(String reason) {
        this.escalationReason = reason;
        this.status = ReReviewStatus.ESCALATED;
    }

    /**
     * SYSTEM_ADMINが最終判定する。
     *
     * @param reviewerId レビュアーID
     * @param note       レビューメモ
     * @param newStatus  APPEAL_ACCEPTED/APPEAL_REJECTED
     */
    public void systemAdminReview(Long reviewerId, String note, ReReviewStatus newStatus) {
        this.systemAdminReviewedBy = reviewerId;
        this.systemAdminReviewNote = note;
        this.status = newStatus;
        this.systemAdminReviewedAt = LocalDateTime.now();
    }
}
