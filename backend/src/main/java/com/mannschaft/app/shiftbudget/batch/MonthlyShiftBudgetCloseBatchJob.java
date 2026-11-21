package com.mannschaft.app.shiftbudget.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseCoordinator;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetBusinessDateProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * F08.7 シフト予算 日次締めバッチジョブ（Phase 9-δ 第2段、Phase 10-β で全組織横断、CMP-260909-2152 で毎日・期間締めへ作り替え）。
 *
 * <p>毎日 02:00 Asia/Tokyo に起動し、前日までに終了した締め期間を、候補割当を持つ組織だけ組織ID昇順に、組織あたり最大36期間キーを
 * 古い順に締める（割当ID昇順・冪等。時間窓は設けず、停止・長期滞留も回収する。残りは翌日継続）。1 組織の失敗が他組織を止めず、
 * 失敗は {@code shift_budget_failed_events}（MONTHLY_CLOSE）に記録される。機能フラグ OFF の組織は候補から外す。
 * 締め後の遅延記録の回収は、本 cron の有効化と独立に {@link ShiftBudgetDeferralRecoveryBatchJob}（15分毎・常時）でも行う。</p>
 *
 * <p>起動制御は {@code feature.shift-budget.monthly-close-cron-enabled} で切替可能（既定は false。手動 API #11 でのみ動かす運用が
 * 安全）。ShedLock により複数インスタンス環境でも 1 回だけ実行される。デプロイ中は cron を止め、旧インスタンスを残さない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "feature.shift-budget", name = "monthly-close-cron-enabled",
        havingValue = "true")
public class MonthlyShiftBudgetCloseBatchJob {

    private final MonthlyShiftBudgetCloseCoordinator coordinator;
    private final ShiftBudgetBusinessDateProvider dateProvider;

    /** 毎日 02:00 JST に実行する。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_SHIFT_ENABLED",
            reason = "殿の裁定: 既定では ConditionalOnProperty により Bean 自体が生成されず、締め処理は BatchEndpoint 経由の手動再実行経路を持つため一回性は障害にならない")
    @BatchEndpoint(name = "shiftbudget-monthly-close", description = "前日までに終了した締め期間のシフト予算 allocation を毎日 02:00 に全組織横断で締める")
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "MonthlyShiftBudgetCloseBatchJob",
            lockAtMostFor = "30m", lockAtLeastFor = "5m")
    public void run() {
        var businessDate = dateProvider.today();
        log.info("F08.7 期間締め cron 起動: 業務日={}", businessDate);

        MonthlyShiftBudgetCloseCoordinator.DailyCloseResult result = coordinator.closeAll(businessDate);
        int recovered = coordinator.recoverDeferrals();

        log.info("F08.7 期間締め cron 完了: 業務日={}, processedOrgs={}, closedKeys={}, closedAllocations={}, "
                        + "failedAllocations={}, recoveredDeferrals={}",
                businessDate, result.processedOrganizationIds().size(), result.closedPeriodKeys(),
                result.closedAllocations(), result.failedAllocations(), recovered);
    }
}
