package com.mannschaft.app.gdpr.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * GDPR S3 削除失敗記録エンティティ。
 *
 * <p>{@link com.mannschaft.app.gdpr.service.AccountPurgeService#purgeUser} 内で
 * S3 ファイル削除に失敗した場合に本テーブルへレコードを INSERT する。
 * {@link com.mannschaft.app.gdpr.batch.GdprPurgeAuditBatchService} が毎日 05:00 に
 * 未解決レコードをリトライし、成功時に {@code resolved_at} をセットする。</p>
 *
 * <h2>設計上の注意</h2>
 * <ul>
 *   <li>{@code user_id} は FK 制約なし（CLAUDE.md 原則 1: クロスドメイン FK 禁止）</li>
 *   <li>主キーは UUIDv7（CLAUDE.md 原則 6: 新規テーブルは UUIDv7）</li>
 * </ul>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md} §4 Phase G</p>
 */
@Entity
@Table(name = "gdpr_s3_purge_failures")
@Getter
@Setter
@NoArgsConstructor
public class GdprS3PurgeFailureEntity extends UuidV7Entity {

    /**
     * 削除対象ユーザーの ID。
     * users テーブルへの FK 制約なし（クロスドメイン FK 禁止原則）。
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 削除に失敗した S3 オブジェクトキー。
     */
    @Column(name = "s3_key", nullable = false, length = 500)
    private String s3Key;

    /**
     * 最初に削除失敗した日時。
     */
    @Column(name = "failed_at", nullable = false)
    private LocalDateTime failedAt;

    /**
     * リトライ累計回数。初期値 0。
     * {@code GdprPurgeAuditBatchService#retryS3PurgeFailures} が実行するたびにインクリメントする。
     */
    @Column(name = "retry_count", nullable = false, columnDefinition = "TINYINT UNSIGNED NOT NULL DEFAULT 0")
    private int retryCount = 0;

    /**
     * 最後にリトライを試みた日時。初回は null。
     */
    @Column(name = "last_retried_at")
    private LocalDateTime lastRetriedAt;

    /**
     * 最後に発生したエラーメッセージ（最大 500 文字）。
     */
    @Column(name = "last_error", length = 500)
    private String lastError;

    /**
     * S3 削除に成功した日時（リトライ成功時にセット）。
     * null = 未解決。
     */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;
}
