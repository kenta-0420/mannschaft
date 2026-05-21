package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.entity.AdEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdEntityRepository extends JpaRepository<AdEntity, Long> {

    List<AdEntity> findByCampaignId(Long campaignId);

    Optional<AdEntity> findByIdAndCampaignId(Long id, Long campaignId);

    List<AdEntity> findAllByStatus(AdEntity.AdStatus status);
}
