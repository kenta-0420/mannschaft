package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdPushDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * F09.17 プッシュ通知チャネル配信実績リポジトリ。
 */
public interface AdPushDeliveryRepository extends JpaRepository<AdPushDelivery, UUID> {

    long countByCampaignId(UUID campaignId);

    List<AdPushDelivery> findByCampaignIdAndMonthKey(UUID campaignId, String monthKey);

    /** 退会時匿名化用。 */
    List<AdPushDelivery> findByUserId(Long userId);
}
