package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdAnnouncementDelivery;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.repository.AdAnnouncementDeliveryRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * F09.17 Phase 11-b ε-B お知らせチャネル配信サービス。
 *
 * <p>F02.6 {@link AnnouncementFeedService#createAdvertiserFeed} を呼び、
 * 戻り値の {@code feedId} を {@code ad_announcement_deliveries.announcement_feed_id} に転記する。
 * モジュラーモノリスの原則に従い、F02.6 ドメインへは Service Method 経由でのみアクセスする
 * （Repository 直叩きしない）。</p>
 *
 * <p>「広告」ラベル必須化（景品表示法）は {@code AnnouncementFeedEntity.isAdvertisement=true} で
 * F02.6 のフロント側が「広告」バッジを描画する想定。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdAnnouncementChannelService {

    /** YYYY-MM (パーティショニング用 month_key) */
    private static final DateTimeFormatter MONTH_KEY_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final AnnouncementFeedService announcementFeedService;
    private final AdAnnouncementDeliveryRepository deliveryRepository;

    /**
     * 1 ユーザーに 1 件のお知らせを配信する。
     *
     * <p>処理順:</p>
     * <ol>
     *   <li>{@link AnnouncementFeedService#createAdvertiserFeed} を呼んで feed 行を保存</li>
     *   <li>{@code ad_announcement_deliveries} に履歴を 1 行追加</li>
     * </ol>
     *
     * <p>例外は呼び出し元（dispatcher）にそのまま伝播する。{@code dispatcher} 側で
     * try/catch + FreqCap ロールバックする設計。</p>
     *
     * @param campaign キャンペーン本体
     * @param channel  ANNOUNCEMENT チャネル設定（locale 解決済の単一行）
     * @param userId   受信者
     */
    @Transactional
    public void deliver(AdMessagingCampaign campaign,
                        AdMessagingCampaignChannel channel,
                        Long userId) {
        if (campaign == null || channel == null || userId == null) {
            throw new IllegalArgumentException("campaign, channel, userId は必須です");
        }

        // 1) F02.6 お知らせフィードを 1 行作成
        AnnouncementFeedEntity feed = announcementFeedService.createAdvertiserFeed(
                campaign.getAdvertiserAccountId(),
                campaign.getId(),
                userId,
                channel.getSubject(),
                channel.getBodyMarkdown());

        // 2) ad_announcement_deliveries に履歴を残す
        LocalDateTime now = LocalDateTime.now();
        AdAnnouncementDelivery delivery = AdAnnouncementDelivery.builder()
                .campaignId(campaign.getId())
                .userId(userId)
                .announcementFeedId(feed.getId())
                .deliveredAt(now)
                .monthKey(now.format(MONTH_KEY_FMT))
                .build();
        deliveryRepository.save(delivery);

        log.info("AD_ANNOUNCEMENT_DELIVERED campaignId={} userId={} feedId={}",
                campaign.getId(), userId, feed.getId());
    }
}
