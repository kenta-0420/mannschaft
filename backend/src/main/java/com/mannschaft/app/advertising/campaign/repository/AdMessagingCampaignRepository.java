package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F09.17 メッセージ型キャンペーン本体リポジトリ。
 * テナント分離キー {@code organization_id} を持つため
 * {@link AbstractTenantAwareRepository} を継承する。
 *
 * <p>基底から提供:
 * {@code findByOrganizationIdAndDeletedAtIsNull}, {@code findByIdAndOrganizationIdAndDeletedAtIsNull},
 * {@code countByOrganizationIdAndDeletedAtIsNull}</p>
 */
public interface AdMessagingCampaignRepository
        extends AbstractTenantAwareRepository<AdMessagingCampaign, UUID> {

    /** 広告主アカウント単位の一覧 (DRAFT・REVIEW など全状態)。 */
    List<AdMessagingCampaign> findByAdvertiserAccountIdAndDeletedAtIsNull(Long advertiserAccountId);

    /** 配信スケジューラ用: 状態 + ウィンドウで配信対象キャンペーンを探索。 */
    List<AdMessagingCampaign> findByStatusAndStartsAtLessThanEqualAndEndsAtGreaterThanEqualAndDeletedAtIsNull(
            AdCampaignStatus status, LocalDateTime startsAtUpper, LocalDateTime endsAtLower);

    /** モデレーションキュー用: 審査状態順の一覧。 */
    List<AdMessagingCampaign> findByModerationStatusAndDeletedAtIsNullOrderByCreatedAtAsc(
            com.mannschaft.app.advertising.campaign.enums.AdModerationStatus moderationStatus);
}
