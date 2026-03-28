package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.entity.AdCampaignEntity;
import com.mannschaft.app.advertising.entity.AdDailyStatsEntity;
import com.mannschaft.app.advertising.repository.AdCampaignRepository;
import com.mannschaft.app.advertising.repository.AdDailyStatsRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CsvExportService {

    private final AdCampaignRepository adCampaignRepository;
    private final AdDailyStatsRepository adDailyStatsRepository;

    /**
     * キャンペーンのパフォーマンスデータをCSV形式でエクスポートする。
     */
    public byte[] exportCampaignPerformance(Long campaignId, Long organizationId,
                                             LocalDate from, LocalDate to) {
        AdCampaignEntity campaign = adCampaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_005));
        if (!campaign.getAdvertiserOrganizationId().equals(organizationId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        List<AdDailyStatsEntity> stats = adDailyStatsRepository.findByCampaignIdAndDateBetween(campaignId, from, to);

        // 日別集計
        Map<LocalDate, List<AdDailyStatsEntity>> byDate = stats.stream()
                .collect(Collectors.groupingBy(AdDailyStatsEntity::getDate));

        StringBuilder csv = new StringBuilder();
        // BOM for Excel compatibility
        csv.append('\uFEFF');
        csv.append("date,impressions,clicks,ctr,cost,conversions,conversion_rate,cost_per_conversion\n");

        byDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    LocalDate date = entry.getKey();
                    long imp = entry.getValue().stream().mapToLong(AdDailyStatsEntity::getImpressions).sum();
                    long clk = entry.getValue().stream().mapToLong(AdDailyStatsEntity::getClicks).sum();
                    BigDecimal cost = entry.getValue().stream()
                            .map(AdDailyStatsEntity::getCost)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal ctr = imp > 0
                            ? BigDecimal.valueOf(clk).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(imp), 2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;

                    csv.append(date).append(',')
                       .append(imp).append(',')
                       .append(clk).append(',')
                       .append(ctr).append(',')
                       .append(cost).append(',')
                       .append(',')  // conversions - TODO: integrate with ad_conversions
                       .append(',')  // conversion_rate
                       .append('\n');
                });

        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * CSVファイル名を生成する。
     */
    public String getCsvFilename(Long campaignId, LocalDate from, LocalDate to) {
        return "campaign_" + campaignId + "_" + from + "_" + to + ".csv";
    }
}
