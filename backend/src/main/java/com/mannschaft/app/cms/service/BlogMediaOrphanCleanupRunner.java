package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.entity.BlogMediaR2DeleteRetryEntity;
import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import com.mannschaft.app.cms.repository.BlogMediaR2DeleteRetryRepository;
import com.mannschaft.app.cms.repository.BlogMediaUploadRepository;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.util.SessionHashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 孤立ブログメディアクリーンアップ日次バッチ用の 1 件処理 REQUIRES_NEW 実行 Bean（Issue #2601）。
 *
 * <p>{@link BlogMediaService#cleanupOrphanMedia()} からループで呼ばれる。
 * 1 件の処理（行の確保 → R2 削除 → 使用量減算）を独立トランザクションとする必要があり、
 * 独立した Bean に切り出し {@link Propagation#REQUIRES_NEW} を付与する
 * （同一 Bean 内の自己呼び出しではプロキシを経由せず伝播設定が効かないため）。
 *
 * <p>R2 はトランザクション外の取り消せない外部操作であるため、バッチ全体を単一トランザクションに
 * 包んではならない。1 件ずつ独立コミットすることで、途中の 1 件で例外が起きても
 * 既に確定した他の件の DB 削除・R2 削除・使用量減算はロールバックされない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BlogMediaOrphanCleanupRunner {

    /** 孤立メディア R2 削除失敗を機械的に検索可能にする固定マーカー。 */
    static final String R2_DELETE_FAILED_MARKER = "ORPHAN_MEDIA_R2_DELETE_FAILED";

    private final R2StorageService r2StorageService;
    private final BlogMediaUploadRepository blogMediaUploadRepository;
    private final StorageQuotaService storageQuotaService;
    private final BlogMediaR2DeleteRetryRepository r2DeleteRetryRepository;

    /**
     * 1 件の孤立メディアを独立トランザクションで処理する。
     *
     * <p>行の確保（{@link BlogMediaUploadRepository#deleteOrphanById}）→ R2 削除 → 使用量減算 の
     * 一連を 1 トランザクションとして扱う。R2 削除に失敗しても DB 行の削除は確定させる
     * （孤立行を DB に残さないことを優先し、失敗は {@link #R2_DELETE_FAILED_MARKER} 付きの
     * {@code log.error} で機械的に追跡可能な形に残す。握り潰しではなく記録して続行する）。
     * ただし<b>使用量の減算は行わない</b> — オブジェクトが R2 に残っている以上、
     * 減算すると使用量が実体より過少になるため。
     *
     * @param orphan       クリーンアップ対象のメディアエンティティ（呼び出し元でフェッチ済み）
     * @param scopeResolver s3Key からストレージスコープを復元する関数（呼び出し元のロジックを再利用）
     * @return 処理結果（このトランザクションが実際に行を確保できたか、R2 削除に失敗したか）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrphanCleanupResult cleanupOne(
            BlogMediaUploadEntity orphan,
            java.util.function.Function<String, java.util.Optional<BlogMediaService.ScopeResolution>> scopeResolver) {

        // 行を先に確保する。0 行なら他の実行が既にこの行を処理済みであり、
        // R2 削除も使用量減算もその実行が行う（ここで重ねて減算してはならない）。
        if (blogMediaUploadRepository.deleteOrphanById(orphan.getId()) == 0) {
            log.debug("孤立メディアは別実行が処理済みのためスキップ: mediaId={}", orphan.getId());
            return new OrphanCleanupResult(false, false);
        }

        boolean r2DeleteFailed = false;
        try {
            r2StorageService.delete(orphan.getS3Key());
            if (orphan.getThumbnailR2Key() != null) {
                r2StorageService.delete(orphan.getThumbnailR2Key());
            }
        } catch (Exception e) {
            r2DeleteFailed = true;
            // R2 削除失敗は「握り潰し」ではなく機械的に検索可能な形で記録して続行する。
            // DB 行は既に削除済みのため、このオブジェクトは再走査されない孤児となる。
            // 本格的な自動リトライ機構は別任務とし、ここでは追跡可能なログ記録に留める。
            log.error("{}: mediaId={}, key={}, thumbnailKey={}",
                    R2_DELETE_FAILED_MARKER, orphan.getId(), orphan.getS3Key(), orphan.getThumbnailR2Key(), e);
            registerRetry(orphan, scopeResolver);
        }

        // F13 Phase 4-δ: 使用量減算（s3Key からスコープを復元）
        //
        // R2 削除に失敗した場合は減算しない。オブジェクトは R2 に残っているため、
        // ここで減算すると used_bytes が実体より過少になる。過少なクォータは以後の
        // 上限判定を誤らせ、ドリフト検出バッチが走るまで是正されない（本クラスの
        // 呼び出し元 Javadoc が警告しているとおり）。実体が残っている以上、
        // 使用量も据え置くのが安全側である。
        if (!r2DeleteFailed && orphan.getFileSize() != null && orphan.getFileSize() > 0) {
            scopeResolver.apply(orphan.getS3Key()).ifPresent(scope ->
                    storageQuotaService.recordDeletion(
                            scope.scopeType(), scope.scopeId(),
                            orphan.getFileSize(), StorageFeatureType.CMS,
                            BlogMediaService.REFERENCE_TYPE, orphan.getId(), orphan.getUploaderId()));
        }

        return new OrphanCleanupResult(true, r2DeleteFailed);
    }

    /**
     * R2 削除に失敗したオブジェクトを {@code blog_media_r2_delete_retries} に登録する
     * （Issue #2601 別任務）。DB 行は既に削除済みのため、この登録が唯一の再発見手段となる。
     *
     * <p>スコープが解決できない場合は使用量減算の対象を特定できないため登録しない
     * （{@link #R2_DELETE_FAILED_MARKER} ログが唯一の追跡手段のまま残る）。
     * 登録処理自体の失敗（一意制約違反等）はここで catch し、掃除処理全体を巻き込まない。
     * ただし握り潰さず必ず error ログを残す。</p>
     */
    private void registerRetry(
            BlogMediaUploadEntity orphan,
            java.util.function.Function<String, java.util.Optional<BlogMediaService.ScopeResolution>> scopeResolver) {
        try {
            String objectKey = orphan.getS3Key();
            String objectKeyHash = SessionHashUtil.hash(objectKey);
            if (r2DeleteRetryRepository.findByObjectKeyHash(objectKeyHash).isPresent()) {
                log.debug("R2削除リトライは登録済みのためスキップ: key={}", objectKey);
                return;
            }
            scopeResolver.apply(objectKey).ifPresentOrElse(scope -> {
                LocalDateTime now = LocalDateTime.now();
                BlogMediaR2DeleteRetryEntity retry = BlogMediaR2DeleteRetryEntity.builder()
                        .objectKey(objectKey)
                        .objectKeyHash(objectKeyHash)
                        .fileSize(orphan.getFileSize() != null ? orphan.getFileSize() : 0L)
                        .scopeType(scope.scopeType().name())
                        .scopeId(String.valueOf(scope.scopeId()))
                        .nextAttemptAt(now)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                r2DeleteRetryRepository.save(retry);
            }, () -> log.warn("R2削除リトライ登録スキップ（スコープ解決不可）: key={}", objectKey));
        } catch (Exception e) {
            // 登録処理の失敗は掃除処理全体（DB行削除・R2削除試行）を巻き込んではならない。
            // 握り潰しではなく必ず error ログを残して続行する。
            log.error("R2削除リトライの登録に失敗しました: mediaId={}, key={}", orphan.getId(), orphan.getS3Key(), e);
        }
    }

    /**
     * 1 件の孤立メディア処理結果。
     *
     * @param claimed       このトランザクションが行を確保できたか（false = 他の実行が処理済み）
     * @param r2DeleteFailed R2 削除に失敗したか（DB 削除・使用量減算は claimed=true なら確定済み）
     */
    public record OrphanCleanupResult(boolean claimed, boolean r2DeleteFailed) {}
}
