package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.dto.BlockCampaignRequest;
import com.mannschaft.app.advertising.campaign.dto.ReviewQueueItemResponse;
import com.mannschaft.app.advertising.campaign.dto.UnblockCampaignRequest;
import com.mannschaft.app.advertising.campaign.entity.AdCampaignModerationLog;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.enums.AdModerationAction;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.enums.AdNgWordSeverity;
import com.mannschaft.app.advertising.campaign.service.moderation.DetectedNgWord;
import com.mannschaft.app.advertising.campaign.service.moderation.ModerationCheckResult;
import com.mannschaft.app.advertising.campaign.service.moderation.SuggestedModerationAction;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.repository.AdCampaignModerationLogRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignChannelRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F09.17 Phase 11-a {@link AdCampaignModerationService} 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdCampaignModerationService 単体テスト")
class AdCampaignModerationServiceTest {

    @Mock private AdMessagingCampaignRepository campaignRepository;
    @Mock private AdCampaignModerationLogRepository moderationLogRepository;
    @Mock private AdMessagingCampaignChannelRepository campaignChannelRepository;
    @Mock private AdContentModerator contentModerator;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private AdCampaignModerationService service;

    private static final Long MODERATOR_USER_ID = 999L;

    private AdMessagingCampaign buildCampaign(AdCampaignStatus status, AdModerationStatus moderationStatus) {
        AdMessagingCampaign campaign = AdMessagingCampaign.builder()
                .advertiserAccountId(100L)
                .organizationId(1L)
                .name("テストキャンペーン")
                .status(status)
                .totalBudgetYen(100_000L)
                .consumedBudgetYen(0L)
                .startsAt(LocalDateTime.now())
                .endsAt(LocalDateTime.now().plusDays(7))
                .scheduledTimezone("Asia/Tokyo")
                .moderationStatus(moderationStatus)
                .createdByUserId(10L)
                .createdAt(LocalDateTime.now().minusHours(1))
                .updatedAt(LocalDateTime.now().minusHours(1))
                .build();
        campaign.setId(UUID.randomUUID());
        return campaign;
    }

