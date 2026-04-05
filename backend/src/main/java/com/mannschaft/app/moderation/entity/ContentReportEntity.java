package com.mannschaft.app.moderation.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.moderation.ReportReason;
import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.ReportTargetType;
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
 * コンテンツ通報エンティティ。不適切なコンテンツの通報・レビュー状態を管理する。
 */
@Entity
@Table(name = "content_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ContentReportEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReportTargetType targetType;

    @Column(nullable = false)
    private Long targetId;

    @Column(nullable = false)
    private Long reportedBy;

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    private Long targetUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportReason reason;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "JSON")
    private String contentSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReportStatus status = ReportStatus.PENDING;

    private Long reviewedBy;

    private LocalDateTime reviewedAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean contentHidden = false;

    /**
     * レビューを開始する（PENDING → REVIEWING）。
     */
    public void startReview(Long reviewerId) {
        this.reviewedBy = reviewerId;
        this.reviewedAt = LocalDateTime.now();
        this.status = ReportStatus.REVIEWING;
    }

    /**
     * 通報を対応済みにする。
     */
    public void resolve(Long reviewerId) {
        this.reviewedBy = reviewerId;
        this.reviewedAt = LocalDateTime.now();
        this.status = ReportStatus.RESOLVED;
    }

    /**
     * 通報を却下する。
     */
    public void dismiss(Long reviewerId) {
        this.reviewedBy = reviewerId;
        this.reviewedAt = LocalDateTime.now();
        this.status = ReportStatus.DISMISSED;
    }

    /**
     * エスカレーションする。
     */
    public void escalate() {
        this.status = ReportStatus.ESCALATED;
    }

    /**
     * 差し戻す（DISMISSED → REVIEWING）。
     */
    public void reopen(Long reviewerId) {
        this.reviewedBy = reviewerId;
        this.reviewedAt = LocalDateTime.now();
        this.status = ReportStatus.REVIEWING;
    }

    /**
     * コンテンツを非表示にする。
     */
    public void hideContent() {
        this.contentHidden = true;
    }

    /**
     * コンテンツの非表示を解除する。
     */
    public void unhideContent() {
        this.contentHidden = false;
    }
}
