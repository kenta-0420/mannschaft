package com.mannschaft.app.files.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Multipart Upload セッション管理エンティティ。
 * 大容量ファイル（100MB 超）の R2 Multipart Upload フローにおける
 * セッション情報（R2 uploadId・オブジェクトキー・状態）を管理する。
 * セッションは開始から24時間で自動失効する。
 */
@Entity
@Table(name = "multipart_upload_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MultipartUploadSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** R2/S3 Multipart Upload ID（一意） */
    @Column(name = "upload_id", nullable = false, unique = true, length = 255)
    private String uploadId;

    /** アップロード先の R2 オブジェクトキー */
    @Column(name = "r2_key", nullable = false, length = 500)
    private String r2Key;

    /** 呼び出し元機能（timeline / gallery / blog / files） */
    @Column(name = "feature", nullable = false, length = 30)
    private String feature;

    /** スコープ種別（PERSONAL / TEAM / ORGANIZATION） */
    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    /** スコープ ID（teams.id / organizations.id / users.id） */
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /** アップロードを開始したユーザーの ID */
    @Column(name = "uploader_id", nullable = false)
    private Long uploaderId;

    /** アップロードファイルの MIME タイプ */
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    /**
     * セッション状態。
     * IN_PROGRESS=アップロード中, COMPLETED=完了, ABORTED=中断
     */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "IN_PROGRESS";

    /** セッション有効期限（開始から24時間） */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
