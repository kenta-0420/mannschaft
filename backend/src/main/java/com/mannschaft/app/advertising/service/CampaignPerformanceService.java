package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.dto.AdvertiserOverviewResponse;
import com.mannschaft.app.advertising.dto.BreakdownResponse;
import com.mannschaft.app.advertising.dto.CampaignPerformanceResponse;
import com.mannschaft.app.advertising.dto.CreativeComparisonResponse;
import com.mannschaft.app.advertising.entity.AdCampaignEntity;
import com.mannschaft.app.advertising.entity.AdDailyStatsEntity;
import com.mannschaft.app.advertising.entity.AdEntity;
import com.mannschaft.app.advertising.entity.AdTargetingRuleEntity;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.repository.AdCampaignRepository;
import com.mannschaft.app.advertising.repository.AdDailyStatsRepository;
import com.mannschaft.app.advertising.repository.AdEntityRepository;
import com.mannschaft.app.advertising.repository.AdTargetingRuleRepository;
import com.mannschaft.app.advertising.repository.AdConversionRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CampaignPerformanceService {

    private final AdCampaignRepository adCampaignRepository;
    private final AdDailyStatsRepository adDailyStatsRepository;
    private final AdEntityRepository adEntityRepository;
    private final AdTargetingRuleRepository adTargetingRuleRepository;
    private final AdConversionRepository adConversionRepository;
    private final AdvertiserAccountRepository advertiserAccountRepository;

    /**
     * 広告主ダッシュボード概要を取得する。
     * 当月分の全キャンペーンのad_daily_statsを集計して返す。
     */
    public AdvertiserOverviewResponse getOverview(Long organizationId) {
        AdvertiserAccountEntity account = advertiserAccountRepository.findByOrganizationId(organizationId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_005));

        List<AdCampaignEntity> campaigns = adCampaignRepository.findByAdvertiserOrganizationId(organizationId);

        LocalDate now = LocalDate.now();
        LocalDate monthStart = now.withDayOfMonth(1);
        var period = new AdvertiserOverviewResponse.Period(monthStart, now);

        if (campaigns.isEmpty()) {
            return new AdvertiserOverviewResponse(
                    period, 0, 0, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, account.getCreditLimit(),
                    List.of()
            );
        }

        List<Long> campaignIds = campaigns.stream().map(AdCampaignEntity::getId).toList();
        List<AdDailyStatsEntity> allStats = adDailyStatsRepository.findByCampaignIdsAndDateBetween(
                campaignIds, monthStart, now);

        // キャンペーンID別に統計を分類
        Map<Long, List<AdDailyStatsEntity>> statsByCampaign = allStats.stream()
                .collect(Collectors.groupingBy(AdDailyStatsEntity::getCampaignId));

        // 全体集計
        long totalImpressions = allStats.stream().mapToLong(AdDailyStatsEntity::getImpressions).sum();
        long totalClicks = allStats.stream().mapToLong(AdDailyStatsEntity::getClicks).sum();
        BigDecimal totalCost = allStats.stream()
                .map(AdDailyStatsEntity::getCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgCtr = calcCtr(totalImpressions, totalClicks);

        int totalCampaignCount = campaigns.size();
        int activeCampaignCount = (int) campaigns.stream()
                .filter(c -> c.getStatus() == AdCampaignEntity.CampaignStatus.ACTIVE)
                .count();

        // 月次予算消化率: totalCost / creditLimit * 100
        BigDecimal creditLimit = account.getCreditLimit();
        BigDecimal monthlyBudgetUsedPct = BigDecimal.ZERO;
        if (creditLimit.compareTo(BigDecimal.ZERO) > 0) {
            monthlyBudgetUsedPct = totalCost.multiply(BigDecimal.valueOf(100))
                    .divide(creditLimit, 2, RoundingMode.HALF_UP);
        }

        // キャンペーン別サマリー
        Map<Long, AdCampaignEntity> campaignMap = campaigns.stream()
                .collect(Collectors.toMap(AdCampaignEntity::getId, c -> c));

        List<AdvertiserOverviewResponse.CampaignSummary> campaignSummaries = campaignIds.stream()
                .map(cid -> {
                    AdCampaignEntity campaign = campaignMap.get(cid);
                    List<AdDailyStatsEntity> cStats = statsByCampaign.getOrDefault(cid, List.of());
                    long imp = cStats.stream().mapToLong(AdDailyStatsEntity::getImpressions).sum();
                    long clk = cStats.stream().mapToLong(AdDailyStatsEntity::getClicks).sum();
                    BigDecimal cost = cStats.stream()
                            .map(AdDailyStatsEntity::getCost)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal ctr = calcCtr(imp, clk);
                    return new AdvertiserOverviewResponse.CampaignSummary(
                            cid, campaign.getName(), campaign.getStatus().name(),
                            imp, clk, ctr, cost
                    );
                })
                .toList();

        return new AdvertiserOverviewResponse(
                period, totalCampaignCount, activeCampaignCount,
                totalImpressions, totalClicks,
                avgCtr, totalCost, monthlyBudgetUsedPct, creditLimit,
                campaignSummaries
        );
    }

    /**
     * キャンペーン別パフォーマンスを取得する。
     */
    public CampaignPerformanceResponse getPerformance(Long campaignId, Long organizationId,
                                                       LocalDate from, LocalDate to) {
        AdCampaignEntity campaign = findCampaignWithAuth(campaignId, organizationId);
        List<AdDailyStatsEntity> stats = adDailyStatsRepository.findByCampaignIdAndDateBetween(campaignId, from, to);

        long totalImpressions = stats.stream().mapToLong(AdDailyStatsEntity::getImpressions).sum();
        long totalClicks = stats.stream().mapToLong(AdDailyStatsEntity::getClicks).sum();
        BigDecimal totalCost = stats.stream().map(AdDailyStatsEntity::getCost).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avgCtr = calcCtr(totalImpressions, totalClicks);
        BigDecimal avgCpm = totalImpressions > 0
                ? totalCost.multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(totalImpressions), 2, RoundingMode.HALF_UP)
                : null;
        BigDecimal avgCpc = totalClicks > 0
                ? totalCost.divide(BigDecimal.valueOf(totalClicks), 2, RoundingMode.HALF_UP)
                : null;

        // コンバージョン
        long conversions = adConversionRepository.countByCampaignId(campaignId);
        BigDecimal conversionRate = totalClicks > 0
                ? BigDecimal.valueOf(conversions).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(totalClicks), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal costPerConversion = conversions > 0
                ? totalCost.divide(BigDecimal.valueOf(conversions), 2, RoundingMode.HALF_UP)
                : null;

        var summary = new CampaignPerformanceResponse.PerformanceSummary(
                totalImpressions, totalClicks, avgCtr, totalCost, avgCpm, avgCpc,
                conversions, conversionRate, costPerConversion);

        // ベンチマーク
        var benchmark = buildBenchmark(campaign, totalImpressions, totalClicks, from, to);

        // 日次ポイント
        Map<LocalDate, List<AdDailyStatsEntity>> byDate = stats.stream()
                .collect(Collectors.groupingBy(AdDailyStatsEntity::getDate));
        List<CampaignPerformanceResponse.PerformancePoint> points = byDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    long imp = entry.getValue().stream().mapToLong(AdDailyStatsEntity::getImpressions).sum();
                    long clk = entry.getValue().stream().mapToLong(AdDailyStatsEntity::getClicks).sum();
                    BigDecimal cost = entry.getValue().stream().map(AdDailyStatsEntity::getCost).reduce(BigDecimal.ZERO, BigDecimal::add);
                    return new CampaignPerformanceResponse.PerformancePoint(
                            entry.getKey().toString(), imp, clk, calcCtr(imp, clk), cost, null);
                })
                .toList();

        return new CampaignPerformanceResponse(
                campaignId, campaign.getName(), campaign.getStatus().name(),
                campaign.getPricingModel().name(), summary, benchmark, points);
    }

    /**
     * クリエイティブ別比較を取得する。
     */
    public CreativeComparisonResponse getCreativeComparison(Long campaignId, Long organizationId,
                                                             LocalDate from, LocalDate to) {
        findCampaignWithAuth(campaignId, organizationId);
        List<AdEntity> ads = adEntityRepository.findByCampaignId(campaignId);

        List<CreativeComparisonResponse.CreativeStats> creatives = ads.stream().map(ad -> {
            List<AdDailyStatsEntity> stats = adDailyStatsRepository.findByAdIdAndDateBetween(ad.getId(), from, to);
            long imp = stats.stream().mapToLong(AdDailyStatsEntity::getImpressions).sum();
            long clk = stats.stream().mapToLong(AdDailyStatsEntity::getClicks).sum();
            BigDecimal cost = stats.stream().map(AdDailyStatsEntity::getCost).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new CreativeComparisonResponse.CreativeStats(
                    ad.getId(), ad.getTitle(), imp, clk, calcCtr(imp, clk), cost, null);
        }).sorted(Comparator.comparing(CreativeComparisonResponse.CreativeStats::ctr, Comparator.reverseOrder())).toList();

        // A/B テスト判定（簡易版: CTR差が有意かの判定）
        CreativeComparisonResponse.Winner winner = null;
        if (creatives.size() >= 2) {
            var top = creatives.get(0);
            var second = creatives.get(1);
            if (top.impressions() >= 1000 && second.impressions() >= 1000
                    && second.ctr() != null && second.ctr().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal diff = top.ctr().subtract(second.ctr())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(second.ctr(), 1, RoundingMode.HALF_UP);
                // カイ二乗検定の簡易代替: CTR差が20%以上ある場合にwinnerとする
                if (diff.compareTo(BigDecimal.valueOf(20)) >= 0) {
                    winner = new CreativeComparisonResponse.Winner(
                            top.adId(),
                            "CTR が " + diff.setScale(1, RoundingMode.HALF_UP) + "% 高い");
                }
            }
        }

        return new CreativeComparisonResponse(campaignId, creatives, winner);
    }

    /**
     * 地域×テンプレート別内訳を取得する。
     */
    public BreakdownResponse getBreakdown(Long campaignId, Long organizationId,
                                           LocalDate from, LocalDate to, String breakdownBy) {
        findCampaignWithAuth(campaignId, organizationId);
        List<AdTargetingRuleEntity> rules = adTargetingRuleRepository.findByCampaignId(campaignId);
        List<AdDailyStatsEntity> stats = adDailyStatsRepository.findByCampaignIdAndDateBetween(campaignId, from, to);

        // 全体集計をitem 1件として返す（地域別の詳細分解はad_daily_statsにprefecture/templateカラムが追加されるまでの暫定）
        long totalImp = stats.stream().mapToLong(AdDailyStatsEntity::getImpressions).sum();
        long totalClk = stats.stream().mapToLong(AdDailyStatsEntity::getClicks).sum();
        BigDecimal totalCost = stats.stream().map(AdDailyStatsEntity::getCost).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<BreakdownResponse.BreakdownItem> items = new ArrayList<>();
        if (!rules.isEmpty()) {
            for (AdTargetingRuleEntity rule : rules) {
                items.add(new BreakdownResponse.BreakdownItem(
                        rule.getTargetPrefecture(), rule.getTargetTemplate(),
                        totalImp, totalClk, calcCtr(totalImp, totalClk), totalCost, null));
            }
        } else {
            items.add(new BreakdownResponse.BreakdownItem(
                    null, null, totalImp, totalClk, calcCtr(totalImp, totalClk), totalCost, null));
        }

        return new BreakdownResponse(campaignId, breakdownBy != null ? breakdownBy : "REGION", items);
    }

    private AdCampaignEntity findCampaignWithAuth(Long campaignId, Long organizationId) {
        AdCampaignEntity campaign = adCampaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_005));
        if (!campaign.getAdvertiserOrganizationId().equals(organizationId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return campaign;
    }

    private CampaignPerformanceResponse.BenchmarkData buildBenchmark(
            AdCampaignEntity campaign, long myImpressions, long myClicks,
            LocalDate from, LocalDate to) {
        if (myImpressions == 0) return null;
        BigDecimal myCtr = calcCtr(myImpressions, myClicks);

        // プラットフォーム全体の平均CTR
        List<AdDailyStatsEntity> allStats = adDailyStatsRepository.findByDateBetween(from, to);
        long allImpressions = allStats.stream().mapToLong(AdDailyStatsEntity::getImpressions).sum();
        long allClicks = allStats.stream().mapToLong(AdDailyStatsEntity::getClicks).sum();
        BigDecimal platformAvgCtr = calcCtr(allImpressions, allClicks);

        // パーセンタイル算出
        Map<Long, List<AdDailyStatsEntity>> byCampaign = allStats.stream()
                .collect(Collectors.groupingBy(AdDailyStatsEntity::getCampaignId));
        List<BigDecimal> allCtrs = byCampaign.values().stream()
                .map(s -> {
                    long imp = s.stream().mapToLong(AdDailyStatsEntity::getImpressions).sum();
                    long clk = s.stream().mapToLong(AdDailyStatsEntity::getClicks).sum();
                    return calcCtr(imp, clk);
                })
                .filter(ctr -> ctr != null)
                .sorted()
                .toList();
        int rank = 0;
        for (BigDecimal ctr : allCtrs) {
            if (myCtr.compareTo(ctr) >= 0) rank++;
        }
        Integer percentile = !allCtrs.isEmpty() ? rank * 100 / allCtrs.size() : null;

        return new CampaignPerformanceResponse.BenchmarkData(platformAvgCtr, percentile, null, null);
    }

    private BigDecimal calcCtr(long impressions, long clicks) {
        if (impressions == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(clicks)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(impressions), 2, RoundingMode.HALF_UP);
    }
}
