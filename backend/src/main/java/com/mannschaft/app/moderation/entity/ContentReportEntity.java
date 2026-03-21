package com.mannschaft.app.moderation.entity;

import com.mannschaft.app.moderation.ReportReason;
import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.ReportTargetType;
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
 * コンテンツ通報エンティティ。不適切なコンテンツの通報・レビュー状態を管理する。
 */
@Entity
@Table(name = "content_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ContentReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReportTargetType targetType;

    @Column(nullable = false)
    private Long targetId;

    @Column(nullable = false, length = 20)
    private String reporterType;

    @Column(nullable = false)
    private Long reporterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportReason reason;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReportStatus status = ReportStatus.PENDING;

    private Long reviewedBy;

    @Column(length = 1000)
    private String reviewNote;

    @Column(nullable = false)
    @Builder.Default
    private Boolean identityDisclosed = false;

    private LocalDateTime resolvedAt;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 通報をレビュー済みに更新する。
     *
     * @param reviewerId レビュアーのユーザーID
     * @param note       レビューメモ
     * @param newStatus  新しいステータス
     */
    public void review(Long reviewerId, String note, ReportStatus newStatus) {
        this.reviewedBy = reviewerId;
        this.reviewNote = note;
        this.status = newStatus;
        if (newStatus == ReportStatus.RESOLVED || newStatus == ReportStatus.DISMISSED) {
            this.resolvedAt = LocalDateTime.now();
        }
    }
}
