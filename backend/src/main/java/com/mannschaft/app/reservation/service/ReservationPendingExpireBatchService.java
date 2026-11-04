package com.mannschaft.app.reservation.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.timezone.TeamTimezoneResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;


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
    private final ReservationPendingExpireProgressService progress;
    private final TeamTimezoneResolver timezoneResolver;

    /**
     * 期限切れの仮押さえ(PENDING)を自動キャンセルする。
     *
     * <p>1 単位（単枠予約 1 件 / グループ 1 組）の失敗が他の単位を巻き込まないよう単位ごとに
     * try/catch する。失敗は {@code log.error} で記録し、永続retryに残して次回起動で再試行する。
     * checkpointの保存に失敗した場合は処理を停止し、未検査のprefixを進めない。</p>
     *
     * <p>戻り値はプリミティブ {@code int} ではなく参照型 {@code Integer} にしている（issue #2724）。
     * ShedLock はプリミティブ戻り値のメソッドをロックできず、{@code int} を返していた旧実装は
     * {@code LockingNotSupportedException} で {@code @Scheduled} 実行のたびに必ず失敗していた
     * （番人 {@code ScheduledBatchGuardTest} ルール5が再発を機械的に防ぐ）。参照型なら ShedLock は
     * 問題なくロックできる。件数は可観測性のため {@code log.info} でも出す。
     *
     * @return 失効させた予約行数（グループは構成行数を合算）。ShedLock がロックを取得できず
     *     処理をスキップした場合は {@code null} になりうる
     */
    @BatchEndpoint(name = "reservation-pending-expire",
            description = "承認されないまま期限切れになった仮押さえ(PENDING)を5分毎に自動キャンセルして枠を復帰させる")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。仮予約の期限切れ処理であり、再開後に同じ条件で拾い直せる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Scheduled(fixedDelay = 300_000)
    // lockAtMostFor は fixedDelay（5分）と同値にしない。同値だと 1 回の実行が 5 分を超えた瞬間に
    // ロックが失効し、次回起動と二重処理になる。ReservationEntity は @Version を持たないため、
    // 二重処理は定員 2 以上の枠で booked_count を余分に減らす（空きが 1 多く出る）。
    // 1 回あたりの処理量は ReservationPendingExpireService.MAX_UNITS_PER_RUN で上限化しつつ、
    // ロック保持時間にも余裕（3 倍）を持たせて窓を閉じる（殿の裁定・2026-07-29）。
    @SchedulerLock(name = "reservationPendingExpireBatch", lockAtLeastFor = "30s", lockAtMostFor = "15m")
    public Integer expirePendingReservations() {
        var run = progress.beginRun();
        var plan = pendingExpireService.findScanPlan(run);
        long cursor = run.cursor();
        int expiredRows = 0;
        int failedUnits = 0;
        int attempts = 0;
        boolean stoppedAtUnprocessedTrue = false;
        for (var entry : plan.entries()) {
            if (!entry.eligible()) {
                if (entry.retry()) progress.discardRetry(run.epoch(), entry.primaryId());
                else cursor = entry.primaryId();
                continue;
            }
            if (entry.unit() == null || attempts == ReservationPendingExpireService.MAX_UNITS_PER_RUN) {
                stoppedAtUnprocessedTrue = true;
                break;
            }
            // false prefixを越す前に耐久記録。enqueue/epoch/checkpoint失敗は当回を停止する。
            progress.checkpoint(run.epoch(), cursor);
            if (!entry.retry()) progress.enqueue(run.epoch(), entry.primaryId());
            long completedPrefix = entry.retry() ? cursor : entry.primaryId();
            try {
                var zone = timezoneResolver.resolveZone(entry.unit().primary().getTeamId());
                attempts++;
                expiredRows += pendingExpireService.expireUnit(entry.unit().withProgress(run.epoch(), completedPrefix, zone));
            } catch (Exception e) {
                failedUnits++;
                log.error("仮押さえ自動失効に失敗（次回起動で再試行）: reservationId={}, teamId={}",
                        entry.primaryId(), entry.unit().primary().getTeamId(), e);
                // 実unitはrollback済み。失敗IDを消さず検査済みprefixだけ進める。
                progress.checkpointFailure(run.epoch(), entry.primaryId(), completedPrefix);
            }
            if (!entry.retry()) cursor = completedPrefix;
        }
        if (!stoppedAtUnprocessedTrue) cursor = plan.scannedThrough();
        progress.checkpoint(run.epoch(), cursor);
        if (!stoppedAtUnprocessedTrue && plan.exhausted()) progress.finishCycle(run.epoch());
        log.info("仮押さえ自動失効バッチ: {}単位を試行、{}行を失効、{}単位が失敗",
                attempts, expiredRows, failedUnits);
        return expiredRows;
    }
}