    // ─────────────────────────────────────────────
    // 審査キュー取得
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("審査キュー: PENDING/AUTO_FLAGGED のみが返却される")
    void 審査キュー取得_PENDING_AUTO_FLAGGED_のみ() {
        // Given
        AdMessagingCampaign pending = buildCampaign(AdCampaignStatus.DRAFT, AdModerationStatus.PENDING);
        AdMessagingCampaign autoFlagged = buildCampaign(AdCampaignStatus.REVIEW, AdModerationStatus.AUTO_FLAGGED);
        Page<AdMessagingCampaign> page = new PageImpl<>(List.of(pending, autoFlagged));
        given(campaignRepository.findByModerationStatusInAndDeletedAtIsNull(any(), any(Pageable.class)))
                .willReturn(page);

        // When
        Page<ReviewQueueItemResponse> result = service.getReviewQueue(0, 20);

        // Then
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent())
                .extracting(ReviewQueueItemResponse::getModerationStatus)
                .containsExactly(AdModerationStatus.PENDING, AdModerationStatus.AUTO_FLAGGED);
        assertThat(result.getContent())
                .extracting(ReviewQueueItemResponse::getName)
                .containsExactly("テストキャンペーン", "テストキャンペーン");
    }

    // ─────────────────────────────────────────────
    // approve()
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("approve 成功: DRAFT+PENDING → APPROVED + moderation_logs 行作成")
    void approve_成功() {
        // Given
        AdMessagingCampaign campaign = buildCampaign(AdCampaignStatus.DRAFT, AdModerationStatus.PENDING);
        UUID campaignId = campaign.getId();
        given(campaignRepository.findById(campaignId)).willReturn(Optional.of(campaign));

        // When
        service.approve(campaignId, MODERATOR_USER_ID);

        // Then: Entity 更新
        assertThat(campaign.getStatus()).isEqualTo(AdCampaignStatus.APPROVED);
        assertThat(campaign.getModerationStatus()).isEqualTo(AdModerationStatus.APPROVED);
        verify(campaignRepository).save(campaign);

        // Then: ログ行作成
        ArgumentCaptor<AdCampaignModerationLog> captor = ArgumentCaptor.forClass(AdCampaignModerationLog.class);
        verify(moderationLogRepository).save(captor.capture());
        AdCampaignModerationLog log = captor.getValue();
        assertThat(log.getCampaignId()).isEqualTo(campaignId);
        assertThat(log.getModeratorUserId()).isEqualTo(MODERATOR_USER_ID);
        assertThat(log.getAction()).isEqualTo(AdModerationAction.APPROVED);
        assertThat(log.getReason()).isNull();
    }

    @Test
    @DisplayName("approve 成功: REVIEW+AUTO_FLAGGED もOK")
    void approve_REVIEW_AUTO_FLAGGED_成功() {
        AdMessagingCampaign campaign = buildCampaign(AdCampaignStatus.REVIEW, AdModerationStatus.AUTO_FLAGGED);
        given(campaignRepository.findById(campaign.getId())).willReturn(Optional.of(campaign));

        service.approve(campaign.getId(), MODERATOR_USER_ID);

        assertThat(campaign.getStatus()).isEqualTo(AdCampaignStatus.APPROVED);
        assertThat(campaign.getModerationStatus()).isEqualTo(AdModerationStatus.APPROVED);
        verify(moderationLogRepository).save(any(AdCampaignModerationLog.class));
    }

    @Test
    @DisplayName("approve 拒否: 既に APPROVED → NOT_REVIEWABLE")
    void approve_拒否_既にAPPROVED() {
        AdMessagingCampaign campaign = buildCampaign(AdCampaignStatus.APPROVED, AdModerationStatus.APPROVED);
        given(campaignRepository.findById(campaign.getId())).willReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.approve(campaign.getId(), MODERATOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.NOT_REVIEWABLE);

        verify(campaignRepository, never()).save(any());
        verify(moderationLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("approve 拒否: BLOCKED → NOT_REVIEWABLE")
    void approve_拒否_BLOCKED() {
        AdMessagingCampaign campaign = buildCampaign(AdCampaignStatus.BLOCKED, AdModerationStatus.BLOCKED);
        given(campaignRepository.findById(campaign.getId())).willReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.approve(campaign.getId(), MODERATOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.NOT_REVIEWABLE);
    }

    @Test
    @DisplayName("approve 拒否: DELIVERING → NOT_REVIEWABLE")
    void approve_拒否_DELIVERING() {
        AdMessagingCampaign campaign = buildCampaign(AdCampaignStatus.DELIVERING, AdModerationStatus.APPROVED);
        given(campaignRepository.findById(campaign.getId())).willReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.approve(campaign.getId(), MODERATOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.NOT_REVIEWABLE);
    }

    @Test
    @DisplayName("approve 拒否: 存在しない → AD_CAMPAIGN_NOT_FOUND")
    void approve_存在しない() {
        UUID id = UUID.randomUUID();
        given(campaignRepository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(id, MODERATOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND);
    }

    // ─────────────────────────────────────────────
    // block()
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("block 成功: DELIVERING でも BLOCKED 遷移可 + moderation_logs 行作成")
    void block_成功_DELIVERING() {
        AdMessagingCampaign campaign = buildCampaign(AdCampaignStatus.DELIVERING, AdModerationStatus.APPROVED);
        UUID campaignId = campaign.getId();
        given(campaignRepository.findById(campaignId)).willReturn(Optional.of(campaign));

        service.block(campaignId, MODERATOR_USER_ID, new BlockCampaignRequest("規約違反のため"));

        assertThat(campaign.getStatus()).isEqualTo(AdCampaignStatus.BLOCKED);
        assertThat(campaign.getModerationStatus()).isEqualTo(AdModerationStatus.BLOCKED);
        assertThat(campaign.getBlockedReason()).isEqualTo("規約違反のため");
        verify(campaignRepository).save(campaign);

        ArgumentCaptor<AdCampaignModerationLog> captor = ArgumentCaptor.forClass(AdCampaignModerationLog.class);
        verify(moderationLogRepository).save(captor.capture());
        AdCampaignModerationLog log = captor.getValue();
        assertThat(log.getCampaignId()).isEqualTo(campaignId);
        assertThat(log.getModeratorUserId()).isEqualTo(MODERATOR_USER_ID);
        assertThat(log.getAction()).isEqualTo(AdModerationAction.BLOCKED);
        assertThat(log.getReason()).isEqualTo("規約違反のため");
    }

    @Test
    @DisplayName("block 成功: PENDING からも遷移可")
    void block_成功_PENDING() {
        AdMessagingCampaign campaign = buildCampaign(AdCampaignStatus.DRAFT, AdModerationStatus.PENDING);
        given(campaignRepository.findById(campaign.getId())).willReturn(Optional.of(campaign));

        service.block(campaign.getId(), MODERATOR_USER_ID, new BlockCampaignRequest("自動検知"));

        assertThat(campaign.getStatus()).isEqualTo(AdCampaignStatus.BLOCKED);
        assertThat(campaign.getModerationStatus()).isEqualTo(AdModerationStatus.BLOCKED);
    }

    @Test
    @DisplayName("block 重複: 既に BLOCKED → ALREADY_BLOCKED (409)")
    void block_拒否_既にBLOCKED() {
        AdMessagingCampaign campaign = buildCampaign(AdCampaignStatus.BLOCKED, AdModerationStatus.BLOCKED);
        UUID campaignId = campaign.getId();
        given(campaignRepository.findById(campaignId)).willReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.block(campaignId, MODERATOR_USER_ID,
                new BlockCampaignRequest("再ブロック")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.ALREADY_BLOCKED);

        verify(campaignRepository, never()).save(any());
        verify(moderationLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("block 拒否: 存在しない → AD_CAMPAIGN_NOT_FOUND")
    void block_存在しない() {
        UUID id = UUID.randomUUID();
        given(campaignRepository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.block(id, MODERATOR_USER_ID, new BlockCampaignRequest("理由")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND);
    }

    // ─────────────────────────────────────────────
    // unblock() — F09.17 残課題 3
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("unblock 成功: BLOCKED → REVIEW + blocked_reason クリア + UNBLOCKED ログ + 監査ログ発火")
    void unblock_成功() {
        AdMessagingCampaign campaign = buildCampaign(AdCampaignStatus.BLOCKED, AdModerationStatus.BLOCKED);
        campaign.setBlockedReason("旧ブロック理由");
        UUID campaignId = campaign.getId();
        given(campaignRepository.findById(campaignId)).willReturn(Optional.of(campaign));

        service.unblock(campaignId, MODERATOR_USER_ID, new UnblockCampaignRequest("誤判定のため取消"));

        // Entity 更新確認
        assertThat(campaign.getStatus()).isEqualTo(AdCampaignStatus.REVIEW);
        assertThat(campaign.getModerationStatus()).isEqualTo(AdModerationStatus.PENDING);
        assertThat(campaign.getBlockedReason()).isNull();
        verify(campaignRepository).save(campaign);

        // moderation_logs 行作成
        ArgumentCaptor<AdCampaignModerationLog> captor = ArgumentCaptor.forClass(AdCampaignModerationLog.class);
        verify(moderationLogRepository).save(captor.capture());
        AdCampaignModerationLog log = captor.getValue();
        assertThat(log.getCampaignId()).isEqualTo(campaignId);
        assertThat(log.getModeratorUserId()).isEqualTo(MODERATOR_USER_ID);
        assertThat(log.getAction()).isEqualTo(AdModerationAction.UNBLOCKED);
        assertThat(log.getReason()).isEqualTo("誤判定のため取消");

        // 監査ログ発火確認
        verify(auditLogService).record(
                eq("CAMPAIGN_UNBLOCKED"),
                eq(MODERATOR_USER_ID),
                any(),
                any(),
                eq(1L),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("unblock 拒否: status=DRAFT → NOT_UNBLOCKABLE")
    void unblock_拒否_DRAFT() {
        AdMessagingCampaign campaign = buildCampaign(AdCampaignStatus.DRAFT, AdModerationStatus.PENDING);
        given(campaignRepository.findById(campaign.getId())).willReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.unblock(campaign.getId(), MODERATOR_USER_ID,
                new UnblockCampaignRequest("試行")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_NOT_UNBLOCKABLE);

        verify(campaignRepository, never()).save(any());
        verify(moderationLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("unblock 拒否: status=APPROVED → NOT_UNBLOCKABLE")
    void unblock_拒否_APPROVED() {
        AdMessagingCampaign campaign = buildCampaign(AdCampaignStatus.APPROVED, AdModerationStatus.APPROVED);
        given(campaignRepository.findById(campaign.getId())).willReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.unblock(campaign.getId(), MODERATOR_USER_ID,
                new UnblockCampaignRequest("試行")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_NOT_UNBLOCKABLE);
    }

    @Test
    @DisplayName("unblock 拒否: status=DELIVERING → NOT_UNBLOCKABLE")
    void unblock_拒否_DELIVERING() {
        AdMessagingCampaign campaign = buildCampaign(AdCampaignStatus.DELIVERING, AdModerationStatus.APPROVED);
        given(campaignRepository.findById(campaign.getId())).willReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.unblock(campaign.getId(), MODERATOR_USER_ID,
                new UnblockCampaignRequest("試行")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_NOT_UNBLOCKABLE);
    }

    @Test
    @DisplayName("unblock 拒否: 存在しない → AD_CAMPAIGN_NOT_FOUND")
    void unblock_存在しない() {
        UUID id = UUID.randomUUID();
        given(campaignRepository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.unblock(id, MODERATOR_USER_ID,
                new UnblockCampaignRequest("試行")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND);
    }

    @Test
    @DisplayName("unblock: 監査ログ metadata に campaign_id と reason が含まれる")
    void unblock_監査ログメタデータ確認() {
        AdMessagingCampaign campaign = buildCampaign(AdCampaignStatus.BLOCKED, AdModerationStatus.BLOCKED);
        UUID campaignId = campaign.getId();
        given(campaignRepository.findById(campaignId)).willReturn(Optional.of(campaign));

        service.unblock(campaignId, MODERATOR_USER_ID, new UnblockCampaignRequest("ダブルクォート\"含む理由"));

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(
                eq("CAMPAIGN_UNBLOCKED"),
                eq(MODERATOR_USER_ID),
                any(), any(), any(), any(), any(), any(),
                metadataCaptor.capture()
        );
        String metadata = metadataCaptor.getValue();
        assertThat(metadata).contains(campaignId.toString());
        // ダブルクォートはエスケープされる
        assertThat(metadata).contains("\\\"");
    }

    // ─────────────────────────────────────────────
    // autoFlagOnSubmit() — F09.17 Phase 11-b δ
    // ─────────────────────────────────────────────

    private AdMessagingCampaignChannel buildChannel(UUID campaignId, AdChannelType channelType, String body) {
        AdMessagingCampaignChannel channel = AdMessagingCampaignChannel.builder()
                .campaignId(campaignId)
                .channelType(channelType)
                .locale("ja")
                .bodyMarkdown(body)
                .build();
        channel.setId(UUID.randomUUID());
        return channel;
    }

    @Test
    @DisplayName("autoFlagOnSubmit: 多チャネル中 1 チャネル BLOCK → キャンペーン全体 BLOCKED + 理由付き log")
    void autoFlagOnSubmit_BLOCK混在で全体BLOCKED() {
        // Given: 3 チャネル (PASS, FLAG, BLOCK) のキャンペーン
        AdMessagingCampaign campaign = buildCampaign(AdCampaignStatus.REVIEW, AdModerationStatus.PENDING);
        UUID campaignId = campaign.getId();
        AdMessagingCampaignChannel cleanCh = buildChannel(campaignId, AdChannelType.EMAIL, "健全な本文");
        AdMessagingCampaignChannel warnCh = buildChannel(campaignId, AdChannelType.PUSH, "最高のサービス");
        AdMessagingCampaignChannel blockCh = buildChannel(campaignId, AdChannelType.ANNOUNCEMENT, "頭痛が治る薬");

        given(campaignRepository.findById(campaignId)).willReturn(Optional.of(campaign));
        given(campaignChannelRepository.findByCampaignId(campaignId))
                .willReturn(List.of(cleanCh, warnCh, blockCh));
        given(contentModerator.check("健全な本文"))
                .willReturn(new ModerationCheckResult(List.of(), SuggestedModerationAction.AUTO_PASS));
        given(contentModerator.check("最高のサービス"))
                .willReturn(new ModerationCheckResult(
                        List.of(new DetectedNgWord("最高", "SUPERLATIVE", AdNgWordSeverity.WARN)),
                        SuggestedModerationAction.AUTO_FLAG));
        given(contentModerator.check("頭痛が治る薬"))
                .willReturn(new ModerationCheckResult(
                        List.of(new DetectedNgWord("治る", "PHARMA", AdNgWordSeverity.BLOCK)),
                        SuggestedModerationAction.AUTO_BLOCK));

        // When
        service.autoFlagOnSubmit(campaignId);

        // Then: キャンペーン全体が BLOCKED
        assertThat(campaign.getModerationStatus()).isEqualTo(AdModerationStatus.BLOCKED);
        assertThat(campaign.getStatus()).isEqualTo(AdCampaignStatus.BLOCKED);
        assertThat(campaign.getBlockedReason())
                .contains("治る")
                .contains("自動 NG 検知");
        verify(campaignRepository).save(campaign);

        // Then: moderation_logs 行 1 件 (BLOCKED + ng_words_detected JSON + reason)
        ArgumentCaptor<AdCampaignModerationLog> captor = ArgumentCaptor.forClass(AdCampaignModerationLog.class);
        verify(moderationLogRepository).save(captor.capture());
        AdCampaignModerationLog log = captor.getValue();
        assertThat(log.getCampaignId()).isEqualTo(campaignId);
        assertThat(log.getModeratorUserId()).isNull(); // 自動検知のため NULL
        assertThat(log.getAction()).isEqualTo(AdModerationAction.BLOCKED);
        assertThat(log.getReason()).contains("治る");
        assertThat(log.getNgWordsDetected())
                .contains("\"word\":\"最高\"")
                .contains("\"word\":\"治る\"")
                .contains("\"severity\":\"WARN\"")
                .contains("\"severity\":\"BLOCK\"");
    }

    @Test
    @DisplayName("autoFlagOnSubmit: 全チャネル PASS → AUTO_PASSED + moderation_logs 行 1 件 (ng_words_detected なし)")
    void autoFlagOnSubmit_全PASSで_AUTO_PASSED反映() {
        AdMessagingCampaign campaign = buildCampaign(AdCampaignStatus.REVIEW, AdModerationStatus.PENDING);
        UUID campaignId = campaign.getId();
        AdMessagingCampaignChannel ch1 = buildChannel(campaignId, AdChannelType.EMAIL, "本日のお知らせ");
        AdMessagingCampaignChannel ch2 = buildChannel(campaignId, AdChannelType.PUSH, "アプリ更新があります");

        given(campaignRepository.findById(campaignId)).willReturn(Optional.of(campaign));
        given(campaignChannelRepository.findByCampaignId(campaignId)).willReturn(List.of(ch1, ch2));
        given(contentModerator.check("本日のお知らせ"))
                .willReturn(new ModerationCheckResult(List.of(), SuggestedModerationAction.AUTO_PASS));
        given(contentModerator.check("アプリ更新があります"))
                .willReturn(new ModerationCheckResult(List.of(), SuggestedModerationAction.AUTO_PASS));

        // When
        service.autoFlagOnSubmit(campaignId);

        // Then: キャンペーンが AUTO_PASSED
        assertThat(campaign.getModerationStatus()).isEqualTo(AdModerationStatus.AUTO_PASSED);
        // status は変更されない (DRAFT/REVIEW のまま)
        assertThat(campaign.getStatus()).isEqualTo(AdCampaignStatus.REVIEW);
        assertThat(campaign.getBlockedReason()).isNull();
        verify(campaignRepository).save(campaign);

        // Then: moderation_logs 行 1 件 (AUTO_PASSED + ng_words_detected なし)
        ArgumentCaptor<AdCampaignModerationLog> captor = ArgumentCaptor.forClass(AdCampaignModerationLog.class);
        verify(moderationLogRepository).save(captor.capture());
        AdCampaignModerationLog log = captor.getValue();
        assertThat(log.getAction()).isEqualTo(AdModerationAction.AUTO_PASSED);
        assertThat(log.getModeratorUserId()).isNull();
        assertThat(log.getNgWordsDetected()).isNull();
        assertThat(log.getReason()).isNull();
    }

    @Test
    @DisplayName("autoFlagOnSubmit: WARN のみ検出 → AUTO_FLAGGED + ng_words_detected JSON")
    void autoFlagOnSubmit_WARNのみで_AUTO_FLAGGED() {
        AdMessagingCampaign campaign = buildCampaign(AdCampaignStatus.REVIEW, AdModerationStatus.PENDING);
        UUID campaignId = campaign.getId();
        AdMessagingCampaignChannel ch = buildChannel(campaignId, AdChannelType.EMAIL, "業界No.1のサービス");

        given(campaignRepository.findById(campaignId)).willReturn(Optional.of(campaign));
        given(campaignChannelRepository.findByCampaignId(campaignId)).willReturn(List.of(ch));
        given(contentModerator.check("業界No.1のサービス"))
                .willReturn(new ModerationCheckResult(
                        List.of(new DetectedNgWord("業界No.1", "SUPERLATIVE", AdNgWordSeverity.WARN)),
                        SuggestedModerationAction.AUTO_FLAG));

        service.autoFlagOnSubmit(campaignId);

        assertThat(campaign.getModerationStatus()).isEqualTo(AdModerationStatus.AUTO_FLAGGED);
        assertThat(campaign.getStatus()).isEqualTo(AdCampaignStatus.REVIEW);
        assertThat(campaign.getBlockedReason()).isNull();

        ArgumentCaptor<AdCampaignModerationLog> captor = ArgumentCaptor.forClass(AdCampaignModerationLog.class);
        verify(moderationLogRepository).save(captor.capture());
        AdCampaignModerationLog log = captor.getValue();
        assertThat(log.getAction()).isEqualTo(AdModerationAction.AUTO_FLAGGED);
        assertThat(log.getNgWordsDetected()).contains("\"word\":\"業界No.1\"");
    }

    @Test
    @DisplayName("autoFlagOnSubmit: 存在しないキャンペーン → AD_CAMPAIGN_NOT_FOUND")
    void autoFlagOnSubmit_存在しない() {
        UUID id = UUID.randomUUID();
        given(campaignRepository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.autoFlagOnSubmit(id))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND);
    }
}
