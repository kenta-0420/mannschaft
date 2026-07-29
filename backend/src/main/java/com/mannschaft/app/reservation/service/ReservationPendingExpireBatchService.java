package com.mannschaft.app.reservation.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.reservation.service.ReservationPendingExpireService.PendingExpireUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 仮押さえ(PENDING)自動失効バッチ（F03.4.5 §6.3・W2-6）。
 *
 * <p>MANUAL 承認チームで承認されないまま放置された PENDING が予約枠を塞ぎ続ける問題への対処。
 * チーム設定 {@code reservation_policies.pending_expire_hours} に従って期限切れの仮押さえを
 * {@code CANCELLED}（{@code cancelledBy=SYSTEM}）にし、枠を復帰させ、申込者へ通知する。</p>
 *
 * <p><b>実行タイミング:</b> {@code @Scheduled(fixedDelay = 300_000)}（5 分間隔。リマインド送出の
 * 1 分間隔と負荷帯を分離する・§6.3）。多重起動防止のため {@link SchedulerLock} を付ける
 * （{@link ReservationWaitlistCleanupBatchService} / {@link ReservationReminderDispatchBatchService}
 * の作法に倣う）。</p>
 *
 * <p><b>役割分担:</b> 本クラスはスケジュール宣言と「単位ごとに回す・1 件の失敗を握って続行する」
 * 制御だけを持ち、対象抽出と実失効は {@link ReservationPendingExpireService} に委譲する
 * （{@link ReservationWaitlistCleanupBatchService} と同じ薄さ）。<b>本クラスには
 * {@code @Transactional} を付けない</b> — 全体を 1 tx で囲むと内側の失敗が participating tx を
 * rollback-only にマークし、行単位 try/catch が実質無効化されるため（委譲先 Javadoc 参照）。</p>
 *
 * <p><b>枠復帰とキャンセル待ちの連鎖:</b> 枠復帰は {@code ReservationSlotService.decrementAndReopen}
 * を必ず経由する。DB が実際に FULL→AVAILABLE 遷移を起こしたときのみ
 * {@code ReservationSlotReopenedEvent} が発行され、{@code ReservationWaitlistNotificationEventListener}
 * が AFTER_COMMIT で購読してキャンセル待ち全員へ通知する。<b>独自にイベントを発行しない</b>（§6.1 統合点）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationPendingExpireBatchService {

    private final ReservationPendingExpireService pendingExpireService;

    /**
     * 期限切れの仮押さえ(PENDING)を自動キャンセルする。
     *
     * <p>1 単位（単枠予約 1 件 / グループ 1 組）の失敗が他の単位を巻き込まないよう単位ごとに
     * try/catch する。失敗は握り潰さず {@code log.error} で記録し、次回起動で再試行される
     * （失効条件は時刻経過なので、失敗した単位は次回も対象に残る＝自己修復する）。</p>
     *
     * @return 失効させた予約行数（グループは構成行数を合算）
     */
    @BatchEndpoint(name = "reservation-pending-expire",
            description = "承認されないまま期限切れになった仮押さえ(PENDING)を5分毎に自動キャンセルして枠を復帰させる")
    @Scheduled(fixedDelay = 300_000)
    @SchedulerLock(name = "reservationPendingExpireBatch", lockAtLeastFor = "30s", lockAtMostFor = "5m")
    public int expirePendingReservations() {
        List<PendingExpireUnit> units = pendingExpireService.findExpirableUnits();
        if (units.isEmpty()) {
            return 0;
        }
        int expiredRows = 0;
        int failedUnits = 0;
        for (PendingExpireUnit unit : units) {
            try {
                expiredRows += pendingExpireService.expireUnit(unit);
            } catch (Exception e) {
                failedUnits++;
                log.error("仮押さえ自動失効に失敗（次回起動で再試行）: reservationId={}, teamId={}",
                        unit.primary().getId(), unit.primary().getTeamId(), e);
            }
        }
        log.info("仮押さえ自動失効バッチ: 対象{}単位中 {}行を失効、{}単位が失敗",
                units.size(), expiredRows, failedUnits);
        return expiredRows;
    }
}
