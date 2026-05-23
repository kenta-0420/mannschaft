package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.entity.AdClickEntity;
import com.mannschaft.app.advertising.repository.AdClickRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 広告クリック記録サービス。
 *
 * <p>クリックイベントを {@code ad_clicks} テーブルに不変レコードとして記録し、
 * 発行された ID を返す。インプレッションなしの直接クリックにも対応する。</p>
 */
@Service
@RequiredArgsConstructor
public class AdClickService {

    private final AdClickRepository adClickRepository;

    /**
     * F09.7 用クリック記録。
     *
     * @param adId         ads.id
     * @param campaignId   F09.7 キャンペーンID (ad_campaigns.id)
     * @param impressionId ad_impressions.id（対応するインプレッションがある場合、なければ null）
     * @param userId       クリックユーザー ID（未ログインの場合は null）
     * @return 作成した ad_clicks.id
     */
    @Transactional
    public Long record(Long adId, Long campaignId, Long impressionId, Long userId) {
        AdClickEntity click = AdClickEntity.create(adId, campaignId, impressionId, userId);
        return adClickRepository.save(click).getId();
    }

    /**
     * F09.17 メッセージキャンペーン用クリック記録。
     *
     * <p>F09.17 の {@code ad_messaging_campaigns.id}（UUID）を正確に記録する。
     * {@code ad_clicks.campaign_id}（Long）は NULL とし、
     * {@code ad_clicks.messaging_campaign_id}（BINARY(16)）に UUID を格納する。</p>
     *
     * @param adId                広告ID (ads.id / バナークリエイティブ ID)
     * @param messagingCampaignId F09.17 メッセージキャンペーンID (ad_messaging_campaigns.id)
     * @param impressionId        ad_impressions.id（対応するインプレッションがある場合、なければ null）
     * @param userId              クリックユーザー ID（未ログインの場合は null）
     * @return 作成した ad_clicks.id
     */
    @Transactional
    public Long recordForMessagingCampaign(Long adId, UUID messagingCampaignId, Long impressionId, Long userId) {
        AdClickEntity click = AdClickEntity.createForMessagingCampaign(adId, messagingCampaignId, impressionId, userId);
        return adClickRepository.save(click).getId();
    }
}
