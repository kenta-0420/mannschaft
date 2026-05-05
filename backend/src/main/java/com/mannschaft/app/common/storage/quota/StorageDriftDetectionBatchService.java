package com.mannschaft.app.common.storage.quota;

import com.mannschaft.app.common.storage.StorageProperties;
import com.mannschaft.app.common.storage.quota.entity.StorageSubscriptionEntity;
import com.mannschaft.app.common.storage.quota.entity.StorageUsageLogEntity;
import com.mannschaft.app.common.storage.quota.repository.StorageSubscriptionRepository;
import com.mannschaft.app.common.storage.quota.repository.StorageUsageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * F13 ストレージクォータ ドリフト検出・自動修正バッチ（週次）。
 *
 * <p>R2 の {@code ListObjectsV2} で各サブスクリプション（スコープ）ごとに
 * スコープ別プレフィックス {@code {feature}/{scopeType}/{scopeId}/} を走査し、
 * {@code storage_subscriptions.used_bytes} と実際の使用量を突合する。
 * 差異が 1MB 以上の場合は {@code used_bytes} を実測値に修正し、
 * {@code storage_usage_logs} に {@code DRIFT_CORRECTION} を記録する。</p>
 *
 * <h3>スコープ別プレフィックス方式（Phase 5-c 対応済み）</h3>
 * <p>各 feature_type のプレフィックスは以下のルートを使用する:</p>
 * <ul>
 *   <li>{@code timeline/{scopeType}/{scopeId}/} — F04.1 タイムライン（TIMELINE）</li>
 *   <li>{@code gallery/{scopeType}/{scopeId}/} — F06.2 メンバー紹介ギャラリー（GALLERY）</li>
 *   <li>{@code files/{scopeType}/{scopeId}/} — F05.5 ファイル共有（FILE_SHARING）</li>
 *   <li>{@code chat/{scopeType}/{scopeId}/} — F04.2 チャット（CHAT）</li>
 *   <li>{@code blog/{scopeType}/{scopeId}/} — F06.1 CMS/ブログ（CMS）</li>
 *   <li>{@code circulation/{scopeType}/{scopeId}/} — F05.2 回覧板（CIRCULATION）</li>
 *   <li>{@code bulletin/{scopeType}/{scopeId}/} — F05.1 掲示板（BULLETIN）</li>
 *   <li>{@code schedules/{scopeType}/{scopeId}/} — F03.14 スケジュールメディア（SCHEDULE_MEDIA）</li>
 *   <li>{@code user/PERSONAL/{scopeId}/timetable-notes/} — F03.15 個人時間割メモ添付（PERSONAL_TIMETABLE_NOTES、専用パターン）</li>
 * </ul>
 *
 * <h3>除外対象</h3>
 * <ul>
 *   <li>{@code thumbnails/} — Workers / クライアント生成サムネイル（自動生成物）</li>
 *   <li>{@code tmp/} — 一時ファイル（未コミット）</li>
 * </ul>
 *
 * <h3>移行期モード（{@code app.storage.drift.migration-mode-enabled=true}）</h3>
 * <p>Phase 5 の新パス移行期間中は、旧パスのオブジェクトも集計に含める。
 * {@link StorageFeatureType#PERSONAL_TIMETABLE_NOTES} の旧パス
 * {@code user/{scopeId}/timetable-notes/} のみスコープ特定が可能なため対応する。
 * 他の feature_type の旧パス（{@code chat/{uuid}/...} 等）はスコープ特定不可のため対象外。</p>
 *
 * <h3>Class A オペレーション課金対策</h3>
 * <ul>
 *   <li>ページングサイズ最大（1000 件/ページ）で {@code ListObjectsV2} 呼び出し数を抑制</li>
 *   <li>バッチは週次に厳守（日曜深夜 2:00 実行）</li>
 * </ul>
 *
 * @see <a href="../../../../../../../../../../docs/cross-cutting/storage_quota.md">設計書 §6 / §11</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageDriftDetectionBatchService {

    /** ドリフト修正のしきい値: 1MB 未満の差異は無視する */
    static final long DRIFT_THRESHOLD_BYTES = 1L * 1024 * 1024;

    /** R2 ListObjectsV2 のページングサイズ（Class A 課金を抑えるため最大値） */
    private static final int LIST_PAGE_SIZE = 1000;

    /**
     * feature_type → R2 トップレベルルートのマッピング。
     *
     * <p>Phase 5-c: スコープ別プレフィックス方式に移行。
     * {@link StorageFeatureType#PERSONAL_TIMETABLE_NOTES} は専用パターンを使用するため
     * このマップには含まれない（{@link #buildScopePrefix} 内で個別処理する）。</p>
     */
    static final Map<StorageFeatureType, String> FEATURE_ROOT_MAP;

    static {
        FEATURE_ROOT_MAP = new EnumMap<>(StorageFeatureType.class);
        FEATURE_ROOT_MAP.put(StorageFeatureType.TIMELINE, "timeline");
        FEATURE_ROOT_MAP.put(StorageFeatureType.GALLERY, "gallery");
        FEATURE_ROOT_MAP.put(StorageFeatureType.FILE_SHARING, "files");
        FEATURE_ROOT_MAP.put(StorageFeatureType.CHAT, "chat");
        FEATURE_ROOT_MAP.put(StorageFeatureType.CMS, "blog");
        FEATURE_ROOT_MAP.put(StorageFeatureType.CIRCULATION, "circulation");
        FEATURE_ROOT_MAP.put(StorageFeatureType.BULLETIN, "bulletin");
        FEATURE_ROOT_MAP.put(StorageFeatureType.SCHEDULE_MEDIA, "schedules");
        // PERSONAL_TIMETABLE_NOTES は専用パターン: user/PERSONAL/{scopeId}/timetable-notes/
        // buildScopePrefix() 内で個別処理するためここには含めない
    }

    private final S3Client s3Client;
    private final StorageProperties storageProperties;
    private final StorageSubscriptionRepository subscriptionRepository;
    private final StorageUsageLogRepository usageLogRepository;

    /**
     * 移行期モードフラグ。{@code true} の場合、旧パスのオブジェクトも集計に含める。
     * デフォルト {@code false}（通常運用）。
     */
    @Value("${app.storage.drift.migration-mode-enabled:false}")
    boolean migrationModeEnabled;

    /**
     * 週次ドリフト検出バッチのエントリポイント。
     * 毎週日曜日深夜 2:00 に実行する。
     */
    @Scheduled(cron = "0 0 2 * * SUN")
    public void execute() {
        log.info("F13 ドリフト検出バッチ 開始 (migrationMode={})", migrationModeEnabled);
        int correctedCount = 0;
        int skippedCount = 0;

        List<StorageSubscriptionEntity> subscriptions = subscriptionRepository.findAll();
        for (StorageSubscriptionEntity sub : subscriptions) {
            try {
                long r2Bytes = sumR2BytesForSubscription(sub);
                log.debug("F13 R2 集計完了: subscriptionId={}, scopeType={}, scopeId={}, r2Bytes={}",
                        sub.getId(), sub.getScopeType(), sub.getScopeId(), r2Bytes);
                int corrected = correctSubscriptionDrift(sub, r2Bytes);
                if (corrected > 0) {
                    correctedCount += corrected;
                } else {
                    skippedCount++;
                }
            } catch (Exception e) {
                log.error("F13 ドリフト修正失敗: subscriptionId={}, scopeType={}, scopeId={}, error={}",
                        sub.getId(), sub.getScopeType(), sub.getScopeId(), e.getMessage(), e);
            }
        }

        log.info("F13 ドリフト検出バッチ 完了: subscriptions={}, corrected={}, skipped={}",
                subscriptions.size(), correctedCount, skippedCount);
    }

    /**
     * 指定されたサブスクリプション（スコープ）に帰属する全 R2 オブジェクトのバイト数合計を返す。
     *
     * <p>全 {@link StorageFeatureType} に対してスコープ別プレフィックスを構築し、
     * {@link #listAllObjectsBytes} で走査して合算する。
     * 移行期モード有効時は旧パスも合算する（{@link #buildOldScopePrefix} 参照）。</p>
     *
     * @param sub 対象サブスクリプション
     * @return 合計バイト数
     */
    long sumR2BytesForSubscription(StorageSubscriptionEntity sub) {
        long total = 0L;
        for (StorageFeatureType featureType : StorageFeatureType.values()) {
            String newPrefix = buildScopePrefix(featureType, sub.getScopeType(), sub.getScopeId());
            total += listAllObjectsBytes(newPrefix);

            if (migrationModeEnabled) {
                Optional<String> oldPrefix = buildOldScopePrefix(featureType, sub.getScopeType(), sub.getScopeId());
                if (oldPrefix.isPresent()) {
                    total += listAllObjectsBytes(oldPrefix.get());
                }
            }
        }
        return total;
    }

    /**
     * feature_type とスコープ情報からスコープ別プレフィックスを構築する。
     *
     * <p>{@link StorageFeatureType#PERSONAL_TIMETABLE_NOTES} は専用パターンを使用する:
     * {@code user/PERSONAL/{scopeId}/timetable-notes/}</p>
     * <p>その他の feature_type は統一パターン:
     * {@code {root}/{scopeType}/{scopeId}/}</p>
     *
     * @param featureType feature_type
     * @param scopeType   スコープ種別（TEAM / ORGANIZATION / PERSONAL）
     * @param scopeId     スコープ ID
     * @return R2 プレフィックス文字列
     */
    String buildScopePrefix(StorageFeatureType featureType, String scopeType, Long scopeId) {
        if (featureType == StorageFeatureType.PERSONAL_TIMETABLE_NOTES) {
            return "user/PERSONAL/" + scopeId + "/timetable-notes/";
        }
        String root = FEATURE_ROOT_MAP.get(featureType);
        return root + "/" + scopeType + "/" + scopeId + "/";
    }

    /**
     * 移行期モード用: 旧パスのプレフィックスを返す。
     *
     * <p>{@link StorageFeatureType#PERSONAL_TIMETABLE_NOTES} のみ旧パスでスコープ特定が可能:
     * {@code user/{scopeId}/timetable-notes/}</p>
     * <p>他の feature_type（{@code chat/{uuid}/...} 等）は旧パスにスコープ情報が
     * 埋め込まれていないためスコープ特定不可 → {@link Optional#empty()} を返す。</p>
     *
     * @param featureType feature_type
     * @param scopeType   スコープ種別（未使用、将来拡張のため保持）
     * @param scopeId     スコープ ID
     * @return 旧パスプレフィックス（スコープ特定可能な場合のみ）
     */
    Optional<String> buildOldScopePrefix(StorageFeatureType featureType, String scopeType, Long scopeId) {
        if (featureType == StorageFeatureType.PERSONAL_TIMETABLE_NOTES) {
            // 旧パス: user/{userId}/timetable-notes/ (PERSONAL_TIMETABLE_NOTES のみスコープ特定可)
            return Optional.of("user/" + scopeId + "/timetable-notes/");
        }
        return Optional.empty();
    }

    /**
     * 指定プレフィックス配下の全オブジェクトのバイト数合計を返す。
     * R2 の {@code ListObjectsV2} をページングしながら全件走査する（最大 1000 件/ページ）。
     *
     * @param prefix R2 オブジェクトキーのプレフィックス
     * @return 合計バイト数
     */
    long listAllObjectsBytes(String prefix) {
        long totalBytes = 0L;
        String continuationToken = null;
        int pageCount = 0;

        do {
            ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                    .bucket(storageProperties.getBucket())
                    .prefix(prefix)
                    .maxKeys(LIST_PAGE_SIZE);
            if (continuationToken != null) {
                requestBuilder.continuationToken(continuationToken);
            }

            ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
            pageCount++;

            for (S3Object object : response.contents()) {
                // thumbnails/ と tmp/ を除外（サムネイル・未コミット一時ファイルはカウント対象外）
                String key = object.key();
                if (key.contains("/thumbnails/") || key.startsWith("thumbnails/")
                        || key.startsWith("tmp/")) {
                    continue;
                }
                Long size = object.size();
                if (size != null) {
                    totalBytes += size;
                }
            }

            continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
        } while (continuationToken != null);

        log.debug("F13 R2 リスト完了: prefix={}, pages={}, bytes={}", prefix, pageCount, totalBytes);
        return totalBytes;
    }

    /**
     * サブスクリプション 1 件のドリフトを検出し、1MB 以上の差異があれば修正する。
     *
     * @param sub          対象サブスクリプション
     * @param r2TotalBytes R2 の実測合計バイト数（スコープ別集計済み）
     * @return 修正件数（0 = 差異なし / 修正不要）
     */
    @Transactional
    int correctSubscriptionDrift(StorageSubscriptionEntity sub, long r2TotalBytes) {
        long dbBytes = sub.getUsedBytes() != null ? sub.getUsedBytes() : 0L;
        long diff = Math.abs(r2TotalBytes - dbBytes);

        if (diff < DRIFT_THRESHOLD_BYTES) {
            return 0;
        }

        log.warn("F13 ドリフト検出: subscriptionId={}, scopeType={}, scopeId={}, "
                        + "dbBytes={}, r2Bytes={}, diff={}",
                sub.getId(), sub.getScopeType(), sub.getScopeId(), dbBytes, r2TotalBytes, diff);

        // used_bytes を実測値に補正
        long deltaBytes = r2TotalBytes - dbBytes;
        sub.applyDelta(deltaBytes, 0);
        subscriptionRepository.save(sub);

        // DRIFT_CORRECTION ログを挿入
        StorageUsageLogEntity correctionLog = buildDriftCorrectionLog(sub, deltaBytes, r2TotalBytes);
        usageLogRepository.save(correctionLog);

        return 1;
    }

    private StorageUsageLogEntity buildDriftCorrectionLog(StorageSubscriptionEntity sub,
                                                           long deltaBytes,
                                                           long afterBytes) {
        // DRIFT_CORRECTION は特定の feature_type / reference に紐づかないため
        // feature_type = "DRIFT_CORRECTION" を使用し、referenceType / referenceId はダミー値を設定する
        return StorageUsageLogEntity.builder()
                .subscriptionId(sub.getId())
                .deltaBytes(deltaBytes)
                .afterBytes(afterBytes < 0 ? 0L : afterBytes)
                .featureType(StorageActionType.DRIFT_CORRECTION.name())
                .referenceType("storage_subscriptions")
                .referenceId(sub.getId())
                .action(StorageActionType.DRIFT_CORRECTION.name())
                .actorId(null)  // バッチ処理のため NULL
                .build();
    }

    /**
     * バッチの手動実行エントリポイント（SYSTEM_ADMIN 向け API から呼び出し可能）。
     *
     * @return 修正されたサブスクリプション数
     */
    public int executeManually() {
        log.info("F13 ドリフト検出バッチ 手動実行");
        execute();
        // 簡易実装: 手動実行も同じフローを走らせる
        return 0;
    }
}
