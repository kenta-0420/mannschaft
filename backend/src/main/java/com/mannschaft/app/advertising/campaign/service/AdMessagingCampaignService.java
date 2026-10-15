package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.dto.AudienceConfigRequest;
import com.mannschaft.app.advertising.campaign.dto.AudienceSegmentRequest;
import com.mannschaft.app.advertising.campaign.dto.AudienceSegmentResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignChannelRequest;
import com.mannschaft.app.advertising.campaign.dto.CampaignChannelResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignDetailResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignListItemResponse;
import com.mannschaft.app.advertising.campaign.dto.CreateCampaignRequest;
import com.mannschaft.app.advertising.campaign.dto.EstimatedReachRangeResponse;
import com.mannschaft.app.advertising.campaign.dto.UpdateCampaignRequest;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.mapper.AdMessagingCampaignMapper;
import com.mannschaft.app.advertising.campaign.repository.AdAudienceSegmentRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignChannelRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.membership.domain.ScopeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F09.17 メッセージ型キャンペーン DRAFT CRUD サービス (Phase 11-a)。
 *
 * <p>本 Service は以下の責務を担う:
 * <ul>
 *   <li>キャンペーンの DRAFT 作成・参照・更新・論理削除</li>
 *   <li>チャネル別コンテンツの追加・更新・削除</li>
 *   <li>ターゲティング条件 (audience segments) の全件 replace 設定</li>
 * </ul>
 * submit / launch / pause / preview / report 等の状態遷移系・分析系は Phase 11-b で実装する。</p>
 *
 * <p>F09.17 Phase 11-d-2: scope ベース化。
 * 引数 {@code organizationId} を {@code (ScopeType scopeType, Long scopeId)} に置き換えた。
 * 越境検出時は {@link AdCampaignErrorCode#AD_CAMPAIGN_NOT_FOUND} を返して IDOR 対策とする
 * (旧コード互換のため一部メソッドで {@code AD_CAMPAIGN_FORBIDDEN_TENANT} は使わない)。</p>
 *
 * <p>F09.17 Phase 11-e: 旧 organizationId overload を物理削除済み。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdMessagingCampaignService {

    private final AdMessagingCampaignRepository campaignRepository;
    private final AdMessagingCampaignChannelRepository channelRepository;
    private final AdAudienceSegmentRepository segmentRepository;
    private final AdMessagingCampaignMapper mapper;
    private final AdAudienceResolver audienceResolver;

    // ─────────────────────────────────────────────
    // 一覧 / 詳細 (scope ベース)
    // ─────────────────────────────────────────────

    /**
     * scope が所有するキャンペーン一覧を取得する (Phase 11-d-2)。
     */
    public Page<CampaignListItemResponse> listCampaigns(
            ScopeType scopeType, Long scopeId, AdCampaignStatus status, Pageable pageable) {
        Page<AdMessagingCampaign> page =
                campaignRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(scopeType, scopeId, pageable);
        return page.map(entity -> {
            if (status != null && entity.getStatus() != status) {
                return null;
            }
            return mapper.toListItem(entity);
        });
    }

    /**
     * キャンペーン詳細を取得する (Phase 11-d-2)。
     */
    public CampaignDetailResponse getCampaign(UUID campaignId, ScopeType scopeType, Long scopeId) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId, scopeType, scopeId);
        List<AdMessagingCampaignChannel> channels = channelRepository.findByCampaignId(campaignId);
        List<AdAudienceSegment> segments = segmentRepository.findByCampaignId(campaignId);
        return mapper.toDetail(campaign, channels, segments);
    }

    /**
     * F09.19.7 §10.2 / AC-7.2: 推定リーチのレンジを返す（ウィザード Step4 用）。
     *
     * <p>まず {@code (scopeType, scopeId)} で当該キャンペーンの所有を検証する（越境は
     * {@link AdCampaignErrorCode#AD_CAMPAIGN_NOT_FOUND}）。所有確認後に
     * {@link AdAudienceResolver#estimateReach} で個別ユーザーを特定しないレンジ表示を返す。</p>
     *
     * @param campaignId 対象キャンペーン
     * @param scopeType  スコープ種別
     * @param scopeId    スコープ ID
     * @return 推定リーチのレンジ（range / label）
     */
    public EstimatedReachRangeResponse preview(UUID campaignId, ScopeType scopeType, Long scopeId) {
        findCampaignOrThrow(campaignId, scopeType, scopeId);
        return audienceResolver.estimateReach(campaignId);
    }

    // ─────────────────────────────────────────────
    // 作成 / 更新 / 削除 (scope ベース)
    // ─────────────────────────────────────────────

    /**
     * 新規キャンペーンを DRAFT 状態で作成する (Phase 11-d-2)。
     */
    @Transactional
    public CampaignDetailResponse createCampaign(
            ScopeType scopeType,
            Long scopeId,
            Long advertiserAccountId,
            Long createdByUserId,
            CreateCampaignRequest request) {
        validateScheduleWindow(request.startsAt(), request.endsAt());

        AdMessagingCampaign entity = AdMessagingCampaign.builder()
                .advertiserAccountId(advertiserAccountId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.name())
                .status(AdCampaignStatus.DRAFT)
                .moderationStatus(AdModerationStatus.PENDING)
                .totalBudgetYen(request.totalBudgetYen())
                .consumedBudgetYen(0L)
                .frequencyCapOverride(request.frequencyCapOverride())
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
                .scheduledTimezone(request.scheduledTimezone())
                .createdByUserId(createdByUserId)
                .build();

        AdMessagingCampaign saved = campaignRepository.save(entity);
        return mapper.toDetail(saved, List.of(), List.of());
    }

    /**
     * DRAFT 状態のキャンペーンを更新する (Phase 11-d-2)。
     */
    @Transactional
    public CampaignDetailResponse updateCampaign(
            UUID campaignId, ScopeType scopeType, Long scopeId, UpdateCampaignRequest request) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId, scopeType, scopeId);
        requireDraft(campaign);
        validateScheduleWindow(request.startsAt(), request.endsAt());

        campaign.setName(request.name());
        campaign.setTotalBudgetYen(request.totalBudgetYen());
        campaign.setStartsAt(request.startsAt());
        campaign.setEndsAt(request.endsAt());
        campaign.setScheduledTimezone(request.scheduledTimezone());
        campaign.setFrequencyCapOverride(request.frequencyCapOverride());

        AdMessagingCampaign saved = campaignRepository.save(campaign);
        List<AdMessagingCampaignChannel> channels = channelRepository.findByCampaignId(campaignId);
        List<AdAudienceSegment> segments = segmentRepository.findByCampaignId(campaignId);
        return mapper.toDetail(saved, channels, segments);
    }

    /**
     * DRAFT 状態のキャンペーンを論理削除する (Phase 11-d-2)。
     */
    @Transactional
    public void softDeleteCampaign(UUID campaignId, ScopeType scopeType, Long scopeId) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId, scopeType, scopeId);
        requireDraft(campaign);
        campaign.softDelete();
        campaignRepository.save(campaign);
    }

    // ─────────────────────────────────────────────
    // チャネル CRUD (scope ベース)
    // ─────────────────────────────────────────────

    /**
     * キャンペーンにチャネル別コンテンツを追加する (Phase 11-d-2)。
     */
    @Transactional
    public CampaignChannelResponse addChannel(
            UUID campaignId, ScopeType scopeType, Long scopeId, CampaignChannelRequest request) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId, scopeType, scopeId);
        requireDraft(campaign);
        validateChannelRequest(request);

        channelRepository
                .findByCampaignIdAndChannelTypeAndLocale(
                        campaignId, request.channelType(), request.locale())
                .ifPresent(existing -> {
                    throw new BusinessException(AdCampaignErrorCode.AD_CHANNEL_DUPLICATE);
                });

        AdMessagingCampaignChannel entity = AdMessagingCampaignChannel.builder()
                .campaignId(campaignId)
                .channelType(request.channelType())
                .locale(request.locale())
                .subject(request.subject())
                .bodyMarkdown(request.bodyMarkdown())
                .imageUrl(request.imageUrl())
                .ctaLabel(request.ctaLabel())
                .ctaUrl(request.ctaUrl())
                .bannerCreativeId(request.bannerCreativeId())
                .build();

        AdMessagingCampaignChannel saved = channelRepository.save(entity);
        return mapper.toChannelResponse(saved);
    }

    /**
     * チャネル別コンテンツを更新する (Phase 11-d-2)。
     */
    @Transactional
    public CampaignChannelResponse updateChannel(
            UUID channelId, ScopeType scopeType, Long scopeId, CampaignChannelRequest request) {
        AdMessagingCampaignChannel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND));

        AdMessagingCampaign campaign = findCampaignOrThrow(channel.getCampaignId(), scopeType, scopeId);
        requireDraft(campaign);
        validateChannelRequest(request);

        boolean keyChanged = channel.getChannelType() != request.channelType()
                || !channel.getLocale().equals(request.locale());
        if (keyChanged) {
            channelRepository
                    .findByCampaignIdAndChannelTypeAndLocale(
                            channel.getCampaignId(), request.channelType(), request.locale())
                    .filter(existing -> !existing.getId().equals(channel.getId()))
                    .ifPresent(existing -> {
                        throw new BusinessException(AdCampaignErrorCode.AD_CHANNEL_DUPLICATE);
                    });
        }

        channel.setChannelType(request.channelType());
        channel.setLocale(request.locale());
        channel.setSubject(request.subject());
        channel.setBodyMarkdown(request.bodyMarkdown());
        channel.setImageUrl(request.imageUrl());
        channel.setCtaLabel(request.ctaLabel());
        channel.setCtaUrl(request.ctaUrl());
        channel.setBannerCreativeId(request.bannerCreativeId());

        AdMessagingCampaignChannel saved = channelRepository.save(channel);
        return mapper.toChannelResponse(saved);
    }

    /**
     * チャネル別コンテンツを物理削除する (Phase 11-d-2)。
     */
    @Transactional
    public void removeChannel(UUID channelId, ScopeType scopeType, Long scopeId) {
        AdMessagingCampaignChannel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND));

        AdMessagingCampaign campaign = findCampaignOrThrow(channel.getCampaignId(), scopeType, scopeId);
        requireDraft(campaign);

        channelRepository.delete(channel);
    }

    // ─────────────────────────────────────────────
    // ターゲティング設定 (scope ベース)
    // ─────────────────────────────────────────────

    /**
     * ターゲティングセグメントを全件 replace する (Phase 11-d-2)。
     */
    @Transactional
    public List<AudienceSegmentResponse> setAudience(
            UUID campaignId, ScopeType scopeType, Long scopeId, AudienceConfigRequest request) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId, scopeType, scopeId);
        requireDraft(campaign);

        // 同期: 既存セグメントを全削除してからリクエストを INSERT
        segmentRepository.deleteByCampaignId(campaignId);

        List<AdAudienceSegment> toInsert = request.segments().stream()
                .map(seg -> buildSegmentEntity(campaignId, seg))
                .toList();
        List<AdAudienceSegment> saved = segmentRepository.saveAll(toInsert);

        return saved.stream().map(mapper::toSegmentResponse).toList();
    }

    // ─────────────────────────────────────────────
    // private ヘルパー
    // ─────────────────────────────────────────────

    private AdMessagingCampaign findCampaignOrThrow(UUID campaignId, ScopeType scopeType, Long scopeId) {
        AdMessagingCampaign campaign = campaignRepository
                .findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, scopeType, scopeId)
                .orElse(null);
        if (campaign == null) {
            // IDOR 対策: 存在しない場合と他テナント所有の場合を区別しない（どちらも 404 にマップ）
            throw new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND);
        }
        return campaign;
    }

    private void requireDraft(AdMessagingCampaign campaign) {
        if (campaign.getStatus() != AdCampaignStatus.DRAFT) {
            throw new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_NOT_EDITABLE);
        }
    }

    private void validateScheduleWindow(LocalDateTime startsAt, LocalDateTime endsAt) {
        if (!startsAt.isBefore(endsAt)) {
            throw new BusinessException(
                    AdCampaignErrorCode.AD_AUDIENCE_INVALID,
                    List.of(new ErrorResponse.FieldError(
                            "endsAt", "endsAt は startsAt より後の日時を指定してください")));
        }
    }

    private void validateChannelRequest(CampaignChannelRequest request) {
        if (request.channelType() == AdChannelType.BANNER && request.bannerCreativeId() == null) {
            throw new BusinessException(
                    AdCampaignErrorCode.AD_AUDIENCE_INVALID,
                    List.of(new ErrorResponse.FieldError(
                            "bannerCreativeId", "BANNER チャネルでは bannerCreativeId が必須です")));
        }
    }

    private AdAudienceSegment buildSegmentEntity(UUID campaignId, AudienceSegmentRequest seg) {
        return AdAudienceSegment.builder()
                .campaignId(campaignId)
                .segmentType(seg.segmentType())
                .segmentValue(mapper.serializeSegmentValue(seg.segmentValue()))
                .inclusionMode(seg.inclusionMode())
                .build();
    }
}
