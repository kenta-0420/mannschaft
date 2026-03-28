package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertiserAccountStatus;
import com.mannschaft.app.advertising.dto.RecommendationItem;
import com.mannschaft.app.advertising.entity.AdCampaignEntity;
import com.mannschaft.app.advertising.entity.AdDailyStatsEntity;
import com.mannschaft.app.advertising.entity.AdEntity;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.repository.AdCampaignRepository;
import com.mannschaft.app.advertising.repository.AdDailyStatsRepository;
import com.mannschaft.app.advertising.repository.AdEntityRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
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
@Slf4j
public class RecommendationService {

    private final AdCampaignRepository adCampaignRepository;
    private final AdDailyStatsRepository adDailyStatsRepository;
    private final AdEntityRepository adEntityRepository;
    private final AdvertiserAccountRepository advertiserAccountRepository;

    private static final int MAX_RECOMMENDATIONS = 5;
    private static final BigDecimal BUDGET_ALERT_THRESHOLD = new BigDecimal("0.80");
    private static final BigDecimal UNDER_PACING_THRESHOLD = new BigDecimal("0.70");
    private static final BigDecimal CTR_DECLINE_THRESHOLD = new BigDecimal("0.20");
    private static final BigDecimal REGION_OPPORTUNITY_MULTIPLIER = new BigDecimal("1.30");
    private static final BigDecimal LOW_CTR_MULTIPLIER = new BigDecimal("0.50");
    private static final BigDecimal CREATIVE_DIFF_THRESHOLD = new BigDecimal("20");

    /**
     * 広告主のレコメンデーションを生成する（キャッシュあり、TTL 1時間）。
     */
    @Cacheable(value = "adRecommendations", key = "#organizationId")
    public List<RecommendationItem> getRecommendations(Long organizationId) {
        AdvertiserAccountEntity account = advertiserAccountRepository.findByOrganizationId(organizationId)
                .orElse(null);
        if (account == null || account.getStatus() != AdvertiserAccountStatus.ACTIVE) {
            return List.of();
        }

        List<AdCampaignEntity> campaigns = adCampaignRepository.findByAdvertiserOrganizationIdAndStatus(
                organizationId, AdCampaignEntity.CampaignStatus.ACTIVE);
        if (campaigns.isEmpty()) {
            return List.of();
        }

        List<Long> campaignIds = campaigns.stream().map(AdCampaignEntity::getId).toList();
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);

        List<RecommendationItem> recommendations = new ArrayList<>();

        // 当月の費用集計
        BigDecimal monthlyCost = adDailyStatsRepository.sumCostByCampaignIdsAndDateBetween(campaignIds, monthStart, today);

        // 1. BUDGET_ALERT: 月間費用が credit_limit の80%以上
        if (monthlyCost.compareTo(account.getCreditLimit().multiply(BUDGET_ALERT_THRESHOLD)) >= 0) {
            BigDecimal pct = monthlyCost.multiply(BigDecimal.valueOf(100))
                    .divide(account.getCreditLimit(), 1, RoundingMode.HALF_UP);
            recommendations.add(new RecommendationItem(
                    "BUDGET_ALERT", "HIGH",
                    "Monthly ad spend has reached " + pct + "% of your credit limit. Consider reducing daily budgets.",
                    null, "REDUCE_BUDGET"));
        }

        // 5. UNDER_PACING: 月間予測費用が credit_limit の70%未満
        int daysPassed = today.getDayOfMonth();
        if (daysPassed >= 3 && monthlyCost.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal dailyAvg = monthlyCost.divide(BigDecimal.valueOf(daysPassed), 2, RoundingMode.HALF_UP);
            BigDecimal projected = dailyAvg.multiply(BigDecimal.valueOf(today.lengthOfMonth()));
            if (projected.compareTo(account.getCreditLimit().multiply(UNDER_PACING_THRESHOLD)) < 0) {
                recommendations.add(new RecommendationItem(
                        "UNDER_PACING", "MEDIUM",
                        "Projected monthly spend is below 70% of your credit limit. Consider increasing campaign budgets.",
                        null, "INCREASE_BUDGET"));
            }
        }

