package com.mannschaft.app.reservation.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * キャンセル待ち（waitlist）失効クリーンアップバッチ（F03.4.5 §6.1）。
 *
 * <p>枠の開始時刻を過ぎた WAITING エントリを物理削除する（「期限切れ」は行として持たず経過で導出・
 * 履歴価値なし）。{@code ReservationReminderDispatchBatchService} の作法に倣う。</p>
 *
 * <p><b>実行タイミング:</b> 日次 AM 0:45（生成バッチ AM0:15・リマインド 1 分間隔と負荷帯を分離・§12）。
 * 多重起動防止のため {@link SchedulerLock} を付ける。実削除は {@link ReservationWaitlistService#purgeExpiredWaiting}
 * に委譲し、注入 {@code Clock} 基準で判定する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationWaitlistCleanupBatchService {

    private final ReservationWaitlistService waitlistService;

    /**
     * 枠開始時刻を過ぎた WAITING エントリを 1 日 1 回物理削除する。
     */
    @BatchEndpoint(name = "reservation-waitlist-cleanup",
            description = "枠開始時刻を過ぎたキャンセル待ち(WAITING)を日次で物理削除する")
    @Scheduled(cron = "0 45 0 * * *")
    @SchedulerLock(name = "reservationWaitlistCleanupBatch", lockAtLeastFor = "30s", lockAtMostFor = "10m")
    public void cleanupExpiredWaiting() {
        int purged = waitlistService.purgeExpiredWaiting();
        log.info("キャンセル待ち失効クリーンアップバッチ: {}件削除", purged);
    }
}
