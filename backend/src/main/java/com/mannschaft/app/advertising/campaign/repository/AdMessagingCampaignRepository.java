package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collection;
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
            AdModerationStatus moderationStatus);

    /**
     * SYSTEM_ADMIN 審査キュー用: 指定の {@code moderation_status} 群に該当する論理削除されていない
     * キャンペーンをページング+作成日時昇順で取得する。
     *
     * <p>F09.17 Phase 11-a: 通常 {@code PENDING / AUTO_FLAGGED} を渡す想定。</p>
     */
    Page<AdMessagingCampaign> findByModerationStatusInAndDeletedAtIsNull(
            Collection<AdModerationStatus> moderationStatuses, Pageable pageable);

    /**
     * F09.17 Phase 11-b ε-A 自動遷移ワーカー用: 指定状態かつ {@code starts_at <= now} のキャンペーンを取得。
     *
     * <p>{@link AdCampaignStatus#SCHEDULED} を渡し、配信開始時刻に到達したキャンペーンを
     * {@code DELIVERING} へ自動遷移するために使う。</p>
     */
    List<AdMessagingCampaign> findByStatusAndStartsAtLessThanEqualAndDeletedAtIsNull(
            AdCampaignStatus status, LocalDateTime now);

    /**
     * F09.17 Phase 11-b ε-A 自動遷移ワーカー用: 指定状態かつ {@code ends_at <= now} のキャンペーンを取得。
     *
     * <p>{@link AdCampaignStatus#DELIVERING} を渡し、配信終了時刻に到達したキャンペーンを
     * {@code COMPLETED} へ自動遷移するために使う。</p>
     */
    List<AdMessagingCampaign> findByStatusAndEndsAtLessThanEqualAndDeletedAtIsNull(
            AdCampaignStatus status, LocalDateTime now);
}
