package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.entity.AdClickEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AdClickRepository extends JpaRepository<AdClickEntity, Long> {

    List<AdClickEntity> findByAdIdAndOccurredAtBetween(Long adId, LocalDateTime from, LocalDateTime to);

    List<AdClickEntity> findByCampaignIdAndOccurredAtBetween(Long campaignId, LocalDateTime from, LocalDateTime to);

    long countByCampaignIdAndOccurredAtBetween(Long campaignId, LocalDateTime from, LocalDateTime to);

    /**
     * F09.19.3 日次集計: 運用型（{@code campaign_id IS NOT NULL}）のクリックを {@code [start, end)} の窓で
     * {@code (campaign_id, ad_id)} 集約する（正本 §7.3・§16 AC-3.4）。
     *
     * @return 各行 {@code [campaignId(Number), adId(Number), count(Number)]}
     */
    @Query(value = "SELECT campaign_id, ad_id, COUNT(*) FROM ad_clicks "
            + "WHERE campaign_id IS NOT NULL AND occurred_at >= :start AND occurred_at < :end "
            + "GROUP BY campaign_id, ad_id", nativeQuery = true)
    List<Object[]> aggregateOperationalByCampaignAndAd(
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
