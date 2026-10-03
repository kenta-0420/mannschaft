package com.mannschaft.app.gdpr.batch;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import com.mannschaft.app.gdpr.entity.GdprS3PurgeFailureEntity;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.gdpr.repository.GdprS3PurgeFailureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * GDPR 30日以内削除完了を監査する日次バッチ。
 *
 * <p>毎日 05:00（JST）に {@code account_purge_completion_status} テーブルを走査し、
 * {@code status = 'PENDING'} のまま {@code attempted_at} から 30 分以上経過している
 * レコードを検出してアラートログを出力する。</p>
 *
 * <h2>タイミングの意図</h2>
 * <ul>
 *   <li>04:00: {@code AccountPurgeService}（物理削除バッチ）が実行 → PENDING レコードを INSERT → {@code AccountPurgedEvent} 発火</li>
 *   <li>04:00〜: 各 {@code *PurgeEventListener} が非同期で処理 → SUCCESS に更新</li>
 *   <li>05:00: 本バッチが実行。閾値 = 05:00 − 30 分 = 04:30。04:00 の PENDING は 04:00 &lt; 04:30 で同日中に検出。</li>
 * </ul>
 *
 * <h2>アラートの意味</h2>
 * <p>アラートログが出力された場合、対象ユーザーのドメイン処理が未完了であり、
 * GDPR Art.17「30日以内削除完了」タイムリミットが危うい可能性がある。
 * 夜次補正バッチまたは手動操作でリカバリすること。</p>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md} §4 Phase D-8</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GdprPurgeAuditBatchService {

    /** PENDING から SUCCESS への更新が完了するまでに許容する時間（分）。04:00 作成のレコードを 05:00 実行時に同日検出できる値。 */
    private static final long ALERT_THRESHOLD_MINUTES = 30L;

    /** S3 削除リトライの上限回数。上限に達したレコードは ERROR ログでアラートする。 */
    private static final int MAX_RETRY_COUNT = 3;

    private final AccountPurgeCompletionStatusRepository completionStatusRepository;
    private final GdprS3PurgeFailureRepository gdprS3PurgeFailureRepository;
    private final StorageService storageService;

    /**
     * GDPR purge 全ドメイン処理完了を監査し、PENDING 残存をアラートする（毎日 05:00 JST）。
     *
     * <p>30 分以上 PENDING のまま経過しているレコードをユーザー別にグルーピングし、
     * {@code log.error} でアラートを出力する。正常完了時は {@code log.info} で完了を記録する。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると GDPR 消去の実行監査が行われず、消去漏れが検知されないまま法令上の消去期限を破っていることに気付けない")
    @BatchEndpoint(
            name = "gdpr-purge-audit-daily",
            description = "AccountPurgedEvent の全ドメイン処理完了を監査し、PENDING 残存をアラートする（毎日 05:00 JST）"
    )
    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "gdprPurgeAuditBatch", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void audit() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(ALERT_THRESHOLD_MINUTES);
        List<AccountPurgeCompletionStatusEntity> pendingList =
                completionStatusRepository.findByStatusAndAttemptedAtBefore("PENDING", threshold);

        if (pendingList.isEmpty()) {
            log.info("GDPR purge audit: 全ドメイン処理完了。未完了エントリなし");
        } else {
            // 未完了エントリをユーザー別にグルーピングしてログ出力
            pendingList.stream()
                    .collect(Collectors.groupingBy(AccountPurgeCompletionStatusEntity::getUserId))
                    .forEach((uid, entries) -> {
                        List<String> pendingDomains = entries.stream()
                                .map(AccountPurgeCompletionStatusEntity::getDomainName)
                                .toList();
                        log.error(
                                "GDPR purge 未完了検出: userId={}, emailHash={}, pendingDomains={}, attemptedAt={}",
                                uid,
                                entries.get(0).getEmailHash(),
                                pendingDomains,
                                entries.get(0).getAttemptedAt()
                        );
                    });

            log.warn("GDPR purge audit 完了: 未完了エントリ={}件（詳細は上記 ERROR ログ参照）",
                    pendingList.size());
        }

        // S3 削除失敗レコードのリトライ（PENDING 有無にかかわらず毎回実行）
        retryS3PurgeFailures();
    }

    /**
     * S3 削除失敗レコードを順次リトライする。
     *
     * <p>未解決（{@code resolved_at} が null）のレコードをすべて取得し、
     * {@link StorageService#delete} を呼び出す。成功時は {@code resolved_at} をセットし、
     * 失敗時は {@code retry_count} をインクリメントして次回に持ち越す。
     * {@code retry_count >= MAX_RETRY_COUNT} に達した場合は ERROR ログでアラートする。</p>
     */
    private void retryS3PurgeFailures() {
        List<GdprS3PurgeFailureEntity> failures = gdprS3PurgeFailureRepository.findByResolvedAtIsNull();
        if (failures.isEmpty()) {
            return;
        }

        int resolved = 0;
        int stillFailing = 0;

        for (GdprS3PurgeFailureEntity failure : failures) {
            try {
                storageService.delete(failure.getS3Key());
                failure.setResolvedAt(LocalDateTime.now());
                gdprS3PurgeFailureRepository.save(failure);
                log.info("S3削除リトライ成功: userId={}, s3Key={}", failure.getUserId(), failure.getS3Key());
                resolved++;
            } catch (Exception e) {
                failure.setRetryCount(failure.getRetryCount() + 1);
                failure.setLastRetriedAt(LocalDateTime.now());
                failure.setLastError(truncate(e.getMessage(), 500));
                gdprS3PurgeFailureRepository.save(failure);

                if (failure.getRetryCount() >= MAX_RETRY_COUNT) {
                    log.error("S3削除リトライ上限到達（GDPR物理削除未完了）: userId={}, s3Key={}, retryCount={}",
                            failure.getUserId(), failure.getS3Key(), failure.getRetryCount());
                } else {
                    log.warn("S3削除リトライ失敗（次回再試行）: userId={}, s3Key={}, retryCount={}",
                            failure.getUserId(), failure.getS3Key(), failure.getRetryCount());
                }
                stillFailing++;
            }
        }

        log.info("S3削除リトライ完了: 解決={}件, 未解決={}件", resolved, stillFailing);
    }

    /**
     * 文字列を指定文字数以内に切り詰める。null 安全。
     *
     * @param s   対象文字列
     * @param max 最大文字数
     * @return 切り詰めた文字列、または null（s が null の場合）
     */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
