package com.mannschaft.app.family;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ファミリー機能のバッチサービス。帰宅遅延リマインド・記念日通知・クリーンアップを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FamilyBatchService {

    private static final int OVERDUE_15MIN_MINUTES = 15;
    private static final int OVERDUE_1HOUR_MINUTES = 60;
    private static final int PRESENCE_RETENTION_DAYS = 90;
    private static final int COIN_TOSS_RETENTION_DAYS = 30;

    private final PresenceEventRepository presenceEventRepository;
    private final CoinTossResultRepository coinTossResultRepository;

    /**
     * 帰宅遅延リマインドバッチ（15分ごと）。
     * <p>
     * 3段階エスカレーション:
     * <ol>
     *   <li>15分超過 → ADMIN にリマインド（overdue_level: 0→1）</li>
     *   <li>1時間超過 → チーム全員に警告（overdue_level: 1→2）</li>
     *   <li>以降は追加通知なし</li>
     * </ol>
     */
    @Scheduled(fixedRate = 15 * 60 * 1000)
    @Transactional
    public void checkOverdueEvents() {
        LocalDateTime now = LocalDateTime.now();

        // 15分超過（level 0 → 1）
        List<PresenceEventEntity> level0Events = presenceEventRepository.findOverdueEvents(
                0, now.minusMinutes(OVERDUE_15MIN_MINUTES));
        for (PresenceEventEntity event : level0Events) {
            event.updateOverdueLevel(1);
            log.info("帰宅遅延15分超過: userId={}, teamId={}, eventId={}",
                    event.getUserId(), event.getTeamId(), event.getId());
            // TODO: ADMINにプッシュ通知
        }

        // 1時間超過（level 1 → 2）
        List<PresenceEventEntity> level1Events = presenceEventRepository.findOverdueEvents(
                1, now.minusMinutes(OVERDUE_1HOUR_MINUTES));
        for (PresenceEventEntity event : level1Events) {
            event.updateOverdueLevel(2);
            log.info("帰宅遅延1時間超過: userId={}, teamId={}, eventId={}",
                    event.getUserId(), event.getTeamId(), event.getId());
            // TODO: チーム全員にプッシュ通知
        }

        if (!level0Events.isEmpty() || !level1Events.isEmpty()) {
            log.info("帰宅遅延チェック完了: 15分超過={}件, 1時間超過={}件",
                    level0Events.size(), level1Events.size());
        }
    }

    /**
     * 記念日通知バッチ（日次 09:00 JST）。
     */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Tokyo")
    public void checkAnniversaries() {
        // TODO: 記念日通知の実装（F04.3プッシュ通知と連携）
        log.info("記念日通知バッチを実行しました");
    }

    /**
     * プレゼンスイベント・コイントス結果のクリーンアップバッチ（日次 04:00 JST）。
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tokyo")
    @Transactional
    public void cleanupOldRecords() {
        LocalDateTime presenceThreshold = LocalDateTime.now().minusDays(PRESENCE_RETENTION_DAYS);
        presenceEventRepository.deleteByCreatedAtBefore(presenceThreshold);

        LocalDateTime coinTossThreshold = LocalDateTime.now().minusDays(COIN_TOSS_RETENTION_DAYS);
        coinTossResultRepository.deleteByCreatedAtBefore(coinTossThreshold);

        log.info("クリーンアップバッチ完了: プレゼンス({}日以前), コイントス({}日以前)",
                PRESENCE_RETENTION_DAYS, COIN_TOSS_RETENTION_DAYS);
    }
}
