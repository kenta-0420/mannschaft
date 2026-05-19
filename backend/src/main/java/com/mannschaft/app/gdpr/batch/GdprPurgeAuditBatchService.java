package com.mannschaft.app.gdpr.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
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
 * {@code status = 'PENDING'} のまま {@code attempted_at} から 2 時間以上経過している
 * レコードを検出してアラートログを出力する。</p>
 *
 * <h2>タイミングの意図</h2>
 * <ul>
 *   <li>04:00: {@code AccountPurgeService}（物理削除バッチ）が実行 → PENDING レコードを INSERT → {@code AccountPurgedEvent} 発火</li>
 *   <li>04:00〜: 各 {@code *PurgeEventListener} が非同期で処理 → SUCCESS に更新</li>
 *   <li>05:00: 本バッチが実行。2 時間（= 1 時間のバッファ）以上 PENDING のまま = リスナー未処理 → アラート</li>
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

    /** PENDING から SUCCESS への更新が完了するまでに許容する時間（時間）。 */
    private static final long ALERT_THRESHOLD_HOURS = 2L;

    private final AccountPurgeCompletionStatusRepository completionStatusRepository;

    /**
     * GDPR purge 全ドメイン処理完了を監査し、PENDING 残存をアラートする（毎日 05:00 JST）。
     *
     * <p>2 時間以上 PENDING のまま経過しているレコードをユーザー別にグルーピングし、
     * {@code log.error} でアラートを出力する。正常完了時は {@code log.info} で完了を記録する。</p>
     */
    @BatchEndpoint(
            name = "gdpr-purge-audit-daily",
            description = "AccountPurgedEvent の全ドメイン処理完了を監査し、PENDING 残存をアラートする（毎日 05:00 JST）"
    )
    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "gdprPurgeAuditBatch", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void audit() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(ALERT_THRESHOLD_HOURS);
        List<AccountPurgeCompletionStatusEntity> pendingList =
                completionStatusRepository.findByStatusAndAttemptedAtBefore("PENDING", threshold);

        if (pendingList.isEmpty()) {
            log.info("GDPR purge audit: 全ドメイン処理完了。未完了エントリなし");
            return;
        }

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
}
