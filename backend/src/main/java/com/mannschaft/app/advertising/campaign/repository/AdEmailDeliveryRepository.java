package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdEmailDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * F09.17 メールチャネル配信実績リポジトリ。
 */
public interface AdEmailDeliveryRepository extends JpaRepository<AdEmailDelivery, UUID> {

    long countByCampaignId(UUID campaignId);

    List<AdEmailDelivery> findByCampaignIdAndMonthKey(UUID campaignId, String monthKey);

    /** 退会時匿名化用。 */
    List<AdEmailDelivery> findByUserId(Long userId);

    /** バウンス済み件数集計。 */
    long countByCampaignIdAndBouncedAtIsNotNull(UUID campaignId);
}
