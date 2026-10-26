package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdBannerDelivery;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.repository.AdBannerDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * F09.17 Phase 11-b ε-B / Phase 10 第二陣-A バナーチャネル配信サービス。
 *
 * <p>BANNER チャネルの<b>配信予約のみ</b>を担う（F09.19.3 §7.4 意味論修正）。
 * 予約時点では実表示ではないため {@code ad_impressions} は記録せず、{@code ad_banner_deliveries} を
 * {@code ad_impression_id = NULL, served_at = NULL}（未表示予約）で INSERT する。
 * 実表示（serve）としての充足は pull 型サービング（{@code SpotlightServingService} の view 計上）が
 * 当該予約行の {@code ad_impression_id} / {@code served_at} を埋めることで行う。</p>
 *
 * <p>FreqCap 消費は従来どおり予約時（在庫確定時）に呼び出し側で行う（本メソッド外）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdBannerChannelService {

    private final AdBannerDeliveryRepository adBannerDeliveryRepository;

    private static final DateTimeFormatter MONTH_KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 1 ユーザーへのバナー配信予約（未表示予約行の作成のみ）。
     *
     * <p>正本 §7.4: 予約時は {@code ad_impressions} を記録せず、{@code ad_banner_deliveries} を
     * {@code ad_impression_id = NULL, served_at = NULL} で INSERT する（配信 ≠ 表示）。</p>
     *
     * @param campaign キャンペーン本体
     * @param channel  BANNER チャネル設定（banner_creative_id を含む）
     * @param userId   受信者
     * @return 常に true（FreqCap は呼び出し側で消費済み）
     */
    @Transactional
    public boolean deliver(AdMessagingCampaign campaign,
                           AdMessagingCampaignChannel channel,
                           Long userId) {
        if (campaign == null || channel == null || userId == null) {
            throw new IllegalArgumentException("campaign, channel, userId は必須です");
        }

        Long bannerCreativeId = channel.getBannerCreativeId();

        LocalDateTime now = LocalDateTime.now();
        // 未表示予約: ad_impression_id / served_at は NULL（serve 時に充足）。
        AdBannerDelivery delivery = AdBannerDelivery.builder()
                .campaignId(campaign.getId())
                .userId(userId)
                .adImpressionId(null)
                .servedAt(null)
                .monthKey(now.format(MONTH_KEY_FORMATTER))
                .build();
        adBannerDeliveryRepository.save(delivery);

        log.info("AD_BANNER_RESERVED campaignId={} userId={} creativeId={}",
                campaign.getId(), userId, bannerCreativeId);

        return true;
    }
}
