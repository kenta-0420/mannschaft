package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdBannerDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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

    /**
     * F09.19.3 BANNER 課金対象件数: 指定月に実表示された（{@code served_at IS NOT NULL}）予約行数。
     *
     * <p>正本 §7.4: BANNER 課金は「¥3 / served view」の固定単価。未表示予約（{@code served_at IS NULL}）は
     * 課金対象外。{@link AdMessagingBillingBridge} が集計に使用する。</p>
     */
    long countByCampaignIdAndMonthKeyAndServedAtIsNotNull(UUID campaignId, String monthKey);

    /**
     * F09.19.3 予約鮮度: {@code served_at IS NULL} かつ {@code created_at < cutoff} の未表示予約を抽出する
     * （正本 §7.4「予約 EXPIRED 処理」・§16 AC-3.8）。抽出行は serve 対象外化 + FreqCap 返却の対象。
     */
    @Query("SELECT d FROM AdBannerDelivery d "
            + "WHERE d.servedAt IS NULL AND d.createdAt < :cutoff")
    List<AdBannerDelivery> findStaleUnservedReservations(@Param("cutoff") LocalDateTime cutoff);
}