        // キャンペーン単位の分析
        for (AdCampaignEntity campaign : campaigns) {
            Long cid = campaign.getId();

            // 直近7日 vs 前7日
            LocalDate last7Start = today.minusDays(7);
            LocalDate prev7Start = today.minusDays(14);

            List<AdDailyStatsEntity> last7 = adDailyStatsRepository.findByCampaignIdAndDateBetween(cid, last7Start, today);
            List<AdDailyStatsEntity> prev7 = adDailyStatsRepository.findByCampaignIdAndDateBetween(cid, prev7Start, last7Start.minusDays(1));

            long last7Imp = last7.stream().mapToLong(AdDailyStatsEntity::getImpressions).sum();
            long last7Clk = last7.stream().mapToLong(AdDailyStatsEntity::getClicks).sum();
            long prev7Imp = prev7.stream().mapToLong(AdDailyStatsEntity::getImpressions).sum();
            long prev7Clk = prev7.stream().mapToLong(AdDailyStatsEntity::getClicks).sum();

            BigDecimal last7Ctr = calcCtr(last7Imp, last7Clk);
            BigDecimal prev7Ctr = calcCtr(prev7Imp, prev7Clk);

            // 2. CTR_DECLINE: 直近7日のCTRが前7日比で20%以上低下
            if (prev7Ctr.compareTo(BigDecimal.ZERO) > 0 && last7Ctr.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal decline = prev7Ctr.subtract(last7Ctr).divide(prev7Ctr, 4, RoundingMode.HALF_UP);
                if (decline.compareTo(CTR_DECLINE_THRESHOLD) >= 0) {
                    BigDecimal declinePct = decline.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
                    recommendations.add(new RecommendationItem(
                            "CTR_DECLINE", "HIGH",
                            "Campaign '" + campaign.getName() + "' CTR declined by " + declinePct + "% in the last 7 days. Consider refreshing creatives.",
                            cid, "REFRESH_CREATIVE"));
                }
            }

            // 4. CREATIVE_WINNER: クリエイティブ間のCTR差が20%以上
            List<AdEntity> ads = adEntityRepository.findByCampaignId(cid);
            if (ads.size() >= 2) {
                List<BigDecimal> adCtrs = new ArrayList<>();
                String topAdTitle = null;
                BigDecimal topCtr = BigDecimal.ZERO;

                for (AdEntity ad : ads) {
                    List<AdDailyStatsEntity> adStats = adDailyStatsRepository.findByAdIdAndDateBetween(ad.getId(), last7Start, today);
                    long adImp = adStats.stream().mapToLong(AdDailyStatsEntity::getImpressions).sum();
                    long adClk = adStats.stream().mapToLong(AdDailyStatsEntity::getClicks).sum();
                    BigDecimal adCtr = calcCtr(adImp, adClk);
                    adCtrs.add(adCtr);
                    if (adImp >= 1000 && adCtr.compareTo(topCtr) > 0) {
                        topCtr = adCtr;
                        topAdTitle = ad.getTitle();
                    }
                }

                if (adCtrs.size() >= 2) {
                    List<BigDecimal> sorted = adCtrs.stream().sorted(Comparator.reverseOrder()).toList();
                    BigDecimal best = sorted.get(0);
                    BigDecimal second = sorted.get(1);
                    if (second.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal diff = best.subtract(second).multiply(BigDecimal.valueOf(100)).divide(second, 1, RoundingMode.HALF_UP);
                        if (diff.compareTo(CREATIVE_DIFF_THRESHOLD) >= 0 && topAdTitle != null) {
                            recommendations.add(new RecommendationItem(
                                    "CREATIVE_WINNER", "MEDIUM",
                                    "Creative '" + topAdTitle + "' outperforms others by " + diff + "%. Consider pausing underperformers.",
                                    cid, "PAUSE_CREATIVE"));
                        }
                    }
                }
            }
        }

        // 6. LOW_CTR: プラットフォーム平均との比較
        List<AdDailyStatsEntity> allPlatformStats = adDailyStatsRepository.findByDateBetween(today.minusDays(30), today);
        long platformImp = allPlatformStats.stream().mapToLong(AdDailyStatsEntity::getImpressions).sum();
        long platformClk = allPlatformStats.stream().mapToLong(AdDailyStatsEntity::getClicks).sum();
        BigDecimal platformCtr = calcCtr(platformImp, platformClk);

        if (platformCtr.compareTo(BigDecimal.ZERO) > 0) {
            for (AdCampaignEntity campaign : campaigns) {
                List<AdDailyStatsEntity> campaignStats = adDailyStatsRepository.findByCampaignIdAndDateBetween(
                        campaign.getId(), today.minusDays(30), today);
                long cImp = campaignStats.stream().mapToLong(AdDailyStatsEntity::getImpressions).sum();
                long cClk = campaignStats.stream().mapToLong(AdDailyStatsEntity::getClicks).sum();
                BigDecimal cCtr = calcCtr(cImp, cClk);

                if (cCtr.compareTo(platformCtr.multiply(LOW_CTR_MULTIPLIER)) < 0 && cImp > 0) {
                    recommendations.add(new RecommendationItem(
                            "LOW_CTR", "LOW",
                            "Campaign '" + campaign.getName() + "' CTR is below 50% of platform average. Review targeting and creatives.",
                            campaign.getId(), "REVIEW_CAMPAIGN"));
                }
            }
        }

        // ソート: HIGH → MEDIUM → LOW、最大5件
        Map<String, Integer> priorityOrder = Map.of("HIGH", 0, "MEDIUM", 1, "LOW", 2);
        return recommendations.stream()
                .sorted(Comparator.comparingInt(r -> priorityOrder.getOrDefault(r.priority(), 3)))
                .limit(MAX_RECOMMENDATIONS)
                .toList();
    }

    /**
     * レコメンデーションキャッシュを全エビクトする（日次バッチ完了時に呼び出す）。
     */
    @CacheEvict(value = "adRecommendations", allEntries = true)
    public void evictAllRecommendationCache() {
        log.info("adRecommendations キャッシュを全エビクトしました");
    }

    private BigDecimal calcCtr(long impressions, long clicks) {
        if (impressions == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(clicks)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(impressions), 2, RoundingMode.HALF_UP);
    }
}
