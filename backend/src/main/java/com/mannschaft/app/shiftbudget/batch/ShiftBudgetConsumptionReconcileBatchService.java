package com.mannschaft.app.shiftbudget.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetConsumptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CMP-260909-1445 — シフト予算消化の整合バッチ（孤児 PLANNED 消化の収束）。
 *
 * <h2>何を直すバッチか</h2>
 * <p>「シフトが ARCHIVED もしくは論理削除済みなのに PLANNED 消化が残っている」行を孤児とみなし、
 * {@code ShiftBudgetConsumptionService#cancelAllForShift} と<b>同じ経路</b>で CANCELLED 化して
 * {@code allocation.consumed_amount} を減算する。</p>
 *
 * <p><b>原因系に依らず状態が収束することが眼目である</b>（殿の御裁可）。この一本で以下がすべて救われる:</p>
 * <ul>
 *   <li>是正前に手動アーカイブ・論理削除で取り残された既存の消化</li>
 *   <li>{@code ShiftBudgetConsumptionCancelListener} が失敗した場合の取りこぼし
 *       （{@code @Async} + {@code AFTER_COMMIT} ゆえ例外は握られてログに残るだけで、
 *       業務トランザクションは既にコミット済み）</li>
 *   <li>将来「シフトを閉じる」経路が新設され、イベント発行を書き忘れた場合</li>
 * </ul>
 *
 * <p>Flyway で直接 UPDATE しないのは、消化行の {@code status} 更新と
 * {@code allocation.consumed_amount} の減算が対になっており、SQL に同じ計算を二重実装すること
 * になるうえ監査ログも残らないため（殿の御裁可）。</p>
 *
 * <h2>手動起動口</h2>
 * <p>{@link BatchEndpoint} を付与しているため、既存の汎用バッチキック API
 * {@code POST /api/v1/system-admin/batch/shift-budget-consumption-reconcile/trigger}
 * （SYSTEM_ADMIN 限定）から今すぐ撃てる。専用エンドポイントを新設せず既存の型に倣うことで、
 * 認可・実行ログ・二重起動防御（ShedLock 事前判定）をそのまま享受する。</p>
 *
 * <h2>トランザクション境界</h2>
 * <p>本メソッドに {@code @Transactional} は付けない。{@code cancelAllForShift} が
 * {@code REQUIRES_NEW} で 1 シフトずつ独立にコミットするため、1 件の失敗が他シフトの
 * 修復を巻き戻さない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftBudgetConsumptionReconcileBatchService {

    /** 1 回の実行で扱うシフト件数の上限。残りは次回実行が拾う（バッチは冪等）。 */
    static final int BATCH_SIZE = 200;

    private final ShiftBudgetConsumptionRepository consumptionRepository;
    private final ShiftBudgetConsumptionService consumptionService;
    private final ShiftBudgetRateQueryRepository rateQueryRepository;
    private final AuditLogService auditLogService;

    /**
     * 毎日 AM 3:30（JST）に実行。自動アーカイブバッチ（03:00）の直後に置き、
     * 同じ夜のうちに取りこぼしが収束するようにしてある。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "予算残高の整合を回復するバッチであり、止めると割当が恒久的に削除不能なまま残る。検知・修復のみで再開後に同じ孤児条件で拾い直せるため常時実行とする")
    @BatchEndpoint(name = "shift-budget-consumption-reconcile",
            description = "ARCHIVED / 論理削除済みシフトに残った PLANNED 消化を取り消して予算残高を整合させる")
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "shift_budget_consumption_reconcile",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runReconcile() {
        reconcileOrphanConsumptions();
    }

    /**
     * 孤児 PLANNED 消化を取り消す本体。
     *
     * <p>{@link BatchEndpoint} 付与メソッドは戻り値を運用画面へ返せないため、件数を返す本体を
     * 分離してテストと運用ログの双方から実数を確認できるようにしている。</p>
     *
     * @return 取り消した消化レコードの件数（0 なら整合済み）
     */
    public int reconcileOrphanConsumptions() {
        List<ShiftBudgetConsumptionRepository.OrphanConsumptionShiftRow> orphans =
                consumptionRepository.findShiftsWithOrphanPlannedConsumptions(BATCH_SIZE);
        if (orphans.isEmpty()) {
            log.info("シフト予算消化 整合バッチ: 孤児 PLANNED 消化なし（整合 OK）");
            return 0;
        }

        log.warn("シフト予算消化 整合バッチ: 孤児 PLANNED 消化を検出。対象シフト数={}", orphans.size());
        int cancelledTotal = 0;
        int failedShifts = 0;

        for (ShiftBudgetConsumptionRepository.OrphanConsumptionShiftRow orphan : orphans) {
            Long shiftId = orphan.getShiftId();
            Long teamId = orphan.getTeamId();
            try {
                int cancelled = consumptionService.cancelAllForShift(shiftId);
                if (cancelled == 0) {
                    // 直前に別経路（リスナー・並行実行）が取り消し済み。競合であって異常ではない。
                    log.info("シフト予算消化 整合バッチ: 取消対象なし（他経路が先に収束済み）: shiftId={}", shiftId);
                    continue;
                }
                cancelledTotal += cancelled;
                Long organizationId = rateQueryRepository.findOrganizationIdByTeamId(teamId).orElse(null);
                auditLogService.record(
                        "SHIFT_BUDGET_CONSUMPTION_CANCELLED",
                        null, null,
                        teamId, organizationId,
                        null, null, null,
                        String.format("{\"shift_schedule_id\":%d,\"cancelled_count\":%d,\"source\":\"RECONCILE_BATCH\"}",
                                shiftId, cancelled));
                log.warn("シフト予算消化 整合バッチ: 孤児消化を取消: shiftId={}, teamId={}, 件数={}",
                        shiftId, teamId, cancelled);
            } catch (Exception e) {
                // 1 シフトの失敗で他シフトの修復を止めない（cancelAllForShift は REQUIRES_NEW）。
                // 握りつぶしではなく、次回実行が同じ孤児条件で必ず拾い直す設計である。
                failedShifts++;
                log.error("シフト予算消化 整合バッチ: 取消失敗（次回実行で再試行）: shiftId={}", shiftId, e);
            }
        }

        log.warn("シフト予算消化 整合バッチ完了: 対象シフト={}, 取消消化={}, 失敗シフト={}",
                orphans.size(), cancelledTotal, failedShifts);
        return cancelledTotal;
    }
}
