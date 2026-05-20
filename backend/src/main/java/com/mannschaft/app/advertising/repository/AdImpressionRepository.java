package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.entity.AdImpressionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AdImpressionRepository extends JpaRepository<AdImpressionEntity, Long> {

    List<AdImpressionEntity> findByAdIdAndOccurredAtBetween(Long adId, LocalDateTime from, LocalDateTime to);

    List<AdImpressionEntity> findByCampaignIdAndOccurredAtBetween(Long campaignId, LocalDateTime from, LocalDateTime to);

    long countByAdIdAndOccurredAtBetween(Long adId, LocalDateTime from, LocalDateTime to);
}
