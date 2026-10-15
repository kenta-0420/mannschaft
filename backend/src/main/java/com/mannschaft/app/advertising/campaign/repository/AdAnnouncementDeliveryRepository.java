package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdAnnouncementDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * F09.17 お知らせチャネル配信実績リポジトリ。
 */
public interface AdAnnouncementDeliveryRepository
        extends JpaRepository<AdAnnouncementDelivery, UUID> {

    /** キャンペーン単位の配信実績集計用。 */
    long countByCampaignId(UUID campaignId);

    /** キャンペーン × 月キー (パーティション/レポート用)。 */
    List<AdAnnouncementDelivery> findByCampaignIdAndMonthKey(UUID campaignId, String monthKey);

    /** 課金対象件数: delivered_at IS NOT NULL の行数（F09.17 月次課金ブリッジ用）。 */
    long countByCampaignIdAndMonthKeyAndDeliveredAtIsNotNull(UUID campaignId, String monthKey);

    /** 退会時匿名化用: user_id 配下の配信実績を取得し、Service 層で NULL 化する。 */
    List<AdAnnouncementDelivery> findByUserId(Long userId);

    /** お知らせフィードからの逆引き。 */
    List<AdAnnouncementDelivery> findByAnnouncementFeedId(Long announcementFeedId);
}
