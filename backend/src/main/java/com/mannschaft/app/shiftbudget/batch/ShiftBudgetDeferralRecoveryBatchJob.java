package com.mannschaft.app.shiftbudget.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * F08.7 遅延記録（{@code shift_budget_record_deferrals}）の回収バッチ（CMP-260909-2152 再検分 P2-3）。
 *
 * <p>締めの PREPARED を理由に保留したシフトの記録・取消は、Commit／Abort 直後に段取り役が再実行する。その取りこぼし
 * （再実行前の停止・リース切れ）を 15 分ごとに回収する。日次締め cron（{@code monthly-close-cron-enabled}）・失敗イベント
 * 再試行バッチ（{@code retry-batch-enabled}）はいずれも既定 false なので、それらの有効化には依存させず常に起動する
 * （手動締め運用でも PENDING が残り続けない）。ShedLock により複数インスタンスでも 1 回だけ実行される。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftBudgetDeferralRecoveryBatchJob {

    private final MonthlyShiftBudgetCloseCoordinator coordinator;

    /** 15 分ごとに実行する。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "遅延記録は記録・取消の対の保留分であり、止めると予算残高が壊れたまま残る（記録・取消のリスナーと同じく常時実行）")
    @BatchEndpoint(name = "shiftbudget-deferral-recovery", description = "締めの PREPARED で保留したシフト予算の記録・取消を 15 分毎に回収する")
    @Scheduled(cron = "0 5/15 * * * ?", zone = "Asia/Tokyo")
    @SchedulerLock(name = "ShiftBudgetDeferralRecoveryBatchJob",
            lockAtMostFor = "30m", lockAtLeastFor = "1m")
    public void run() {
        int recovered = coordinator.recoverDeferrals();
        if (recovered > 0) {
            log.info("F08.7 遅延記録の回収: recovered={}", recovered);
        }
    }
}
