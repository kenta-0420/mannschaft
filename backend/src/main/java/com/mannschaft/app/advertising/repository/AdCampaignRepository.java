package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.entity.AdCampaignEntity;
import com.mannschaft.app.advertising.entity.AdCampaignEntity.CampaignStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdCampaignRepository extends JpaRepository<AdCampaignEntity, Long> {
    List<AdCampaignEntity> findByAdvertiserOrganizationId(Long organizationId);
    List<AdCampaignEntity> findByAdvertiserOrganizationIdAndStatus(Long organizationId, CampaignStatus status);
    long countByAdvertiserOrganizationId(Long organizationId);
    long countByAdvertiserOrganizationIdAndStatus(Long organizationId, CampaignStatus status);
}
