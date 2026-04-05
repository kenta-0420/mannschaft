package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.entity.AdEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdEntityRepository extends JpaRepository<AdEntity, Long> {
    List<AdEntity> findByCampaignId(Long campaignId);
}
