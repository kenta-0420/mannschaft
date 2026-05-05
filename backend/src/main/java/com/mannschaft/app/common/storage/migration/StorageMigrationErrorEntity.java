package com.mannschaft.app.common.storage.migration;

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
 * F13 Phase 5-b ストレージパス移行エラー記録エンティティ（{@code storage_migration_errors}）。
 *
 * <p>CopyObject に失敗した際のエラー情報を記録する。バッチは失敗してもスキップして続行し、
 * 管理者が本テーブルを参照して手動対応する。</p>
 */
@Entity
@Table(name = "storage_migration_errors")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class StorageMigrationErrorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 対象テーブル名（例: chat_message_attachments） */
    @Column(nullable = false, length = 50)
    private String referenceType;

    /** 対象レコードID */
    @Column(nullable = false)
    private Long referenceId;

    /** 移行前R2キー */
    @Column(nullable = false, length = 1000)
    private String oldFileKey;

    /** 移行先R2キー */
    @Column(nullable = false, length = 1000)
    private String newFileKey;

    /** エラー内容 */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** リトライ回数 */
    @Column(nullable = false)
    @Builder.Default
    private int retryCount = 0;

    /** 記録日時 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 解決日時（手動マーク用） */
    @Column
    private LocalDateTime resolvedAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
