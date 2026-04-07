package com.mannschaft.app.quickmemo.entity;

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
 * Presigned URL 発行履歴エンティティ。
 * 重複制御・容量制限（1ユーザー1時間100MB）・孤立URL削除バッチに使用する。
 */
@Entity
@Table(name = "pending_uploads")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PendingUploadEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "memo_id", nullable = false)
    private Long memoId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "s3_key", nullable = false, unique = true, length = 512)
    private String s3Key;

    @Column(name = "declared_size_bytes", nullable = false)
    private Integer declaredSizeBytes;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "presigned_url_expires_at", nullable = false)
    private LocalDateTime presignedUrlExpiresAt;

    /** NULL = 未確認（孤立URL）、非NULL = confirm済み */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public boolean isConfirmed() {
        return this.confirmedAt != null;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.presignedUrlExpiresAt);
    }

    public void confirm() {
        this.confirmedAt = LocalDateTime.now();
    }
}
