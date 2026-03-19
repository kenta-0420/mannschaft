package com.mannschaft.app.family.service;

import com.mannschaft.app.family.entity.PresenceEventEntity;
import com.mannschaft.app.family.repository.CoinTossResultRepository;
import com.mannschaft.app.family.repository.PresenceEventRepository;
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

    @Scheduled(fixedRate = 15 * 60 * 1000)
    @Transactional
    public void checkOverdueEvents() {
        LocalDateTime now = LocalDateTime.now();
        List<PresenceEventEntity> level0Events = presenceEventRepository.findOverdueEvents(0, now.minusMinutes(OVERDUE_15MIN_MINUTES));
        for (PresenceEventEntity event : level0Events) {
            event.updateOverdueLevel(1);
            log.info("帰宅遅延15分超過: userId={}, teamId={}, eventId={}", event.getUserId(), event.getTeamId(), event.getId());
        }
        List<PresenceEventEntity> level1Events = presenceEventRepository.findOverdueEvents(1, now.minusMinutes(OVERDUE_1HOUR_MINUTES));
        for (PresenceEventEntity event : level1Events) {
            event.updateOverdueLevel(2);
            log.info("帰宅遅延1時間超過: userId={}, teamId={}, eventId={}", event.getUserId(), event.getTeamId(), event.getId());
        }
        if (!level0Events.isEmpty() || !level1Events.isEmpty()) {
            log.info("帰宅遅延チェック完了: 15分超過={}件, 1時間超過={}件", level0Events.size(), level1Events.size());
        }
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Tokyo")
    public void checkAnniversaries() {
        log.info("記念日通知バッチを実行しました");
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tokyo")
    @Transactional
    public void cleanupOldRecords() {
        LocalDateTime presenceThreshold = LocalDateTime.now().minusDays(PRESENCE_RETENTION_DAYS);
        presenceEventRepository.deleteByCreatedAtBefore(presenceThreshold);
        LocalDateTime coinTossThreshold = LocalDateTime.now().minusDays(COIN_TOSS_RETENTION_DAYS);
        coinTossResultRepository.deleteByCreatedAtBefore(coinTossThreshold);
        log.info("クリーンアップバッチ完了: プレゼンス({}日以前), コイントス({}日以前)", PRESENCE_RETENTION_DAYS, COIN_TOSS_RETENTION_DAYS);
    }
}
