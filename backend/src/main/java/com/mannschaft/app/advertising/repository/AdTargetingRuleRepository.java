package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.entity.AdTargetingRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdTargetingRuleRepository extends JpaRepository<AdTargetingRuleEntity, Long> {
    List<AdTargetingRuleEntity> findByCampaignId(Long campaignId);
}
