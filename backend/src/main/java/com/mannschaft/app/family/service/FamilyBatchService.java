package com.mannschaft.app.family.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.family.entity.PresenceEventEntity;
import com.mannschaft.app.family.repository.CoinTossResultRepository;
import com.mannschaft.app.family.repository.PresenceEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_FAMILY_CARE_ENABLED",
            reason = "帰宅遅延の判定は presence_events の時刻から何度でも再計算でき、止めても元のプレゼンス記録は壊れない")
    @BatchEndpoint(name = "family-overdue-event-check", description = "ファミリーの帰宅遅延イベントを 15 分毎にチェックする")
    @Scheduled(fixedRate = 15 * 60 * 1000)
    // 起動間隔は 15 分（fixedRate）。処理は帰宅遅延イベントの検出と通知で通常は数秒。間隔の 3 倍を上限とする。
    @SchedulerLock(name = "familyOverdueEventCheck", lockAtLeastFor = "PT30S", lockAtMostFor = "PT45M")
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

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_FAMILY_CARE_ENABLED",
            reason = "止まるのは記念日通知のみで DB は一切書き換わらず、ファミリー機能を閉じている間は通知先の画面自体が閉じている")
    @BatchEndpoint(name = "family-anniversary-notify-daily", description = "ファミリーの記念日通知を毎日 09:00 に送信する")
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Tokyo")
    // 起動間隔は日次 09:00。当日該当の記念日通知のみで最悪ケースでも数分。通知先集中を見込み 30 分を上限とする。
    @SchedulerLock(name = "familyAnniversaryNotifyDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void checkAnniversaries() {
        log.info("記念日通知バッチを実行しました");
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると保持期間を超えたプレゼンス（所在）履歴とコイントス履歴が削除されず、保持期限を超えた個人データが残留する。DisclosureAutoDeleteBatchService と同種の保持期間超過削除である")
    @BatchEndpoint(name = "family-presence-cleanup-daily", description = "プレゼンス・コイントス履歴の保持期間超過を毎日 04:00 にクリーンアップする")
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tokyo")
    // 起動間隔は日次 04:00。保持期間を超えたプレゼンス・コイントス履歴の一括 DELETE のみで最悪ケースでも数分。
    // 初回実行時の積み残しを見込み 30 分を上限とする。
    @SchedulerLock(name = "familyPresenceCleanupDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    @Transactional
    public void cleanupOldRecords() {
        LocalDateTime presenceThreshold = LocalDateTime.now().minusDays(PRESENCE_RETENTION_DAYS);
        presenceEventRepository.deleteByCreatedAtBefore(presenceThreshold);
        LocalDateTime coinTossThreshold = LocalDateTime.now().minusDays(COIN_TOSS_RETENTION_DAYS);
        coinTossResultRepository.deleteByCreatedAtBefore(coinTossThreshold);
        log.info("クリーンアップバッチ完了: プレゼンス({}日以前), コイントス({}日以前)", PRESENCE_RETENTION_DAYS, COIN_TOSS_RETENTION_DAYS);
    }
}
