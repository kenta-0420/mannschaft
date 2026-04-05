package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.AdvertisingMapper;
import com.mannschaft.app.advertising.dto.AdConversionResponse;
import com.mannschaft.app.advertising.dto.AdConversionSummaryResponse;
import com.mannschaft.app.advertising.entity.AdCampaignEntity;
import com.mannschaft.app.advertising.entity.AdConversionEntity;
import com.mannschaft.app.advertising.entity.AdDailyStatsEntity;
import com.mannschaft.app.advertising.repository.AdCampaignRepository;
import com.mannschaft.app.advertising.repository.AdConversionRepository;
import com.mannschaft.app.advertising.repository.AdDailyStatsRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 広告コンバージョンサービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdConversionService {

    private final AdConversionRepository adConversionRepository;
    private final AdCampaignRepository adCampaignRepository;
    private final AdDailyStatsRepository adDailyStatsRepository;
    private final AdvertisingMapper advertisingMapper;

    /**
     * キャンペーン別コンバージョン一覧を取得する（期間指定）。
     */
    public List<AdConversionResponse> getConversions(Long campaignId, Long organizationId,
                                                      LocalDate from, LocalDate to) {
        findCampaignWithAuth(campaignId, organizationId);

        LocalDateTime fromDateTime = from.atStartOfDay();
        LocalDateTime toDateTime = to.atTime(LocalTime.MAX);

        List<AdConversionEntity> conversions =
                adConversionRepository.findByCampaignIdAndConvertedAtBetween(campaignId, fromDateTime, toDateTime);

        return conversions.stream()
                .map(advertisingMapper::toAdConversionResponse)
                .toList();
    }

    /**
     * キャンペーン別コンバージョンサマリーを取得する（件数、CVR、CPA）。
     */
    public AdConversionSummaryResponse getConversionSummary(Long campaignId, Long organizationId,
                                                             LocalDate from, LocalDate to) {
        findCampaignWithAuth(campaignId, organizationId);

        LocalDateTime fromDateTime = from.atStartOfDay();
        LocalDateTime toDateTime = to.atTime(LocalTime.MAX);

        List<AdConversionEntity> conversions =
                adConversionRepository.findByCampaignIdAndConvertedAtBetween(campaignId, fromDateTime, toDateTime);

        long totalConversions = conversions.size();

        // コンバージョン種別ごとの件数
        Map<String, Long> conversionsByType = conversions.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getConversionType().name(),
                        Collectors.counting()));

        // 日別統計からクリック数・コストを取得
        List<AdDailyStatsEntity> stats =
                adDailyStatsRepository.findByCampaignIdAndDateBetween(campaignId, from, to);
        long totalClicks = stats.stream().mapToLong(AdDailyStatsEntity::getClicks).sum();
        BigDecimal totalCost = stats.stream()
                .map(AdDailyStatsEntity::getCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // CVR = conversions / clicks * 100
        BigDecimal conversionRate = totalClicks > 0
                ? BigDecimal.valueOf(totalConversions)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(totalClicks), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // CPA = totalCost / conversions
        BigDecimal costPerConversion = totalConversions > 0
                ? totalCost.divide(BigDecimal.valueOf(totalConversions), 2, RoundingMode.HALF_UP)
                : null;

        return new AdConversionSummaryResponse(
                campaignId,
                totalConversions,
                totalClicks,
                conversionRate,
                totalCost,
                costPerConversion,
                conversionsByType);
    }

    /**
     * キャンペーンの存在と組織権限を検証する。
     */
    private AdCampaignEntity findCampaignWithAuth(Long campaignId, Long organizationId) {
        AdCampaignEntity campaign = adCampaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_021));
        if (!campaign.getAdvertiserOrganizationId().equals(organizationId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return campaign;
    }
}
