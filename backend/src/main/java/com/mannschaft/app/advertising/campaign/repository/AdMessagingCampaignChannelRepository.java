package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F09.17 キャンペーンチャネル別コンテンツリポジトリ。
 */
public interface AdMessagingCampaignChannelRepository
        extends JpaRepository<AdMessagingCampaignChannel, UUID> {

    /** キャンペーンに紐づく全チャネル × 言語コンテンツを取得。 */
    List<AdMessagingCampaignChannel> findByCampaignId(UUID campaignId);

    /** キャンペーン + チャネル種別 + ロケールでユニーク取得。 */
    Optional<AdMessagingCampaignChannel> findByCampaignIdAndChannelTypeAndLocale(
            UUID campaignId, AdChannelType channelType, String locale);

    /** キャンペーン + チャネル種別の多言語コンテンツ一覧。 */
    List<AdMessagingCampaignChannel> findByCampaignIdAndChannelType(
            UUID campaignId, AdChannelType channelType);

    /** キャンペーン削除時の物理クリーンアップ補助 (FK CASCADE と併用)。 */
    void deleteByCampaignId(UUID campaignId);
}
