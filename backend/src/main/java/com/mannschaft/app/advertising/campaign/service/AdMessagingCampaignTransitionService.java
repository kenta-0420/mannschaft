package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.dto.BlockCampaignRequest;
import com.mannschaft.app.advertising.campaign.dto.CampaignDetailResponse;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.mapper.AdMessagingCampaignMapper;
import com.mannschaft.app.advertising.campaign.repository.AdAudienceSegmentRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignChannelRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.advertising.service.AdvertiserAccountService;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F09.17 Phase 11-b ε-A メッセージ型キャンペーン状態遷移サービス。
 *
 * <p>{@link AdMessagingCampaignService} (DRAFT CRUD) と {@link AdCampaignModerationService}
 * (手動承認/ブロック) を呼び出す薄い orchestration 層。
 * 設計書 §5「キャンペーン状態遷移マシン」に従い、以下の遷移を提供する:</p>
 *
 * <ul>
 *   <li>{@link #submit(UUID, Long, Long)} : DRAFT → REVIEW (AUTO_BLOCK 検出時のみ BLOCKED)</li>
 *   <li>{@link #cancel(UUID, Long, Long)} : DRAFT/REVIEW → CANCELLED</li>
 *   <li>{@link #approve(UUID, Long)}      : REVIEW → APPROVED (既存サービスへ委譲)</li>
 *   <li>{@link #block(UUID, Long, BlockCampaignRequest)} : 任意 → BLOCKED (既存サービスへ委譲)</li>
 *   <li>{@link #launch(UUID, Long, Long)} : APPROVED → SCHEDULED または DELIVERING</li>
 *   <li>{@link #pause(UUID, Long, Long)}  : DELIVERING → PAUSED</li>
 *   <li>{@link #resume(UUID, Long, Long)} : PAUSED → DELIVERING (credit_limit 再判定)</li>
 * </ul>
 *
 * <p>所有者向け遷移 (submit/cancel/launch/pause/resume) は {@code organization_id} で
 * テナント越境を検証し、IDOR 対策として違反時は 404 にマップする。
 * approve/block は SYSTEM_ADMIN 専用のため Controller 層の {@code @PreAuthorize} に委ねる。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class AdMessagingCampaignTransitionService {

    /** DRAFT/REVIEW 以外は cancel 不可。 */
    private static final Set<AdCampaignStatus> CANCELLABLE_STATUSES =
            Set.of(AdCampaignStatus.DRAFT, AdCampaignStatus.REVIEW);

    private final AdMessagingCampaignRepository campaignRepository;
    private final AdMessagingCampaignChannelRepository channelRepository;
    private final AdAudienceSegmentRepository segmentRepository;
    private final AdMessagingCampaignMapper mapper;
    private final AdCampaignModerationService moderationService;
    private final AdvertiserAccountService advertiserAccountService;

    // ─────────────────────────────────────────────
    // 所有者向け遷移
    // ─────────────────────────────────────────────

    /**
     * DRAFT → REVIEW 遷移。
     *
     * <ol>
     *   <li>キャンペーン取得 + テナント検証 + status=DRAFT 確認</li>
     *   <li>必須項目検証 (少なくとも 1 channel + 1 audience segment)</li>
     *   <li>{@link AdCampaignModerationService#autoFlagOnSubmit(UUID)} で自動 NG 検知</li>
     *   <li>結果に応じて status を更新:
     *       <ul>
     *         <li>AUTO_BLOCK 検出: moderation_status=BLOCKED 済 → status=BLOCKED もすでにセット済 (autoFlagOnSubmit 内)</li>
     *         <li>AUTO_FLAG または AUTO_PASS: moderation_status のみ更新済 → status=REVIEW に更新</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p>F10.3 監査ログ: ε-C で統合予定 (TODO)。</p>
     */
    @Transactional
    public CampaignDetailResponse submit(UUID campaignId, Long organizationId, Long requesterUserId) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId, organizationId);
        requireStatus(campaign, AdCampaignStatus.DRAFT);
        validateSubmitPrerequisites(campaignId);

        // 既存の自動 NG 検知 (d25684f87) は内部で moderation_status と
        // (AUTO_BLOCK 時のみ) status=BLOCKED まで更新する。
        moderationService.autoFlagOnSubmit(campaignId);

        // autoFlagOnSubmit 内の更新を読み戻す
        AdMessagingCampaign refreshed = findCampaignOrThrow(campaignId, organizationId);
        if (refreshed.getStatus() != AdCampaignStatus.BLOCKED) {
            refreshed.setStatus(AdCampaignStatus.REVIEW);
            campaignRepository.save(refreshed);
        }

        log.info("CAMPAIGN_SUBMITTED campaignId={} userId={} resultStatus={} moderationStatus={}",
                campaignId, requesterUserId, refreshed.getStatus(), refreshed.getModerationStatus());

        // TODO(F09.17 ε-C): F10.3 監査ログイベント CAMPAIGN_SUBMITTED / CAMPAIGN_AUTO_BLOCKED を発火する
        return buildDetail(refreshed);
    }

    /**
     * DRAFT/REVIEW → CANCELLED 遷移。
     */
    @Transactional
    public CampaignDetailResponse cancel(UUID campaignId, Long organizationId, Long requesterUserId) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId, organizationId);
        if (!CANCELLABLE_STATUSES.contains(campaign.getStatus())) {
            throw new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_INVALID_STATE);
        }
        campaign.setStatus(AdCampaignStatus.CANCELLED);
        campaignRepository.save(campaign);

        log.info("CAMPAIGN_CANCELLED campaignId={} userId={}", campaignId, requesterUserId);
        // TODO(F09.17 ε-C): F10.3 監査ログイベント CAMPAIGN_CANCELLED を発火する
        return buildDetail(campaign);
    }

    /**
     * APPROVED → SCHEDULED または APPROVED → DELIVERING 遷移。
     *
     * <p>{@code starts_at > now} なら SCHEDULED、{@code starts_at <= now} なら DELIVERING。
     * 同期 credit_limit 判定で超過なら {@link AdCampaignErrorCode#AD_CAMPAIGN_CREDIT_EXCEEDED}。</p>
     */
    @Transactional
    public CampaignDetailResponse launch(UUID campaignId, Long organizationId, Long requesterUserId) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId, organizationId);
        requireStatus(campaign, AdCampaignStatus.APPROVED);
        ensureCreditAvailable(campaign);

        LocalDateTime now = LocalDateTime.now();
        AdCampaignStatus next = campaign.getStartsAt().isAfter(now)
                ? AdCampaignStatus.SCHEDULED
                : AdCampaignStatus.DELIVERING;
        campaign.setStatus(next);
        campaignRepository.save(campaign);

        log.info("CAMPAIGN_LAUNCHED campaignId={} userId={} nextStatus={}",
                campaignId, requesterUserId, next);
        // TODO(F09.17 ε-C): F10.3 監査ログイベント CAMPAIGN_LAUNCHED を発火する
        return buildDetail(campaign);
    }

    /**
     * DELIVERING → PAUSED 遷移 (広告主の手動操作)。
     */
    @Transactional
    public CampaignDetailResponse pause(UUID campaignId, Long organizationId, Long requesterUserId) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId, organizationId);
        requireStatus(campaign, AdCampaignStatus.DELIVERING);
        campaign.setStatus(AdCampaignStatus.PAUSED);
        campaignRepository.save(campaign);

        log.info("CAMPAIGN_PAUSED campaignId={} userId={} reason=MANUAL", campaignId, requesterUserId);
        // TODO(F09.17 ε-C): F10.3 監査ログイベント CAMPAIGN_PAUSED を発火する
        return buildDetail(campaign);
    }

    /**
     * PAUSED → DELIVERING 遷移 (広告主の手動 resume)。pause 中に他キャンペーンで credit_limit を使い果たした
     * 可能性があるため再判定を行う。
     */
    @Transactional
    public CampaignDetailResponse resume(UUID campaignId, Long organizationId, Long requesterUserId) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId, organizationId);
        requireStatus(campaign, AdCampaignStatus.PAUSED);
        ensureCreditAvailable(campaign);
        campaign.setStatus(AdCampaignStatus.DELIVERING);
        campaignRepository.save(campaign);

        log.info("CAMPAIGN_RESUMED campaignId={} userId={}", campaignId, requesterUserId);
        // TODO(F09.17 ε-C): F10.3 監査ログイベント CAMPAIGN_RESUMED を発火する
        return buildDetail(campaign);
    }

    // ─────────────────────────────────────────────
    // SYSTEM_ADMIN 向け遷移 (既存サービスへ委譲)
    // ─────────────────────────────────────────────

    /**
     * REVIEW → APPROVED 遷移。Phase 11-a の {@link AdCampaignModerationService#approve(UUID, Long)} に委譲。
     */
    @Transactional
    public void approve(UUID campaignId, Long moderatorUserId) {
        moderationService.approve(campaignId, moderatorUserId);
    }

    /**
     * 任意状態 → BLOCKED 遷移。Phase 11-a の
     * {@link AdCampaignModerationService#block(UUID, Long, BlockCampaignRequest)} に委譲。
     */
    @Transactional
    public void block(UUID campaignId, Long moderatorUserId, BlockCampaignRequest request) {
        moderationService.block(campaignId, moderatorUserId, request);
    }

    // ─────────────────────────────────────────────
    // private ヘルパー
    // ─────────────────────────────────────────────

    private AdMessagingCampaign findCampaignOrThrow(UUID campaignId, Long organizationId) {
        return campaignRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(campaignId, organizationId)
                .orElseThrow(() -> new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND));
    }

    private void requireStatus(AdMessagingCampaign campaign, AdCampaignStatus expected) {
        if (campaign.getStatus() != expected) {
            throw new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_INVALID_STATE);
        }
    }

    /**
     * submit 時の必須項目検証。
     * <ul>
     *   <li>少なくとも 1 つの channel が登録されていること</li>
     *   <li>少なくとも 1 つの INCLUDE audience segment が登録されていること</li>
     * </ul>
     */
    private void validateSubmitPrerequisites(UUID campaignId) {
        List<AdMessagingCampaignChannel> channels = channelRepository.findByCampaignId(campaignId);
        if (channels.isEmpty()) {
            throw new BusinessException(AdCampaignErrorCode.AD_CHANNEL_REQUIRED);
        }
        List<AdAudienceSegment> segments = segmentRepository.findByCampaignId(campaignId);
        if (segments.isEmpty()) {
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
    }

    /**
     * credit_limit 同期判定。{@link AdvertiserAccountService#canAcceptNewCampaign} に委譲。
     */
    private void ensureCreditAvailable(AdMessagingCampaign campaign) {
        boolean ok = advertiserAccountService.canAcceptNewCampaign(
                campaign.getAdvertiserAccountId(), campaign.getTotalBudgetYen());
        if (!ok) {
            throw new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_CREDIT_EXCEEDED);
        }
    }

    private CampaignDetailResponse buildDetail(AdMessagingCampaign campaign) {
        List<AdMessagingCampaignChannel> channels = channelRepository.findByCampaignId(campaign.getId());
        List<AdAudienceSegment> segments = segmentRepository.findByCampaignId(campaign.getId());
        return mapper.toDetail(campaign, channels, segments);
    }
}
