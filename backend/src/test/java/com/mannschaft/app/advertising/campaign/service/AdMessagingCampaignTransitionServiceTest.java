package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.dto.AudienceSegmentResponse;
import com.mannschaft.app.advertising.campaign.dto.BlockCampaignRequest;
import com.mannschaft.app.advertising.campaign.dto.CampaignChannelResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignDetailResponse;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentInclusionMode;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.mapper.AdMessagingCampaignMapper;
import com.mannschaft.app.advertising.campaign.repository.AdAudienceSegmentRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignChannelRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.advertising.service.AdvertiserAccountService;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F09.17 Phase 11-b ε-A {@link AdMessagingCampaignTransitionService} 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdMessagingCampaignTransitionService 単体テスト")
class AdMessagingCampaignTransitionServiceTest {

    @Mock private AdMessagingCampaignRepository campaignRepository;
    @Mock private AdMessagingCampaignChannelRepository channelRepository;
    @Mock private AdAudienceSegmentRepository segmentRepository;
    @Mock private AdMessagingCampaignMapper mapper;
    @Mock private AdCampaignModerationService moderationService;
    @Mock private AdvertiserAccountService advertiserAccountService;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private AdMessagingCampaignTransitionService service;

    private static final Long ORG_ID = 1L;
    private static final Long ADVERTISER_ID = 100L;
    private static final Long REQUESTER_USER_ID = 10L;
    private static final Long MODERATOR_USER_ID = 999L;
    private UUID campaignId;

    @BeforeEach
    void setUp() {
        campaignId = UUID.randomUUID();
        // mapper.toDetail はデフォルトの空 DetailResponse を返す
        given(mapper.toDetail(any(), any(), any())).willReturn(emptyDetail());
        // save は引数 echo
        willAnswer(inv -> inv.getArgument(0)).given(campaignRepository).save(any());
    }

    private CampaignDetailResponse emptyDetail() {
        return new CampaignDetailResponse(
                campaignId, ADVERTISER_ID, "test", AdCampaignStatus.DRAFT,
                AdModerationStatus.PENDING, null, 100_000L, 0L,
                LocalDateTime.now(), LocalDateTime.now().plusDays(7),
                "Asia/Tokyo", null, LocalDateTime.now(), LocalDateTime.now(),
                List.<CampaignChannelResponse>of(), List.<AudienceSegmentResponse>of());
    }

    private AdMessagingCampaign buildCampaign(AdCampaignStatus status) {
        return buildCampaign(status, AdModerationStatus.PENDING, LocalDateTime.now().minusHours(1));
    }

    private AdMessagingCampaign buildCampaign(AdCampaignStatus status, AdModerationStatus moderationStatus,
                                              LocalDateTime startsAt) {
        AdMessagingCampaign campaign = AdMessagingCampaign.builder()
                .advertiserAccountId(ADVERTISER_ID)
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(ORG_ID)
                .name("テストキャンペーン")
                .status(status)
                .totalBudgetYen(50_000L)
                .consumedBudgetYen(0L)
                .startsAt(startsAt)
                .endsAt(startsAt.plusDays(7))
                .scheduledTimezone("Asia/Tokyo")
                .moderationStatus(moderationStatus)
                .createdByUserId(REQUESTER_USER_ID)
                .createdAt(LocalDateTime.now().minusHours(1))
                .updatedAt(LocalDateTime.now().minusHours(1))
                .build();
        campaign.setId(campaignId);
        return campaign;
    }

    private AdMessagingCampaignChannel buildChannel() {
        return AdMessagingCampaignChannel.builder()
                .campaignId(campaignId)
                .channelType(AdChannelType.ANNOUNCEMENT)
                .locale("ja")
                .bodyMarkdown("クリーンな本文")
                .build();
    }

    private AdAudienceSegment buildSegment() {
        return AdAudienceSegment.builder()
                .campaignId(campaignId)
                .segmentType(AdSegmentType.LOCALE)
                .segmentValue("{\"locales\":[\"ja\"]}")
                .inclusionMode(AdSegmentInclusionMode.INCLUDE)
                .build();
    }

