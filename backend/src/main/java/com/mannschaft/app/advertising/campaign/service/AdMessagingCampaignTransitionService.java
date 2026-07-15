package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.dto.BlockCampaignRequest;
import com.mannschaft.app.advertising.campaign.dto.CampaignDetailResponse;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.mapper.AdMessagingCampaignMapper;
import com.mannschaft.app.advertising.campaign.repository.AdAudienceSegmentRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignChannelRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.advertising.service.AdvertiserAccountService;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
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
 *   <li>{@code submit} : DRAFT → REVIEW (AUTO_BLOCK 検出時のみ BLOCKED)</li>
 *   <li>{@code cancel} : DRAFT/REVIEW → CANCELLED</li>
 *   <li>{@link #approve(UUID, Long)} : REVIEW → APPROVED (既存サービスへ委譲)</li>
 *   <li>{@link #block(UUID, Long, BlockCampaignRequest)} : 任意 → BLOCKED (既存サービスへ委譲)</li>
 *   <li>{@code launch} : APPROVED → SCHEDULED または DELIVERING</li>
 *   <li>{@code pause}  : DELIVERING → PAUSED</li>
 *   <li>{@code resume} : PAUSED → DELIVERING (credit_limit 再判定)</li>
 * </ul>
 *
 * <p>F09.17 Phase 11-d-2: scope ベース化。
 * 所有者向け遷移 (submit/cancel/launch/pause/resume) は {@code (ScopeType, scopeId)} で
 * テナント越境を検証し、IDOR 対策として違反時は 404 にマップする。
 * approve/block は SYSTEM_ADMIN 専用のため Controller 層の {@code @PreAuthorize} に委ねる。
 * 旧 {@code organizationId} 引数の overload は {@code @Deprecated} で残置し、
 * 内部で {@code (ORGANIZATION, organizationId)} に詰め替えて新シグネチャに委譲する。
 * Phase 11-e で旧 overload を物理削除予定。</p>
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
    private final AuditLogService auditLogService;

    // ─────────────────────────────────────────────
    // F09.19.7 §10.5 / AC-7.5: メッセージ型状態遷移の監査ログイベント名（F10.3）
    // ─────────────────────────────────────────────
    static final String AUDIT_CAMPAIGN_SUBMITTED = "CAMPAIGN_SUBMITTED";
    static final String AUDIT_CAMPAIGN_AUTO_BLOCKED = "CAMPAIGN_AUTO_BLOCKED";
    static final String AUDIT_CAMPAIGN_CANCELLED = "CAMPAIGN_CANCELLED";
    static final String AUDIT_CAMPAIGN_LAUNCHED = "CAMPAIGN_LAUNCHED";
    static final String AUDIT_CAMPAIGN_PAUSED = "CAMPAIGN_PAUSED";
    static final String AUDIT_CAMPAIGN_RESUMED = "CAMPAIGN_RESUMED";

    // ─────────────────────────────────────────────
    // 所有者向け遷移 (scope ベース)
    // ─────────────────────────────────────────────

    /**
     * DRAFT → REVIEW 遷移 (Phase 11-d-2)。
     */
    @Transactional
    public CampaignDetailResponse submit(
            UUID campaignId, ScopeType scopeType, Long scopeId, Long requesterUserId) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId, scopeType, scopeId);
        requireStatus(campaign, AdCampaignStatus.DRAFT);
        validateSubmitPrerequisites(campaignId);

        // 既存の自動 NG 検知は内部で moderation_status と
        // (AUTO_BLOCK 時のみ) status=BLOCKED まで更新する。
        moderationService.autoFlagOnSubmit(campaignId);

        AdMessagingCampaign refreshed = findCampaignOrThrow(campaignId, scopeType, scopeId);
        if (refreshed.getStatus() != AdCampaignStatus.BLOCKED) {
            refreshed.setStatus(AdCampaignStatus.REVIEW);
            campaignRepository.save(refreshed);
        }

        log.info("CAMPAIGN_SUBMITTED campaignId={} userId={} resultStatus={} moderationStatus={}",
                campaignId, requesterUserId, refreshed.getStatus(), refreshed.getModerationStatus());

        // F09.19.7 §10.5: 提出イベント。submit 時点の自動 NG 検知（autoFlagOnSubmit）で BLOCKED に
        // なった場合は CAMPAIGN_AUTO_BLOCKED も発火する。
        // ※ 通報 3 件による自動ブロック（F09.19.9・AdReportService）は別トリガのため二重発火しない。
        fireAudit(AUDIT_CAMPAIGN_SUBMITTED, campaignId, requesterUserId, refreshed);
        if (refreshed.getStatus() == AdCampaignStatus.BLOCKED) {
            fireAudit(AUDIT_CAMPAIGN_AUTO_BLOCKED, campaignId, requesterUserId, refreshed);
        }
        return buildDetail(refreshed);
    }

    /**
     * DRAFT/REVIEW → CANCELLED 遷移 (Phase 11-d-2)。
     */
    @Transactional
    public CampaignDetailResponse cancel(
            UUID campaignId, ScopeType scopeType, Long scopeId, Long requesterUserId) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId, scopeType, scopeId);
        if (!CANCELLABLE_STATUSES.contains(campaign.getStatus())) {
            throw new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_INVALID_STATE);
        }
        campaign.setStatus(AdCampaignStatus.CANCELLED);
        campaignRepository.save(campaign);

        log.info("CAMPAIGN_CANCELLED campaignId={} userId={}", campaignId, requesterUserId);
        fireAudit(AUDIT_CAMPAIGN_CANCELLED, campaignId, requesterUserId, campaign);
        return buildDetail(campaign);
    }

    /**
     * APPROVED → SCHEDULED または APPROVED → DELIVERING 遷移 (Phase 11-d-2)。
     */
    @Transactional
    public CampaignDetailResponse launch(
            UUID campaignId, ScopeType scopeType, Long scopeId, Long requesterUserId) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId, scopeType, scopeId);
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
        fireAudit(AUDIT_CAMPAIGN_LAUNCHED, campaignId, requesterUserId, campaign);
        return buildDetail(campaign);
    }

    /**
     * DELIVERING → PAUSED 遷移 (Phase 11-d-2)。
     */
    @Transactional
    public CampaignDetailResponse pause(
            UUID campaignId, ScopeType scopeType, Long scopeId, Long requesterUserId) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId, scopeType, scopeId);
        requireStatus(campaign, AdCampaignStatus.DELIVERING);
        campaign.setStatus(AdCampaignStatus.PAUSED);
        campaignRepository.save(campaign);

        log.info("CAMPAIGN_PAUSED campaignId={} userId={} reason=MANUAL", campaignId, requesterUserId);
        fireAudit(AUDIT_CAMPAIGN_PAUSED, campaignId, requesterUserId, campaign);
        return buildDetail(campaign);
    }

    /**
     * PAUSED → DELIVERING 遷移 (Phase 11-d-2、credit_limit 再判定)。
     */
    @Transactional
    public CampaignDetailResponse resume(
            UUID campaignId, ScopeType scopeType, Long scopeId, Long requesterUserId) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId, scopeType, scopeId);
        requireStatus(campaign, AdCampaignStatus.PAUSED);
        ensureCreditAvailable(campaign);
        campaign.setStatus(AdCampaignStatus.DELIVERING);
        campaignRepository.save(campaign);

        log.info("CAMPAIGN_RESUMED campaignId={} userId={}", campaignId, requesterUserId);
        fireAudit(AUDIT_CAMPAIGN_RESUMED, campaignId, requesterUserId, campaign);
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
    // 互換 API (Phase 11-e で削除予定)
    // ─────────────────────────────────────────────

    /** @deprecated Phase 11-d-2 で scope ベース化。 */
    @Deprecated
    @Transactional
    public CampaignDetailResponse submit(UUID campaignId, Long organizationId, Long requesterUserId) {
        return submit(campaignId, ScopeType.ORGANIZATION, organizationId, requesterUserId);
    }

    /** @deprecated Phase 11-d-2 で scope ベース化。 */
    @Deprecated
    @Transactional
    public CampaignDetailResponse cancel(UUID campaignId, Long organizationId, Long requesterUserId) {
        return cancel(campaignId, ScopeType.ORGANIZATION, organizationId, requesterUserId);
    }

    /** @deprecated Phase 11-d-2 で scope ベース化。 */
    @Deprecated
    @Transactional
    public CampaignDetailResponse launch(UUID campaignId, Long organizationId, Long requesterUserId) {
        return launch(campaignId, ScopeType.ORGANIZATION, organizationId, requesterUserId);
    }

    /** @deprecated Phase 11-d-2 で scope ベース化。 */
    @Deprecated
    @Transactional
    public CampaignDetailResponse pause(UUID campaignId, Long organizationId, Long requesterUserId) {
        return pause(campaignId, ScopeType.ORGANIZATION, organizationId, requesterUserId);
    }

    /** @deprecated Phase 11-d-2 で scope ベース化。 */
    @Deprecated
    @Transactional
    public CampaignDetailResponse resume(UUID campaignId, Long organizationId, Long requesterUserId) {
        return resume(campaignId, ScopeType.ORGANIZATION, organizationId, requesterUserId);
    }

    // ─────────────────────────────────────────────
    // private ヘルパー
    // ─────────────────────────────────────────────

    private AdMessagingCampaign findCampaignOrThrow(UUID campaignId, ScopeType scopeType, Long scopeId) {
        return campaignRepository
                .findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, scopeType, scopeId)
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

    /**
     * F09.19.7 §10.5 / AC-7.5: メッセージ型状態遷移の監査ログを fire-and-forget で記録する。
     *
     * <p>metadata は {@code {"campaign_id":"<uuid>"}} の最小構成。scope に応じて team_id /
     * organization_id を文脈として付与する。{@link AuditLogService#record} は {@code @Async} で
     * 失敗してもメイン処理を止めない。</p>
     *
     * @param eventType  監査イベント名
     * @param campaignId 対象キャンペーン
     * @param actorUserId 操作ユーザー（スケジューラ起因はシステムユーザーだが本サービスは常にユーザー操作）
     * @param campaign   scope 文脈解決用
     */
    private void fireAudit(String eventType, UUID campaignId, Long actorUserId, AdMessagingCampaign campaign) {
        Long teamId = campaign.getScopeType() == ScopeType.TEAM ? campaign.getScopeId() : null;
        Long orgId = campaign.getScopeType() == ScopeType.ORGANIZATION ? campaign.getScopeId() : null;
        String metadata = "{\"campaign_id\":\"" + campaignId + "\"}";
        auditLogService.record(eventType, actorUserId, null, teamId, orgId, null, null, null, metadata);
    }
}
