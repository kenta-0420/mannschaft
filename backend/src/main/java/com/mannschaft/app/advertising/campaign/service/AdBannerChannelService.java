package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdBannerDelivery;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.repository.AdBannerDeliveryRepository;
import com.mannschaft.app.advertising.service.AdImpressionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * F09.17 Phase 11-b ε-B / Phase 10 第二陣-A バナーチャネル配信サービス。
 *
 * <p>BANNER チャネルの配信予約を担う。F09.7 {@link AdImpressionService} を呼んで
 * インプレッションを記録し、{@code ad_banner_deliveries} に保存する。
 * 真の click は F09.7 が serve した瞬間に記録する設計（設計書 §5）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdBannerChannelService {

    private final AdImpressionService adImpressionService;
    private final AdBannerDeliveryRepository adBannerDeliveryRepository;

    private static final DateTimeFormatter MONTH_KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 1 ユーザーへのバナー配信予約。
     *
     * <p>F09.7 {@link AdImpressionService#recordForMessagingCampaign} を呼んでインプレッションを記録し、
     * {@code ad_banner_deliveries} に {@code ad_impression_id} を保存する。</p>
     *
     * <p>F09.17 の {@code ad_messaging_campaigns.id}（UUID）を {@code ad_impressions.messaging_campaign_id}
     * に正確に記録する（F09.7 / F09.17 の campaignId 型不一致根治）。</p>
     *
     * @param campaign キャンペーン本体
     * @param channel  BANNER チャネル設定（banner_creative_id を含む）
     * @param userId   受信者
     * @return 常に true（FreqCap は消費したことにする / 設計書 §5 末尾の方針）
     */
    @Transactional
    public boolean deliver(AdMessagingCampaign campaign,
                           AdMessagingCampaignChannel channel,
                           Long userId) {
        if (campaign == null || channel == null || userId == null) {
            throw new IllegalArgumentException("campaign, channel, userId は必須です");
        }

        Long bannerCreativeId = channel.getBannerCreativeId();

        // F09.17 メッセージキャンペーン用インプレッション記録。
        // messaging_campaign_id (UUID) を正確に記録する（F09.7 Long vs F09.17 UUID 型不一致根治）。
        Long adImpressionId = adImpressionService.recordForMessagingCampaign(
                bannerCreativeId,
                campaign.getId(),
                userId);

        LocalDateTime now = LocalDateTime.now();
        AdBannerDelivery delivery = AdBannerDelivery.builder()
                .campaignId(campaign.getId())
                .userId(userId)
                .adImpressionId(adImpressionId)
                .servedAt(now)
                .monthKey(now.format(MONTH_KEY_FORMATTER))
                .build();
        adBannerDeliveryRepository.save(delivery);

        log.info("AD_BANNER_DELIVERED campaignId={} userId={} creativeId={} impressionId={}",
                campaign.getId(), userId, bannerCreativeId, adImpressionId);

        return true;
    }
}
