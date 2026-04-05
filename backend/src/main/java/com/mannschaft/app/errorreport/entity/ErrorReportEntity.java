package com.mannschaft.app.errorreport.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.gdpr.PersonalData;
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
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * F12.5 エラーレポートエンティティ。
 * フロントエンドから送信されたエラー情報を保持する。
 */
@PersonalData(category = "error_reports")
@Entity
@Table(name = "error_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ErrorReportEntity extends BaseEntity {

    @Column(nullable = false, length = 1000)
    private String errorMessage;

    @Column(length = 2000)
    private String stackTrace;

    @Column(nullable = false, length = 2048)
    private String pageUrl;

    @Column(length = 500)
    private String userAgent;

    @Column(length = 1000)
    private String userComment;

    private Long userId;

    private Long organizationId;

    @Column(length = 36)
    private String requestId;

    @Column(length = 45)
    private String ipAddress;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ErrorReportStatus status;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ErrorReportSeverity severity;

    private Long resolvedBy;

    private LocalDateTime resolvedAt;

    @Setter
    @Column(length = 2000)
    private String adminNote;

    @Setter
    @Column(length = 1000)
    private String latestUserComment;

    @Column(nullable = false, length = 64)
    private String errorHash;

    @Column(nullable = false)
    @Builder.Default
    private Integer occurrenceCount = 1;

    @Setter
    @Column(nullable = false)
    @Builder.Default
    private Integer affectedUserCount = 1;

    @Column(nullable = false)
    private LocalDateTime firstOccurredAt;

    @Column(nullable = false)
    private LocalDateTime lastOccurredAt;

    /**
     * エラーレポートを再発（REOPENED）状態にする。
     * RESOLVED から同一 error_hash で再度報告された場合に使用する。
     *
     * @param occurredAt エラー発生日時
     */
    public void reopen(LocalDateTime occurredAt) {
        this.status = ErrorReportStatus.REOPENED;
        this.lastOccurredAt = occurredAt;
        this.occurrenceCount = this.occurrenceCount + 1;
    }

    /**
     * エラーレポートを解決済みにする。
     *
     * @param adminId 対応した管理者のユーザーID
     */
    public void resolve(Long adminId) {
        this.status = ErrorReportStatus.RESOLVED;
        this.resolvedBy = adminId;
        this.resolvedAt = LocalDateTime.now();
    }
}
