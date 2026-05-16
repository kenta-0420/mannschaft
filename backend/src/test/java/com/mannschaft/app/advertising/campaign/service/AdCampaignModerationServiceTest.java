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
}
