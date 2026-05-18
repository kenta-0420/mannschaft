package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdEmailDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
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

    /**
     * SES バウンス通知反映用。
     * {@code direct_mail_recipient_id} から F09.17 由来の配信履歴を引き当てる。
     * 通常 1 件のみだが念のため Optional 返却。
     */
    Optional<AdEmailDelivery> findByDirectMailRecipientId(Long directMailRecipientId);
}
