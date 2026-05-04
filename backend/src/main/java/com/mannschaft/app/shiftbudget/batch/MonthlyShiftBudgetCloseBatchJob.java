package com.mannschaft.app.shiftbudget.batch;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;

/**
 * F08.7 シフト予算 月次締めバッチジョブ（Phase 9-δ 第2段）。
 *
 * <p>設計書 F08.7 (v1.2) §4.6 / §6.1 #11 に準拠。
 * 毎月 1 日 02:00 JST に起動し、前月分の生存 allocation を全組織横断で締める。</p>
 *
 * <p><strong>マスター御裁可 Q4</strong>:
 * 起動制御は {@code feature.shift-budget.monthly-close-cron-enabled} で切替可能。
 * デフォルトは false（手動 API #11 でのみ動かす運用が安全）。
 * 本番で cron 起動を有効化したい場合は環境変数
 * {@code FEATURE_SHIFT_BUDGET_MONTHLY_CRON_ENABLED=true} を設定する。</p>
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
    private final OrganizationRepository organizationRepository;

    /**
     * 毎月 1 日 02:00 JST に実行する。
     * <p>ShedLock により複数インスタンス環境で重複実行されない。
     * 1 回の実行で 30 分以上かかる場合は他インスタンスが起動してしまう（運用想定外、
     * 1 組織あたり数秒〜数十秒の処理を見込む）。</p>
     */
    @Scheduled(cron = "0 0 2 1 * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "MonthlyShiftBudgetCloseBatchJob",
            lockAtMostFor = "30m", lockAtLeastFor = "5m")
    public void run() {
        YearMonth targetMonth = YearMonth.now().minusMonths(1);
        log.info("F08.7 月次締め cron 起動: 対象月={}", targetMonth);

        List<OrganizationEntity> organizations = organizationRepository.findAll();
        int closedAllocationsTotal = 0;
        int closedConsumptionsTotal = 0;
        int errorCount = 0;
        for (OrganizationEntity org : organizations) {
            try {
                MonthlyShiftBudgetCloseService.CloseResult result =
                        closeService.closeFromBatch(org.getId(), targetMonth);
                closedAllocationsTotal += result.closedAllocations();
                closedConsumptionsTotal += result.closedConsumptions();
            } catch (Exception e) {
                // 1 組織の失敗で全体を止めない（エラーログのみ、次組織継続）
                errorCount++;
                log.error("F08.7 月次締め cron: 組織単位エラー: orgId={}, month={}",
                        org.getId(), targetMonth, e);
            }
        }

        log.info("F08.7 月次締め cron 完了: 対象月={}, totalClosedAllocations={}, totalConsumptions={}, errorOrgs={}",
                targetMonth, closedAllocationsTotal, closedConsumptionsTotal, errorCount);
    }
}
