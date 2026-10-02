package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.entity.BlogMediaR2DeleteRetryEntity;
import com.mannschaft.app.cms.repository.BlogMediaR2DeleteRetryRepository;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ブログメディア R2 削除リトライ 1 件処理の {@code REQUIRES_NEW} 実行 Bean（Issue #2601 別任務）。
 *
 * <p>{@link BlogMediaR2DeleteRetryBatchService} からループで呼ばれる。R2 削除はトランザクション外の
 * 取り消せない外部操作であるため、バッチ全体を単一トランザクションに包んで1件ずつ catch する構造は
 * ロールバックオンリーで全滅する。1 件（R2 削除 → 状態更新 → 成功時のみ使用量減算）を独立した Bean に
 * 切り出し {@link Propagation#REQUIRES_NEW} を付与する（同一 Bean 内の自己呼び出しではプロキシを
 * 経由せず伝播設定が効かないため）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BlogMediaR2DeleteRetryRunner {

    /** 試行上限。到達すると {@code ABANDONED} にし以後自動では触らない。 */
    static final int MAX_ATTEMPT_COUNT = 5;

    /**
     * 指数バックオフ間隔（試行回数 1 → 5 回目失敗後の次回試行までの待ち時間）。
     * 1時間 → 6時間 → 24時間 → 72時間 → 168時間 と段階的に広げ、恒久的な障害（バケット権限喪失等）で
     * 無駄な再試行を連発しないようにしつつ、一過性障害（R2 の瞬断等）は早期に回復させる。
     */
    static final Duration[] BACKOFF_INTERVALS = {
            Duration.ofHours(1),
            Duration.ofHours(6),
            Duration.ofHours(24),
            Duration.ofHours(72),
            Duration.ofHours(168),
    };

    /** {@code last_error} 列（VARCHAR(500)）に収まるよう切り詰める上限文字数。 */
    private static final int LAST_ERROR_MAX_LENGTH = 500;

    private final R2StorageService r2StorageService;
    private final BlogMediaR2DeleteRetryRepository retryRepository;
    private final StorageQuotaService storageQuotaService;
    private final Clock clock;

    /**
     * 1 件のリトライ対象を独立トランザクションで処理する。
     *
     * @param retry 処理対象（呼び出し元でフェッチ済み）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryOne(BlogMediaR2DeleteRetryEntity retry) {
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            r2StorageService.delete(retry.getObjectKey());

            retry.markSucceeded(now);
            retryRepository.save(retry);
            // R2 削除が成功して初めて使用量を減算する（オブジェクトが実際に消えたことが確定した後）。
            storageQuotaService.recordDeletion(
                    StorageScopeType.valueOf(retry.getScopeType()),
                    Long.valueOf(retry.getScopeId()),
                    retry.getFileSize(),
                    StorageFeatureType.CMS,
                    BlogMediaService.REFERENCE_TYPE,
                    null,
                    null);
            log.info("R2削除リトライ成功: retryId={}, key={}", retry.getId(), retry.getObjectKey());
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            if (errorMessage.length() > LAST_ERROR_MAX_LENGTH) {
                errorMessage = errorMessage.substring(0, LAST_ERROR_MAX_LENGTH);
            }

            int attemptIndex = retry.getAttemptCount(); // 0始まり。今回の失敗で attemptCount+1 回目が完了する
            LocalDateTime nextAttemptAt = now.plus(BACKOFF_INTERVALS[Math.min(attemptIndex, BACKOFF_INTERVALS.length - 1)]);
            retry.recordFailure(errorMessage, nextAttemptAt, now);

            if (retry.getAttemptCount() >= MAX_ATTEMPT_COUNT) {
                retry.abandon(now);
                log.warn("R2削除リトライ上限到達のため放棄: retryId={}, key={}, attemptCount={}",
                        retry.getId(), retry.getObjectKey(), retry.getAttemptCount());
            } else {
                log.error("R2削除リトライ失敗: retryId={}, key={}, attemptCount={}, nextAttemptAt={}",
                        retry.getId(), retry.getObjectKey(), retry.getAttemptCount(), nextAttemptAt, e);
            }
            retryRepository.save(retry);
        }
    }
}
