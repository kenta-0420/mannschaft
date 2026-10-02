package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdPushDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * F09.17 プッシュ通知チャネル配信実績リポジトリ。
 */
public interface AdPushDeliveryRepository extends JpaRepository<AdPushDelivery, UUID> {

    long countByCampaignId(UUID campaignId);

    List<AdPushDelivery> findByCampaignIdAndMonthKey(UUID campaignId, String monthKey);

    /**
     * 課金対象件数: delivered_at IS NOT NULL AND failed_reason が NULL または空白のみ
     * （F09.17 月次課金ブリッジ用）。
     *
     * <p>旧実装（{@code d.getFailedReason() == null || d.getFailedReason().isBlank()}）と
     * 意味を一致させるため、空文字だけでなく空白のみの文字列（{@code " "} 等）も
     * 「失敗なし＝課金対象」として扱う。{@code TRIM()} で前後の空白を除去してから
     * 空文字判定することで {@code String#isBlank()} 相当の判定を SQL 側で再現する。</p>
     */
    @Query("SELECT COUNT(d) FROM AdPushDelivery d WHERE d.campaignId = :campaignId AND d.monthKey = :monthKey "
            + "AND d.deliveredAt IS NOT NULL "
            + "AND (d.failedReason IS NULL OR TRIM(d.failedReason) = '')")
    long countBillableByCampaignIdAndMonthKey(@Param("campaignId") UUID campaignId, @Param("monthKey") String monthKey);

    /** 退会時匿名化用。 */
    List<AdPushDelivery> findByUserId(Long userId);
}
