package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdBannerDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * F09.17 バナーチャネル配信実績リポジトリ。
 */
public interface AdBannerDeliveryRepository extends JpaRepository<AdBannerDelivery, UUID> {

    long countByCampaignId(UUID campaignId);

    List<AdBannerDelivery> findByCampaignIdAndMonthKey(UUID campaignId, String monthKey);

    /** 退会時匿名化用。 */
    List<AdBannerDelivery> findByUserId(Long userId);

    /** クリック数集計。 */
    long countByCampaignIdAndClickedAtIsNotNull(UUID campaignId);
}
