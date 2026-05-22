package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.entity.AdImpressionEntity;
import com.mannschaft.app.advertising.repository.AdImpressionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 広告インプレッション記録サービス。
 *
 * <p>F09.7 の既存バナー抽選機構と F09.17 メッセージ型キャンペーンの
 * 両方から呼ばれる共通サービス。インプレッションイベントを {@code ad_impressions}
 * テーブルに不変レコードとして記録し、発行された ID を返す。</p>
 */
@Service
@RequiredArgsConstructor
public class AdImpressionService {

    private final AdImpressionRepository adImpressionRepository;

    /**
     * F09.7 用インプレッション記録。
     *
     * @param adId       ads.id（クリエイティブ ID）
     * @param campaignId F09.7 キャンペーンID (ad_campaigns.id)
     * @param userId     閲覧ユーザー ID（未ログインの場合は null）
     * @return 作成した ad_impressions.id
     */
    @Transactional
    public Long record(Long adId, Long campaignId, Long userId) {
        AdImpressionEntity impression = AdImpressionEntity.create(adId, campaignId, userId);
        return adImpressionRepository.save(impression).getId();
    }

    /**
     * F09.17 BANNER チャネル用インプレッション予約。
     *
     * <p>serving_strategy を引数で受け取り、将来の抽選ロジック拡張に備える。
     * 現フェーズでは即時記録。将来的に serving_strategy='MESSAGING_CAMPAIGN' で
     * 抽選プール管理が必要になった場合はここを拡張する。</p>
     *
     * @param servingStrategy 配信戦略（例: {@code "MESSAGING_CAMPAIGN"}）
     * @param adId            ads.id（クリエイティブ ID）
     * @param campaignId      F09.7 キャンペーン ID (ad_campaigns.id)
     * @param userId          閲覧ユーザー ID（未ログインの場合は null）
     * @return 作成した ad_impressions.id
     * @deprecated F09.17 メッセージキャンペーン用には {@link #recordForMessagingCampaign} を使うこと
     */
    @Transactional
    public Long scheduleServe(String servingStrategy, Long adId, Long campaignId, Long userId) {
        // 現フェーズでは即時記録。将来的に serving_strategy='MESSAGING_CAMPAIGN' で
        // 抽選プール管理が必要になった場合はここを拡張する。
        return record(adId, campaignId, userId);
    }

    /**
     * F09.17 メッセージキャンペーン用インプレッション記録。
     *
     * <p>F09.17 の {@code ad_messaging_campaigns.id}（UUID）を正確に記録する。
     * {@code ad_impressions.campaign_id}（Long）は NULL とし、
     * {@code ad_impressions.messaging_campaign_id}（BINARY(16)）に UUID を格納する。</p>
     *
     * @param adId                広告ID (ads.id / バナークリエイティブ ID)
     * @param messagingCampaignId F09.17 メッセージキャンペーンID (ad_messaging_campaigns.id)
     * @param userId              閲覧ユーザー ID（未ログインの場合は null）
     * @return 作成した ad_impressions.id
     */
    @Transactional
    public Long recordForMessagingCampaign(Long adId, UUID messagingCampaignId, Long userId) {
        AdImpressionEntity impression = AdImpressionEntity.createForMessagingCampaign(adId, messagingCampaignId, userId);
        return adImpressionRepository.save(impression).getId();
    }
}
