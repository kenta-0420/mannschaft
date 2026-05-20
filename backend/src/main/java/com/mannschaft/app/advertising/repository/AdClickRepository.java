package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.entity.AdClickEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AdClickRepository extends JpaRepository<AdClickEntity, Long> {

    List<AdClickEntity> findByAdIdAndOccurredAtBetween(Long adId, LocalDateTime from, LocalDateTime to);

    List<AdClickEntity> findByCampaignIdAndOccurredAtBetween(Long campaignId, LocalDateTime from, LocalDateTime to);

    long countByCampaignIdAndOccurredAtBetween(Long campaignId, LocalDateTime from, LocalDateTime to);
}
