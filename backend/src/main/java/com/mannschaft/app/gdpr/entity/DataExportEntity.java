package com.mannschaft.app.gdpr.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * GDPRデータエクスポートエンティティ。エクスポートリクエストの状態を管理する。
 */
@Entity
@Table(name = "data_exports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class DataExportEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    /** ステータス: PENDING / PROCESSING / COMPLETED / FAILED */
    @Column(nullable = false, length = 20)
    private String status;

    /** 収集対象カテゴリ（カンマ区切り）。nullは全カテゴリ */
    @Column(columnDefinition = "TEXT")
    private String categories;

    /** 進捗率 (0-100) */
    @Column(nullable = false)
    @Builder.Default
    private Integer progressPercent = 0;

    /** 処理ステップ（DBカラム名: current_step） */
    @Column(name = "current_step", length = 100)
    private String progressStep;

    /** S3オブジェクトキー（COMPLETED後に設定） */
    @Column(length = 500)
    private String s3Key;

    /** ZIPファイルサイズ（バイト）（DBカラム名: file_size_bytes） */
    @Column(name = "file_size_bytes")
    private Long fileSize;

    /** ZIPパスワードのbcryptハッシュ（平文は保存しない） */
    @Column(length = 100)
    private String zipPasswordHash;

    /** ダウンロード有効期限 */
    private LocalDateTime expiresAt;

    /** 失敗理由 */
    @Column(length = 500)
    private String errorMessage;

    /** 完了日時 */
    private LocalDateTime completedAt;

    /**
     * 処理中に遷移する。
     */
    public void markProcessing() {
        this.status = "PROCESSING";
        this.progressPercent = 0;
        this.progressStep = "started";
    }

    /**
     * 進捗を更新する。
     */
    public void updateProgress(int percent, String step) {
        this.progressPercent = percent;
        this.progressStep = step;
    }

    /**
     * 完了に遷移する。
     */
    public void markCompleted(String s3Key, long fileSize, String zipPasswordHash, LocalDateTime expiresAt) {
        this.status = "COMPLETED";
        this.s3Key = s3Key;
        this.fileSize = fileSize;
        this.zipPasswordHash = zipPasswordHash;
        this.expiresAt = expiresAt;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 失敗に遷移する。
     */
    public void markFailed(String errorMessage) {
        this.status = "FAILED";
        this.errorMessage = errorMessage;
    }

    /**
     * S3キーをクリアする（有効期限後のクリーンアップ用）。
     */
    public void clearS3Key() {
        this.s3Key = null;
    }
}
