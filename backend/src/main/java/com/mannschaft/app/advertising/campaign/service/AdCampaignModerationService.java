package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.dto.BlockCampaignRequest;
import com.mannschaft.app.advertising.campaign.dto.ReviewQueueItemResponse;
import com.mannschaft.app.advertising.campaign.entity.AdCampaignModerationLog;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationAction;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.repository.AdCampaignModerationLogRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F09.17 Phase 11-a メッセージ型キャンペーン モデレーションサービス。
 *
 * <p>SYSTEM_ADMIN が手動で行う審査キュー取得・承認・ブロック操作を担う。
 * 自動 NG 検知や通報 3 件で自動 SUSPEND は Phase 11-b スコープ外。</p>
 */
@Service
@RequiredArgsConstructor
public class AdCampaignModerationService {

    /** SYSTEM_ADMIN が確認すべき審査対象状態。 */
    private static final Set<AdModerationStatus> REVIEW_QUEUE_STATUSES =
            Set.of(AdModerationStatus.PENDING, AdModerationStatus.AUTO_FLAGGED);

    /** approve 可能なキャンペーン状態 (DRAFT / REVIEW のみ)。 */
    private static final Set<AdCampaignStatus> APPROVE_ALLOWED_STATUSES =
            Set.of(AdCampaignStatus.DRAFT, AdCampaignStatus.REVIEW);

    private final AdMessagingCampaignRepository campaignRepository;
    private final AdCampaignModerationLogRepository moderationLogRepository;

    /**
     * SYSTEM_ADMIN 審査キューを取得する。
     *
     * <p>{@code moderation_status IN (PENDING, AUTO_FLAGGED)} のキャンペーンを
     * {@code created_at ASC} (古い順) で返す。</p>
     */
    @Transactional(readOnly = true)
    public Page<ReviewQueueItemResponse> getReviewQueue(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<AdMessagingCampaign> campaigns =
                campaignRepository.findByModerationStatusInAndDeletedAtIsNull(
                        REVIEW_QUEUE_STATUSES, pageable);
        return campaigns.map(ReviewQueueItemResponse::from);
    }

    /**
     * キャンペーンを承認する。
     *
     * <p>条件:
     * <ul>
     *   <li>{@code moderation_status} が {@code PENDING} または {@code AUTO_FLAGGED}</li>
     *   <li>{@code status} が {@code DRAFT} または {@code REVIEW}</li>
     * </ul>
     * いずれかを満たさない場合は {@link AdCampaignErrorCode#NOT_REVIEWABLE} を投げる。</p>
     *
     * <p>{@code moderation_status=APPROVED}, {@code status=APPROVED} に更新し、
     * {@code ad_campaign_moderation_logs} へ {@code action=APPROVED} の行を 1 件作成する。</p>
     */
    @Transactional
    public void approve(UUID campaignId, Long moderatorUserId) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId);

        if (!REVIEW_QUEUE_STATUSES.contains(campaign.getModerationStatus())
                || !APPROVE_ALLOWED_STATUSES.contains(campaign.getStatus())) {
            throw new BusinessException(AdCampaignErrorCode.NOT_REVIEWABLE);
        }

        campaign.setModerationStatus(AdModerationStatus.APPROVED);
        campaign.setStatus(AdCampaignStatus.APPROVED);
        campaignRepository.save(campaign);

        moderationLogRepository.save(AdCampaignModerationLog.builder()
                .campaignId(campaignId)
                .moderatorUserId(moderatorUserId)
                .action(AdModerationAction.APPROVED)
                .build());
    }

    /**
     * キャンペーンをブロックする。
     *
     * <p>任意の {@code moderation_status} から {@code BLOCKED} へ遷移可能だが、
     * 既に {@code BLOCKED} のキャンペーンへの重複ブロックは
     * {@link AdCampaignErrorCode#ALREADY_BLOCKED} で 409 Conflict を返す。</p>
     *
     * <p>{@code moderation_status=BLOCKED}, {@code status=BLOCKED},
     * {@code blocked_reason=reason} に更新し、
     * {@code ad_campaign_moderation_logs} へ {@code action=BLOCKED} + reason の行を 1 件作成する。</p>
     */
    @Transactional
    public void block(UUID campaignId, Long moderatorUserId, BlockCampaignRequest request) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId);

        if (campaign.getModerationStatus() == AdModerationStatus.BLOCKED) {
            throw new BusinessException(AdCampaignErrorCode.ALREADY_BLOCKED);
        }

        String reason = request.reason();
        campaign.setModerationStatus(AdModerationStatus.BLOCKED);
        campaign.setStatus(AdCampaignStatus.BLOCKED);
        campaign.setBlockedReason(reason);
        campaignRepository.save(campaign);

        moderationLogRepository.save(AdCampaignModerationLog.builder()
                .campaignId(campaignId)
                .moderatorUserId(moderatorUserId)
                .action(AdModerationAction.BLOCKED)
                .reason(reason)
                .build());
    }

    /**
     * キャンペーンを ID で検索し、存在しなければ {@link AdCampaignErrorCode#AD_CAMPAIGN_NOT_FOUND} を投げる。
     *
     * <p>本メソッドは {@code organization_id} 絞り込みを行わない (SYSTEM_ADMIN 越テナント前提)。</p>
     */
    private AdMessagingCampaign findCampaignOrThrow(UUID campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND));
    }

    /** ハッシュコレクション化のためのユーティリティ (テスト容易化用に List 経由公開)。 */
    static List<AdModerationStatus> reviewQueueStatusesForTest() {
        return List.copyOf(REVIEW_QUEUE_STATUSES);
    }
}
