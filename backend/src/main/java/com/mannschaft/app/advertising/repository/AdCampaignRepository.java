package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.entity.AdCampaignEntity;
import com.mannschaft.app.advertising.entity.AdCampaignEntity.CampaignStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdCampaignRepository extends JpaRepository<AdCampaignEntity, Long> {
    List<AdCampaignEntity> findByAdvertiserOrganizationId(Long organizationId);
    List<AdCampaignEntity> findByAdvertiserOrganizationIdAndStatus(Long organizationId, CampaignStatus status);
    long countByAdvertiserOrganizationId(Long organizationId);
    long countByAdvertiserOrganizationIdAndStatus(Long organizationId, CampaignStatus status);

    // ─── F09.19.1 運用型 CRUD 一覧（PagedResponse 正準・created_at DESC は Pageable の Sort で指定） ───
    Page<AdCampaignEntity> findByAdvertiserOrganizationId(Long organizationId, Pageable pageable);
    Page<AdCampaignEntity> findByAdvertiserOrganizationIdAndStatus(
            Long organizationId, CampaignStatus status, Pageable pageable);

    /** SYSTEM_ADMIN 審査キュー（status フィルタ）。 */
    Page<AdCampaignEntity> findByStatus(CampaignStatus status, Pageable pageable);

    /** AD_034 防御: 指定料金カードを参照する運用型キャンペーンが存在するか。 */
    boolean existsByRateCardId(Long rateCardId);
}
