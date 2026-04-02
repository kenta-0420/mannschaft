package com.mannschaft.app.gdpr.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * データエクスポートエンティティ。
 * GDPRデータポータビリティ要求に基づくエクスポートジョブを管理する。
 */
@Entity
@Table(name = "data_exports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class DataExportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(length = 500)
    private String categories;

    @Column(nullable = false)
    @Builder.Default
    private Integer progressPercent = 0;

    @Column(length = 50)
    private String currentStep;

    @Column(length = 500)
    private String s3Key;

    private Long fileSizeBytes;

    @Column(length = 200)
    private String zipPasswordHash;

    private LocalDateTime expiresAt;

    @Column(length = 500)
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * ステータスをPROCESSINGに遷移する。
     */
    public void markProcessing() {
        this.status = "PROCESSING";
        this.progressPercent = 0;
        this.currentStep = null;
    }

    /**
     * ステータスをCOMPLETEDに遷移し、S3情報を設定する。
     */
    public void markCompleted(String s3Key, long fileSizeBytes, String zipPasswordHash, LocalDateTime expiresAt) {
        this.status = "COMPLETED";
        this.s3Key = s3Key;
        this.fileSizeBytes = fileSizeBytes;
        this.zipPasswordHash = zipPasswordHash;
        this.expiresAt = expiresAt;
        this.progressPercent = 100;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * ステータスをFAILEDに遷移し、エラーメッセージを設定する。
     */
    public void markFailed(String errorMessage) {
        this.status = "FAILED";
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * S3キーをクリアする（期限切れファイルの削除後）。
     */
    public void clearS3Key() {
        this.s3Key = null;
    }

    /**
     * 進捗を更新する。
     */
    public void updateProgress(int percent, String step) {
        this.progressPercent = percent;
        this.currentStep = step;
    }
}
