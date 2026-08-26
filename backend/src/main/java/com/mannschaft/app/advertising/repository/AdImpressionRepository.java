package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.entity.AdImpressionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AdImpressionRepository extends JpaRepository<AdImpressionEntity, Long> {

    List<AdImpressionEntity> findByAdIdAndOccurredAtBetween(Long adId, LocalDateTime from, LocalDateTime to);

    List<AdImpressionEntity> findByCampaignIdAndOccurredAtBetween(Long campaignId, LocalDateTime from, LocalDateTime to);

    long countByAdIdAndOccurredAtBetween(Long adId, LocalDateTime from, LocalDateTime to);

    /**
     * F09.19.3 日次集計: 運用型（{@code campaign_id IS NOT NULL} = F09.7）のインプレッションを
     * {@code [start, end)} の窓で {@code (campaign_id, ad_id)} 集約する。
     *
     * <p>F09.17 分（{@code messaging_campaign_id} 非 NULL・{@code campaign_id} NULL）は
     * {@code campaign_id IS NOT NULL} 条件で自然に除外され、二重課金を防ぐ（正本 §7.3・§16 AC-3.4）。</p>
     *
     * @return 各行 {@code [campaignId(Number), adId(Number), count(Number)]}
     */
    @Query(value = "SELECT campaign_id, ad_id, COUNT(*) FROM ad_impressions "
            + "WHERE campaign_id IS NOT NULL AND occurred_at >= :start AND occurred_at < :end "
            + "GROUP BY campaign_id, ad_id", nativeQuery = true)
    List<Object[]> aggregateOperationalByCampaignAndAd(
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
