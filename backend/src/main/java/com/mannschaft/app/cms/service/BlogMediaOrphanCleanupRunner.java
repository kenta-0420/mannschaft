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
     * {@code log.error} で機械的に追跡可能な形に残しつつ、{@code blog_media_r2_delete_retries}
     * へ登録して日次バッチ（{@link BlogMediaR2DeleteRetryBatchService}）に指数バックオフで
     * 再試行させる。握り潰しではなく記録して続行する）。
     * ただし<b>使用量の減算は行わない</b> — オブジェクトが R2 に残っている以上、
     * 減算すると使用量が実体より過少になるため。減算はリトライで実際に削除が確定したときに行う。
     *
     * <p><b>本体（s3Key）とサムネイル（thumbnailR2Key）は個別の R2 オブジェクトであり、
     * 削除の成否も独立している。</b> 一方だけ削除に失敗した場合に他方まで再試行対象にすると、
     * 既に削除済みのキーに対する削除呼び出しが「成功」扱いになり無駄なだけでなく、
     * 失敗した方の再登録漏れにも直結しかねないため、それぞれを個別の try で囲み、
     * 失敗したキーだけを {@link #registerRetry} に渡す。</p>
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

        // 本体（s3Key）とサムネイル（thumbnailR2Key）は別オブジェクトのため、削除・失敗時の
        // リトライ登録をそれぞれ独立に行う。片方の例外がもう片方の削除試行を止めないよう、
        // try を分ける（1つの try にまとめると、先に落ちた方の例外で後続の削除が実行されず、
        // 実際には未削除のキーが「試行すらされていない」まま握り潰されたのと同じ結果になる）。
        boolean r2DeleteFailed = false;
        try {
            r2StorageService.delete(orphan.getS3Key());
        } catch (Exception e) {
            r2DeleteFailed = true;
            log.error("{}: mediaId={}, key={}", R2_DELETE_FAILED_MARKER, orphan.getId(), orphan.getS3Key(), e);
            registerRetry(orphan, orphan.getS3Key(), orphan.getFileSize(), scopeResolver);
        }

        if (orphan.getThumbnailR2Key() != null) {
            try {
                r2StorageService.delete(orphan.getThumbnailR2Key());
            } catch (Exception e) {
                r2DeleteFailed = true;
                log.error("{}: mediaId={}, thumbnailKey={}",
                        R2_DELETE_FAILED_MARKER, orphan.getId(), orphan.getThumbnailR2Key(), e);
                // サムネイルは使用量減算の対象外（quota は本体 s3Key の file_size のみで計上している）。
                // リトライ行の file_size は 0 とし、成功時に使用量減算が発生しないようにする
                // （減算は本体行の成功時にのみ発生させ、二重減算を起こさない）。
                registerRetry(orphan, orphan.getThumbnailR2Key(), 0L, scopeResolver);
            }
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
     * R2 削除に失敗した 1 個のオブジェクトを {@code blog_media_r2_delete_retries} に登録する
     * （Issue #2601 別任務）。DB 行は既に削除済みのため、この登録が唯一の再発見手段となる。
     *
     * <p>本体・サムネイルのどちらであっても、削除に失敗した個々のキーを独立した行として登録する
     * （呼び出し元 {@link #cleanupOne} を参照）。使用量減算は {@code fileSize} が正の値のときのみ
     * 発生するため、サムネイル分は {@code fileSize=0} で渡し二重減算を起こさない。</p>
     *
     * <p>スコープが解決できない場合は使用量減算の対象を特定できないため登録しない
     * （{@link #R2_DELETE_FAILED_MARKER} ログが唯一の追跡手段のまま残る）。
     * 登録処理自体の失敗（一意制約違反等）はここで catch し、掃除処理全体を巻き込まない。
     * ただし握り潰さず必ず error ログを残す。</p>
     *
     * @param orphan   クリーンアップ対象のメディアエンティティ（ログ・スコープ復元用）
     * @param key      削除に失敗した R2 オブジェクトキー（本体 s3Key またはサムネイル thumbnailR2Key）
     * @param fileSize 削除成功時に使用量から減算するバイト数（サムネイルは 0 を渡す）
     */
    private void registerRetry(
            BlogMediaUploadEntity orphan, String key, Long fileSize,
            java.util.function.Function<String, java.util.Optional<BlogMediaService.ScopeResolution>> scopeResolver) {
        try {
            String objectKeyHash = SessionHashUtil.hash(key);
            if (r2DeleteRetryRepository.findByObjectKeyHash(objectKeyHash).isPresent()) {
                log.debug("R2削除リトライは登録済みのためスキップ: key={}", key);
                return;
            }
            scopeResolver.apply(orphan.getS3Key()).ifPresentOrElse(scope -> {
                LocalDateTime now = LocalDateTime.now();
                BlogMediaR2DeleteRetryEntity retry = BlogMediaR2DeleteRetryEntity.builder()
                        .objectKey(key)
                        .objectKeyHash(objectKeyHash)
                        .fileSize(fileSize != null ? fileSize : 0L)
                        .scopeType(scope.scopeType().name())
                        .scopeId(String.valueOf(scope.scopeId()))
                        .nextAttemptAt(now)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                r2DeleteRetryRepository.save(retry);
            }, () -> log.warn("R2削除リトライ登録スキップ（スコープ解決不可）: key={}", key));
        } catch (Exception e) {
            // 登録処理の失敗は掃除処理全体（DB行削除・R2削除試行）を巻き込んではならない。
            // 握り潰しではなく必ず error ログを残して続行する。
            log.error("R2削除リトライの登録に失敗しました: mediaId={}, key={}", orphan.getId(), key, e);
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
