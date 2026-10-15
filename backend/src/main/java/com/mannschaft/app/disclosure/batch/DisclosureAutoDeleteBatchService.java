package com.mannschaft.app.disclosure.batch;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.disclosure.entity.DisclosureAutoDeleteBatchLogEntity;
import com.mannschaft.app.disclosure.entity.DisclosureExportEntity;
import com.mannschaft.app.disclosure.repository.DisclosureAutoDeleteBatchLogRepository;
import com.mannschaft.app.disclosure.repository.DisclosureExportRepository;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
// （上の filesharing/storage は内部 TxHelper でのみ使用）
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 重要事項説明書 出力ファイル自動削除バッチ（F09.14 Phase 3-E）。
 *
 * <p>設計書 §5.7 出力ファイル保管期間（デフォルト 90 日、ADMIN による延長で最大 7 年）に基づき、
 * {@code disclosure_exports.expires_at} を過ぎた出力履歴とそれに紐づく R2 上の物理ファイルを
 * 削除する日次バッチ。</p>
 *
 * <h3>処理フロー</h3>
 * <ol>
 *   <li>{@link DisclosureExportRepository#findExpired(LocalDateTime, org.springframework.data.domain.Pageable)}
 *       で期限切れ（{@code expires_at <= now} かつ未削除）レコードを取得</li>
 *   <li>各レコードについて、紐づく {@link SharedFileEntity} を取得し、R2 から物理削除</li>
 *   <li>{@code disclosure_exports} を <strong>論理削除</strong>（{@code deleted_at} セット）</li>
 *   <li>失敗件数・成功件数を集計し {@code disclosure_auto_delete_batch_logs} に記録</li>
 * </ol>
 *
 * <h3>削除方針: 論理削除を採用する理由</h3>
 * <p>設計書 §5.7 / §6.3 で監査要件（誰が・いつ・何を出力したかの履歴を将来追跡可能にする）が
 * 求められているため、出力履歴レコード自体は <strong>物理削除せず {@code deleted_at} で論理削除</strong>
 * する。R2 上の生成ファイル（PDF/Excel）は実際にデータ削除する（個人情報保護のため必須）が、
 * 履歴メタデータは残す。これにより以下のメリットがある:</p>
 * <ul>
 *   <li>「誰が・いつ・何の様式を・どの提出先向けに出力したか」が監査可能</li>
 *   <li>削除されたファイルへのダウンロード要求が来た場合 {@code DISCLOSURE_001} で適切に応答できる</li>
 *   <li>個人情報スナップショット {@code data_snapshot} は別途 GDPR マスキングフローで除去する</li>
 * </ul>
 *
 * <h3>冪等性</h3>
 * <p>1 件ずつ別トランザクション（{@code REQUIRES_NEW}）で処理する。途中で失敗しても他のレコードは
 * コミット済みとなり、次回実行で残りを再試行できる。R2 削除と DB 論理削除を同一トランザクション内で
 * 行うため、R2 削除成功・DB 削除失敗時にロールバックされても、次回実行で R2 削除がリトライされる
 * （R2 の DELETE は冪等で同一キーへの 2 回目の削除は成功扱い）。</p>
 *
 * <h3>テスト容易性</h3>
 * <p>内部処理は {@link #executeAt(LocalDateTime)} に切り出し、テストから時刻を注入できる。</p>
 *
 * @see DisclosureExportRepository#findExpired
 * @see DisclosureAutoDeleteBatchLogEntity
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DisclosureAutoDeleteBatchService {

    /** ShedLock のジョブ名。 */
    static final String JOB_NAME = "disclosureAutoDeleteBatch";

    /** 1 回の実行で処理する最大件数（過剰な負荷を防ぐ安全装置）。 */
    static final int BATCH_LIMIT = 1000;

    private final DisclosureExportRepository exportRepository;
    private final DisclosureAutoDeleteBatchLogRepository batchLogRepository;
    private final DisclosureAutoDeleteBatchTxHelper txHelper;

    /**
     * 毎日 02:00（JST）に実行されるエントリポイント。
     *
     * <p>F09.14 設計書 §5.7 に基づき、深夜帯にバッチを実行することで業務時間中の負荷を回避する。
     * {@link SchedulerLock} により複数インスタンス起動時の同時実行を防ぐ。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると保持期間を超過した情報開示物が削除されず、法令上の保持期限を超えた個人データが残存する")
    @BatchEndpoint(name = "disclosure-auto-delete-daily", description = "保存期限超過の開示エクスポートを毎日 02:00 に自動削除する")
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(
            name = JOB_NAME,
            lockAtMostFor = "PT5M",
            lockAtLeastFor = "PT1M")
    public void run() {
        executeAt(LocalDateTime.now());
    }

    /**
     * バッチ本体。テストから時刻を注入できるように {@code now} を引数化している。
     *
     * <p>本メソッドは {@code @Transactional} を付けない。各レコードの削除は
     * {@link DisclosureAutoDeleteBatchTxHelper#deleteOne} で個別トランザクション化する。
     * バッチログ記録は最後に独立トランザクションで保存する。</p>
     *
     * @param now 基準時刻（通常は {@link LocalDateTime#now()}）
     */
    public DisclosureAutoDeleteBatchLogEntity executeAt(LocalDateTime now) {
        log.info("[DisclosureAutoDelete] バッチ開始: now={}", now);
        List<DisclosureExportEntity> expired = exportRepository.findExpired(
                now, PageRequest.of(0, BATCH_LIMIT));

        int totalExpired = expired.size();
        int totalDeleted = 0;
        int failedCount = 0;
        List<String> errorDetails = new ArrayList<>();

        for (DisclosureExportEntity export : expired) {
            try {
                txHelper.deleteOne(export.getId());
                totalDeleted++;
            } catch (RuntimeException e) {
                failedCount++;
                String detail = "exportId=" + export.getId() + ": "
                        + e.getClass().getSimpleName() + " " + safeMessage(e);
                errorDetails.add(detail);
                log.error("[DisclosureAutoDelete] 削除失敗: exportId={}", export.getId(), e);
            }
        }

        DisclosureAutoDeleteBatchLogEntity logEntity = DisclosureAutoDeleteBatchLogEntity.builder()
                .batchRunAt(now)
                .totalExpired(totalExpired)
                .totalDeleted(totalDeleted)
                .failedCount(failedCount)
                .errorDetails(errorDetails.isEmpty() ? null : String.join("\n", errorDetails))
                .build();
        DisclosureAutoDeleteBatchLogEntity saved = batchLogRepository.save(logEntity);

        log.info("[DisclosureAutoDelete] バッチ完了: expired={}, deleted={}, failed={}",
                totalExpired, totalDeleted, failedCount);
        return saved;
    }

    private String safeMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null) return "";
        // ログテーブルに突っ込む際の長さ抑制（TEXT 65535 だが 1 件あたり余裕を持たせる）
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    /**
     * 1 レコード単位の削除を独立トランザクション（{@code REQUIRES_NEW}）で実行するヘルパ。
     *
     * <p>本クラス内に切り出しているのは、Spring AOP プロキシによる {@code @Transactional}
     * 適用は同一 Bean 内のメソッド自己呼び出しでは無効化されるため。{@link DisclosureAutoDeleteBatchService}
     * から本ヘルパ Bean のメソッドを呼ぶことで {@code REQUIRES_NEW} が確実に適用される。</p>
     */
    @Service
    @RequiredArgsConstructor
    public static class DisclosureAutoDeleteBatchTxHelper {

        private final DisclosureExportRepository exportRepository;
        private final SharedFileRepository sharedFileRepository;
        private final R2StorageService r2StorageService;

        /**
         * 1 件の出力履歴を削除する（R2 物理削除 + DB 論理削除）。
         *
         * @throws BusinessException R2 削除失敗時（DB 論理削除はロールバックされる）
         */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void deleteOne(Long exportId) {
            DisclosureExportEntity export = exportRepository.findById(exportId)
                    .orElseThrow(() -> new IllegalStateException(
                            "対象 export が見つかりません: id=" + exportId));
            // 既に他のバッチ等で論理削除済みなら何もしない（冪等）
            if (export.getDeletedAt() != null) {
                return;
            }

            // R2 物理削除（R2 側のオブジェクトキーは shared_files.file_key を信頼）
            SharedFileEntity sharedFile = sharedFileRepository.findById(export.getSharedFileId())
                    .orElse(null);
            if (sharedFile != null) {
                r2StorageService.delete(sharedFile.getFileKey());
            }

            // DB 論理削除（履歴メタデータは監査用に保持）
            export.softDelete();
            exportRepository.save(export);
        }
    }
}
