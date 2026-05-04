package com.mannschaft.app.shiftbudget.batch;

import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * F08.7 シフト予算 月次締めバッチジョブ（Phase 9-δ 第2段、Phase 10-β で closeAll 経路へ移行）。
 *
 * <p>設計書 F08.7 (v1.3) §4.6 / §6.1 #11 / §13 Phase 10-β に準拠。
 * 毎月 1 日 02:00 JST に起動し、前月分の生存 allocation を全組織横断で締める。</p>
 *
 * <p><strong>マスター御裁可 Q4</strong>:
 * 起動制御は {@code feature.shift-budget.monthly-close-cron-enabled} で切替可能。
 * デフォルトは false（手動 API #11 でのみ動かす運用が安全）。</p>
 *
 * <p>Phase 10-β: {@link MonthlyShiftBudgetCloseService#closeAll} 経由に変更。
 * 1 組織の失敗が他組織を止めない + 失敗イベントを {@code shift_budget_failed_events} に記録 +
 * 運用者は管理 API / API #11 で個別再実行できる。</p>
 *
 * <p>ShedLock により複数インスタンス環境でも 1 回だけ実行されることを保証する。
 * フィーチャーフラグ ({@code feature.shift-budget.enabled} の三値論理) は
 * {@link MonthlyShiftBudgetCloseService#closeFromBatch} 内で組織単位に再判定する
 * （バッチ全体で OFF でも、一部組織のみ ON のケースに対応する設計）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "feature.shift-budget", name = "monthly-close-cron-enabled",
        havingValue = "true")
public class MonthlyShiftBudgetCloseBatchJob {

    private final MonthlyShiftBudgetCloseService closeService;

    /**
     * 毎月 1 日 02:00 JST に実行する。
     * <p>Phase 10-β: {@code closeAll} を使用。組織毎の失敗は failed_events に記録されつつ続行する。</p>
     */
    @Scheduled(cron = "0 0 2 1 * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "MonthlyShiftBudgetCloseBatchJob",
            lockAtMostFor = "30m", lockAtLeastFor = "5m")
    public void run() {
        YearMonth targetMonth = YearMonth.now().minusMonths(1);
        log.info("F08.7 月次締め cron 起動: 対象月={}", targetMonth);

        MonthlyShiftBudgetCloseService.AllOrgsCloseResult result = closeService.closeAll(targetMonth);

        log.info("F08.7 月次締め cron 完了: 対象月={}, processedOrgs={}, failedOrgs={}, "
                        + "totalClosedAllocations={}, totalConsumptions={}",
                targetMonth, result.processedOrganizationIds().size(),
                result.failedOrganizationIds().size(),
                result.closedAllocations(), result.closedConsumptions());
    }
}
