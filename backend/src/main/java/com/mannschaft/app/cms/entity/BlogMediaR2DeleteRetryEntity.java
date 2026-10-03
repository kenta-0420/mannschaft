package com.mannschaft.app.cms.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * ブログメディア孤立オブジェクトの R2 削除リトライ台帳エンティティ（Issue #2601 別任務）。
 *
 * <p>{@code BlogMediaOrphanCleanupRunner} が claim-then-act で DB 行を先に削除した後に R2 削除へ
 * 失敗すると、そのオブジェクトは以後の孤立メディア走査で二度と拾われない。本エンティティは
 * その削除失敗オブジェクトを追跡し、{@code BlogMediaR2DeleteRetryBatchService} が日次で
 * 指数バックオフ再試行する対象を表す。</p>
 *
 * @see com.mannschaft.app.cms.service.BlogMediaOrphanCleanupRunner
 */
@Entity
@Table(name = "blog_media_r2_delete_retries")
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(callSuper = true)
public class BlogMediaR2DeleteRetryEntity extends UuidV7Entity {

    /** R2 のオブジェクトキー（削除に失敗した対象）。 */
    @Column(name = "object_key", nullable = false, length = 1024)
    private String objectKey;

    /**
     * {@link #objectKey} の SHA-256 16進文字列（固定64文字）。
     *
     * <p>{@code object_key} は VARCHAR(1024) であり utf8mb4 換算で InnoDB の索引長制限
     * （3072 byte）を超えるため UNIQUE 索引を直接張れない。ハッシュ列側に UNIQUE 制約を
     * 張ることで二重登録防止の一意性を索引長制限内で成立させる。</p>
     */
    @Column(name = "object_key_hash", nullable = false, length = 64)
    private String objectKeyHash;

    /** 削除成功時に使用量から減算するバイト数。 */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** 使用量減算の対象スコープ種別。 */
    @Column(name = "scope_type", nullable = false, length = 32)
    private String scopeType;

    /** 使用量減算の対象スコープ ID。 */
    @Column(name = "scope_id", nullable = false, length = 64)
    private String scopeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @lombok.Builder.Default
    private BlogMediaR2DeleteRetryStatus status = BlogMediaR2DeleteRetryStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    @lombok.Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 削除成功時の状態遷移。
     *
     * @param now 更新時刻
     */
    public void markSucceeded(LocalDateTime now) {
        this.status = BlogMediaR2DeleteRetryStatus.SUCCEEDED;
        this.updatedAt = now;
    }

    /**
     * 削除失敗時の状態更新（試行回数加算・バックオフ前進）。
     * {@code attemptCount} が上限に達した場合は呼び出し側が {@link #abandon} を呼ぶこと。
     *
     * @param errorMessage  直近の失敗理由（呼び出し側で切り詰め済みであること）
     * @param nextAttemptAt 次回試行時刻
     * @param now           更新時刻
     */
    public void recordFailure(String errorMessage, LocalDateTime nextAttemptAt, LocalDateTime now) {
        this.attemptCount = this.attemptCount + 1;
        this.lastError = errorMessage;
        this.nextAttemptAt = nextAttemptAt;
        this.updatedAt = now;
    }

    /**
     * 試行上限到達時に以後の自動リトライ対象から外す。
     *
     * @param now 更新時刻
     */
    public void abandon(LocalDateTime now) {
        this.status = BlogMediaR2DeleteRetryStatus.ABANDONED;
        this.updatedAt = now;
    }
}
