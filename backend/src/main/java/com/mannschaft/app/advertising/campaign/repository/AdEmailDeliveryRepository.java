package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdEmailDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F09.17 メールチャネル配信実績リポジトリ。
 */
public interface AdEmailDeliveryRepository extends JpaRepository<AdEmailDelivery, UUID> {

    long countByCampaignId(UUID campaignId);

    List<AdEmailDelivery> findByCampaignIdAndMonthKey(UUID campaignId, String monthKey);

    /**
     * 課金対象件数: sent_at IS NOT NULL AND (bounce_type IS NULL OR bounce_type='SOFT')。
     * HARD / COMPLAINT は課金対象外（F09.17 月次課金ブリッジ用）。
     */
    @Query("SELECT COUNT(d) FROM AdEmailDelivery d WHERE d.campaignId = :campaignId AND d.monthKey = :monthKey "
            + "AND d.sentAt IS NOT NULL "
            + "AND (d.bounceType IS NULL OR d.bounceType = com.mannschaft.app.advertising.campaign.enums.AdBounceType.SOFT)")
    long countBillableByCampaignIdAndMonthKey(@Param("campaignId") UUID campaignId, @Param("monthKey") String monthKey);

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