    // ─────────────────────────────────────────────
    // submit()
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("submit: AUTO_PASS 結果なら status=REVIEW + moderation_status=AUTO_PASSED")
    void submit_AUTO_PASS_REVIEWに遷移() {
        AdMessagingCampaign draft = buildCampaign(AdCampaignStatus.DRAFT);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(draft));
        given(channelRepository.findByCampaignId(campaignId)).willReturn(List.of(buildChannel()));
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of(buildSegment()));
        // autoFlagOnSubmit 内で moderation_status を AUTO_PASSED にセットしたと模倣
        willAnswer(inv -> {
            draft.setModerationStatus(AdModerationStatus.AUTO_PASSED);
            return null;
        }).given(moderationService).autoFlagOnSubmit(campaignId);

        service.submit(campaignId, ORG_ID, REQUESTER_USER_ID);

        verify(moderationService).autoFlagOnSubmit(campaignId);
        assertThat(draft.getStatus()).isEqualTo(AdCampaignStatus.REVIEW);
        assertThat(draft.getModerationStatus()).isEqualTo(AdModerationStatus.AUTO_PASSED);
    }

    @Test
    @DisplayName("submit: AUTO_FLAGGED 結果でも status=REVIEW に遷移（人間レビュー待ち）")
    void submit_AUTO_FLAGGED_REVIEWに遷移() {
        AdMessagingCampaign draft = buildCampaign(AdCampaignStatus.DRAFT);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(draft));
        given(channelRepository.findByCampaignId(campaignId)).willReturn(List.of(buildChannel()));
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of(buildSegment()));
        willAnswer(inv -> {
            draft.setModerationStatus(AdModerationStatus.AUTO_FLAGGED);
            return null;
        }).given(moderationService).autoFlagOnSubmit(campaignId);

        service.submit(campaignId, ORG_ID, REQUESTER_USER_ID);

        assertThat(draft.getStatus()).isEqualTo(AdCampaignStatus.REVIEW);
        assertThat(draft.getModerationStatus()).isEqualTo(AdModerationStatus.AUTO_FLAGGED);
    }

    @Test
    @DisplayName("submit: AUTO_BLOCK 結果なら status=BLOCKED を維持 (REVIEW へ遷移しない)")
    void submit_AUTO_BLOCK_BLOCKEDのまま() {
        AdMessagingCampaign draft = buildCampaign(AdCampaignStatus.DRAFT);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(draft));
        given(channelRepository.findByCampaignId(campaignId)).willReturn(List.of(buildChannel()));
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of(buildSegment()));
        // autoFlagOnSubmit が status=BLOCKED + moderation_status=BLOCKED をセットしたと模倣
        willAnswer(inv -> {
            draft.setStatus(AdCampaignStatus.BLOCKED);
            draft.setModerationStatus(AdModerationStatus.BLOCKED);
            draft.setBlockedReason("自動 NG 検知によりブロック: ...");
            return null;
        }).given(moderationService).autoFlagOnSubmit(campaignId);

        service.submit(campaignId, ORG_ID, REQUESTER_USER_ID);

        assertThat(draft.getStatus()).isEqualTo(AdCampaignStatus.BLOCKED);
        assertThat(draft.getModerationStatus()).isEqualTo(AdModerationStatus.BLOCKED);
    }

    @Test
    @DisplayName("submit: status=DRAFT 以外は AD_CAMPAIGN_INVALID_STATE")
    void submit_DRAFT以外は不正() {
        AdMessagingCampaign review = buildCampaign(AdCampaignStatus.REVIEW);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(review));

        assertThatThrownBy(() -> service.submit(campaignId, ORG_ID, REQUESTER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_INVALID_STATE);
        verify(moderationService, never()).autoFlagOnSubmit(any());
    }

    @Test
    @DisplayName("submit: チャネル未登録は AD_CHANNEL_REQUIRED")
    void submit_チャネルなしは拒否() {
        AdMessagingCampaign draft = buildCampaign(AdCampaignStatus.DRAFT);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(draft));
        given(channelRepository.findByCampaignId(campaignId)).willReturn(List.of());

        assertThatThrownBy(() -> service.submit(campaignId, ORG_ID, REQUESTER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_CHANNEL_REQUIRED);
    }

    @Test
    @DisplayName("submit: セグメント未登録は AD_AUDIENCE_INVALID")
    void submit_セグメントなしは拒否() {
        AdMessagingCampaign draft = buildCampaign(AdCampaignStatus.DRAFT);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(draft));
        given(channelRepository.findByCampaignId(campaignId)).willReturn(List.of(buildChannel()));
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of());

        assertThatThrownBy(() -> service.submit(campaignId, ORG_ID, REQUESTER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    // ─────────────────────────────────────────────
    // cancel()
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("cancel: DRAFT → CANCELLED")
    void cancel_DRAFTから成功() {
        AdMessagingCampaign draft = buildCampaign(AdCampaignStatus.DRAFT);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(draft));

        service.cancel(campaignId, ORG_ID, REQUESTER_USER_ID);

        assertThat(draft.getStatus()).isEqualTo(AdCampaignStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancel: REVIEW → CANCELLED")
    void cancel_REVIEWから成功() {
        AdMessagingCampaign review = buildCampaign(AdCampaignStatus.REVIEW);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(review));

        service.cancel(campaignId, ORG_ID, REQUESTER_USER_ID);

        assertThat(review.getStatus()).isEqualTo(AdCampaignStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancel: DELIVERING など対象外は AD_CAMPAIGN_INVALID_STATE")
    void cancel_対象外状態は拒否() {
        AdMessagingCampaign delivering = buildCampaign(AdCampaignStatus.DELIVERING);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(delivering));

        assertThatThrownBy(() -> service.cancel(campaignId, ORG_ID, REQUESTER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_INVALID_STATE);
    }

    // ─────────────────────────────────────────────
    // launch()
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("launch: starts_at <= now なら DELIVERING")
    void launch_開始時刻到達済なら配信中() {
        AdMessagingCampaign approved = buildCampaign(
                AdCampaignStatus.APPROVED, AdModerationStatus.APPROVED, LocalDateTime.now().minusHours(1));
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(approved));
        given(advertiserAccountService.canAcceptNewCampaign(ADVERTISER_ID, 50_000L)).willReturn(true);

        service.launch(campaignId, ORG_ID, REQUESTER_USER_ID);

        assertThat(approved.getStatus()).isEqualTo(AdCampaignStatus.DELIVERING);
    }

    @Test
    @DisplayName("launch: starts_at > now なら SCHEDULED")
    void launch_開始時刻未来ならスケジュール() {
        AdMessagingCampaign approved = buildCampaign(
                AdCampaignStatus.APPROVED, AdModerationStatus.APPROVED, LocalDateTime.now().plusDays(1));
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(approved));
        given(advertiserAccountService.canAcceptNewCampaign(ADVERTISER_ID, 50_000L)).willReturn(true);

        service.launch(campaignId, ORG_ID, REQUESTER_USER_ID);

        assertThat(approved.getStatus()).isEqualTo(AdCampaignStatus.SCHEDULED);
    }

    @Test
    @DisplayName("launch: credit_limit 超過は AD_CAMPAIGN_CREDIT_EXCEEDED")
    void launch_creditLimit超過() {
        AdMessagingCampaign approved = buildCampaign(
                AdCampaignStatus.APPROVED, AdModerationStatus.APPROVED, LocalDateTime.now().minusHours(1));
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(approved));
        given(advertiserAccountService.canAcceptNewCampaign(ADVERTISER_ID, 50_000L)).willReturn(false);

        assertThatThrownBy(() -> service.launch(campaignId, ORG_ID, REQUESTER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_CREDIT_EXCEEDED);
        // status は APPROVED のまま (BLOCKED で無いことを確認)
        assertThat(approved.getStatus()).isEqualTo(AdCampaignStatus.APPROVED);
    }

    @Test
    @DisplayName("launch: status=APPROVED 以外は AD_CAMPAIGN_INVALID_STATE")
    void launch_APPROVED以外は拒否() {
        AdMessagingCampaign draft = buildCampaign(AdCampaignStatus.DRAFT);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.launch(campaignId, ORG_ID, REQUESTER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_INVALID_STATE);
    }

    // ─────────────────────────────────────────────
    // pause() / resume()
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("pause: DELIVERING → PAUSED")
    void pause_配信中から一時停止() {
        AdMessagingCampaign delivering = buildCampaign(AdCampaignStatus.DELIVERING);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(delivering));

        service.pause(campaignId, ORG_ID, REQUESTER_USER_ID);

        assertThat(delivering.getStatus()).isEqualTo(AdCampaignStatus.PAUSED);
    }

    @Test
    @DisplayName("pause: DELIVERING 以外は AD_CAMPAIGN_INVALID_STATE")
    void pause_DELIVERING以外は拒否() {
        AdMessagingCampaign approved = buildCampaign(AdCampaignStatus.APPROVED);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.pause(campaignId, ORG_ID, REQUESTER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_INVALID_STATE);
    }

    @Test
    @DisplayName("resume: PAUSED → DELIVERING (credit_limit OK)")
    void resume_credit十分なら配信再開() {
        AdMessagingCampaign paused = buildCampaign(AdCampaignStatus.PAUSED);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(paused));
        given(advertiserAccountService.canAcceptNewCampaign(ADVERTISER_ID, 50_000L)).willReturn(true);

        service.resume(campaignId, ORG_ID, REQUESTER_USER_ID);

        assertThat(paused.getStatus()).isEqualTo(AdCampaignStatus.DELIVERING);
    }

    @Test
    @DisplayName("resume: credit_limit 不足は AD_CAMPAIGN_CREDIT_EXCEEDED で PAUSED 維持")
    void resume_credit不足ならPAUSEDまま() {
        AdMessagingCampaign paused = buildCampaign(AdCampaignStatus.PAUSED);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(paused));
        given(advertiserAccountService.canAcceptNewCampaign(ADVERTISER_ID, 50_000L)).willReturn(false);

        assertThatThrownBy(() -> service.resume(campaignId, ORG_ID, REQUESTER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_CREDIT_EXCEEDED);
        assertThat(paused.getStatus()).isEqualTo(AdCampaignStatus.PAUSED);
    }

    @Test
    @DisplayName("resume: PAUSED 以外は AD_CAMPAIGN_INVALID_STATE")
    void resume_PAUSED以外は拒否() {
        AdMessagingCampaign delivering = buildCampaign(AdCampaignStatus.DELIVERING);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(delivering));

        assertThatThrownBy(() -> service.resume(campaignId, ORG_ID, REQUESTER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_INVALID_STATE);
    }

    // ─────────────────────────────────────────────
    // テナント越境 / approve・block の委譲
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("submit: テナント越境は AD_CAMPAIGN_NOT_FOUND (IDOR 対策)")
    void テナント越境は404扱い() {
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(campaignId, ORG_ID, REQUESTER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND);
    }

    @Test
    @DisplayName("approve: AdCampaignModerationService にそのまま委譲")
    void approve_委譲のみ() {
        service.approve(campaignId, MODERATOR_USER_ID);
        verify(moderationService, times(1)).approve(campaignId, MODERATOR_USER_ID);
    }

    @Test
    @DisplayName("block: AdCampaignModerationService にそのまま委譲")
    void block_委譲のみ() {
        BlockCampaignRequest req = new BlockCampaignRequest("理由");
        service.block(campaignId, MODERATOR_USER_ID, req);
        verify(moderationService, times(1)).block(eq(campaignId), eq(MODERATOR_USER_ID), eq(req));
    }

    // ─────────────────────────────────────────────
    // F09.19.7 §10.5 / AC-7.5: 監査ログ発火
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("AC-7.5: submit(AUTO_PASS) は CAMPAIGN_SUBMITTED を発火し AUTO_BLOCKED は発火しない")
    void submit_監査ログ_SUBMITTEDのみ() {
        AdMessagingCampaign draft = buildCampaign(AdCampaignStatus.DRAFT);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(draft));
        given(channelRepository.findByCampaignId(campaignId)).willReturn(List.of(buildChannel()));
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of(buildSegment()));
        willAnswer(inv -> {
            draft.setModerationStatus(AdModerationStatus.AUTO_PASSED);
            return null;
        }).given(moderationService).autoFlagOnSubmit(campaignId);

        service.submit(campaignId, ORG_ID, REQUESTER_USER_ID);

        String expectedMeta = "{\"campaign_id\":\"" + campaignId + "\"}";
        verify(auditLogService, times(1)).record(
                eq("CAMPAIGN_SUBMITTED"), eq(REQUESTER_USER_ID), any(), any(), eq(ORG_ID),
                any(), any(), any(), eq(expectedMeta));
        verify(auditLogService, never()).record(
                eq("CAMPAIGN_AUTO_BLOCKED"), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AC-7.5: submit(AUTO_BLOCK) は SUBMITTED と AUTO_BLOCKED の両方を発火")
    void submit_監査ログ_AUTO_BLOCKED併発() {
        AdMessagingCampaign draft = buildCampaign(AdCampaignStatus.DRAFT);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(draft));
        given(channelRepository.findByCampaignId(campaignId)).willReturn(List.of(buildChannel()));
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of(buildSegment()));
        willAnswer(inv -> {
            draft.setStatus(AdCampaignStatus.BLOCKED);
            draft.setModerationStatus(AdModerationStatus.BLOCKED);
            return null;
        }).given(moderationService).autoFlagOnSubmit(campaignId);

        service.submit(campaignId, ORG_ID, REQUESTER_USER_ID);

        verify(auditLogService, times(1)).record(
                eq("CAMPAIGN_SUBMITTED"), any(), any(), any(), any(), any(), any(), any(), any());
        verify(auditLogService, times(1)).record(
                eq("CAMPAIGN_AUTO_BLOCKED"), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AC-7.5: cancel/launch/pause/resume が各対応イベントを発火")
    void 各遷移_監査ログ発火() {
        // cancel
        AdMessagingCampaign draft = buildCampaign(AdCampaignStatus.DRAFT);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(draft));
        service.cancel(campaignId, ORG_ID, REQUESTER_USER_ID);
        verify(auditLogService, times(1)).record(
                eq("CAMPAIGN_CANCELLED"), eq(REQUESTER_USER_ID), any(), any(), eq(ORG_ID),
                any(), any(), any(), any());

        // launch (即配信)
        AdMessagingCampaign approved = buildCampaign(
                AdCampaignStatus.APPROVED, AdModerationStatus.APPROVED, LocalDateTime.now().minusHours(1));
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(approved));
        given(advertiserAccountService.canAcceptNewCampaign(ADVERTISER_ID, 50_000L)).willReturn(true);
        service.launch(campaignId, ORG_ID, REQUESTER_USER_ID);
        verify(auditLogService, times(1)).record(
                eq("CAMPAIGN_LAUNCHED"), any(), any(), any(), any(), any(), any(), any(), any());

        // pause
        AdMessagingCampaign delivering = buildCampaign(AdCampaignStatus.DELIVERING);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(delivering));
        service.pause(campaignId, ORG_ID, REQUESTER_USER_ID);
        verify(auditLogService, times(1)).record(
                eq("CAMPAIGN_PAUSED"), any(), any(), any(), any(), any(), any(), any(), any());

        // resume
        AdMessagingCampaign paused = buildCampaign(AdCampaignStatus.PAUSED);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(campaignId, ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(paused));
        given(advertiserAccountService.canAcceptNewCampaign(ADVERTISER_ID, 50_000L)).willReturn(true);
        service.resume(campaignId, ORG_ID, REQUESTER_USER_ID);
        verify(auditLogService, times(1)).record(
                eq("CAMPAIGN_RESUMED"), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
