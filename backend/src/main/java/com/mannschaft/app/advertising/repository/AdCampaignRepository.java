package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.entity.AdCampaignEntity;
import com.mannschaft.app.advertising.entity.AdCampaignEntity.CampaignStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdCampaignRepository extends JpaRepository<AdCampaignEntity, Long> {
    // ─── F09.19.5 scope 化: advertiser_account_id 直結（org/team 両対応。旧 advertiser_organization_id 撤廃） ───
    List<AdCampaignEntity> findByAdvertiserAccountId(Long advertiserAccountId);
    List<AdCampaignEntity> findByAdvertiserAccountIdAndStatus(Long advertiserAccountId, CampaignStatus status);
    long countByAdvertiserAccountId(Long advertiserAccountId);
    long countByAdvertiserAccountIdAndStatus(Long advertiserAccountId, CampaignStatus status);

    // ─── F09.19.1 運用型 CRUD 一覧（PagedResponse 正準・created_at DESC は Pageable の Sort で指定） ───
    Page<AdCampaignEntity> findByAdvertiserAccountId(Long advertiserAccountId, Pageable pageable);
    Page<AdCampaignEntity> findByAdvertiserAccountIdAndStatus(
            Long advertiserAccountId, CampaignStatus status, Pageable pageable);

    /** SYSTEM_ADMIN 審査キュー（status フィルタ）。 */
    Page<AdCampaignEntity> findByStatus(CampaignStatus status, Pageable pageable);

    /** AD_034 防御: 指定料金カードを参照する運用型キャンペーンが存在するか。 */
    boolean existsByRateCardId(Long rateCardId);
}
