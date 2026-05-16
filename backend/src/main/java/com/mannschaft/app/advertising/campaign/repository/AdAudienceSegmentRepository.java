package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentInclusionMode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * F09.17 ターゲティング条件リポジトリ。
 */
public interface AdAudienceSegmentRepository extends JpaRepository<AdAudienceSegment, UUID> {

    /** キャンペーンの全セグメント (INCLUDE + EXCLUDE)。 */
    List<AdAudienceSegment> findByCampaignId(UUID campaignId);

    /** キャンペーン + 包含モード別セグメント。 */
    List<AdAudienceSegment> findByCampaignIdAndInclusionMode(
            UUID campaignId, AdSegmentInclusionMode inclusionMode);

    void deleteByCampaignId(UUID campaignId);
}
