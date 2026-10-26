package com.mannschaft.app.advertising.campaign.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.dto.AudienceConfigRequest;
import com.mannschaft.app.advertising.campaign.dto.AudienceSegmentRequest;
import com.mannschaft.app.advertising.campaign.dto.AudienceSegmentResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignChannelRequest;
import com.mannschaft.app.advertising.campaign.dto.CampaignChannelResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignDetailResponse;
import com.mannschaft.app.advertising.campaign.dto.CreateCampaignRequest;
import com.mannschaft.app.advertising.campaign.dto.UpdateCampaignRequest;
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
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F09.17 Phase 11-a {@link AdMessagingCampaignService} 単体テスト。
 *
 * <p>DRAFT 制約・テナント越境・UNIQUE 違反・audience replace のコア挙動を網羅する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdMessagingCampaignService 単体テスト")
class AdMessagingCampaignServiceTest {

    @Mock
    private AdMessagingCampaignRepository campaignRepository;
    @Mock
    private AdMessagingCampaignChannelRepository channelRepository;
    @Mock
    private AdAudienceSegmentRepository segmentRepository;
    @Mock
    private AdAudienceResolver audienceResolver;

    // Mapper は ObjectMapper を含むため Mock より実体を注入する方が挙動が確実。
    private final AdMessagingCampaignMapper mapper = new AdMessagingCampaignMapper(new ObjectMapper());

    private AdMessagingCampaignService service;

    @BeforeEach
    void setUp() {
        service = new AdMessagingCampaignService(
                campaignRepository, channelRepository, segmentRepository, mapper, audienceResolver);
    }

    // ─────────────────────────────────────────────
    // create
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("createCampaign: DRAFT で作成され status=DRAFT moderation=PENDING がセットされる")
    void createCampaign_DRAFTで作成される() {
        // Given
        Long orgId = 100L;
        Long accountId = 200L;
        Long userId = 300L;
        CreateCampaignRequest request = new CreateCampaignRequest(
                "夏キャンペーン",
                500_000L,
                LocalDateTime.of(2026, 6, 1, 0, 0),
                LocalDateTime.of(2026, 6, 30, 23, 59),
                "Asia/Tokyo",
                5);

        given(campaignRepository.save(any(AdMessagingCampaign.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // When
        CampaignDetailResponse response = service.createCampaign(ScopeType.ORGANIZATION, orgId, accountId, userId, request);

        // Then
        assertThat(response.name()).isEqualTo("夏キャンペーン");
        assertThat(response.status()).isEqualTo(AdCampaignStatus.DRAFT);
        assertThat(response.moderationStatus()).isEqualTo(AdModerationStatus.PENDING);
        assertThat(response.advertiserAccountId()).isEqualTo(accountId);
        assertThat(response.consumedBudgetYen()).isEqualTo(0L);
        assertThat(response.channels()).isEmpty();
        assertThat(response.audienceSegments()).isEmpty();
        verify(campaignRepository, times(1)).save(any(AdMessagingCampaign.class));
    }

    @Test
    @DisplayName("createCampaign: startsAt >= endsAt の場合 AD_AUDIENCE_INVALID で 400")
    void createCampaign_スケジュール不正で400() {
        // Given
        CreateCampaignRequest request = new CreateCampaignRequest(
                "不正期間",
                10_000L,
                LocalDateTime.of(2026, 6, 30, 0, 0),
                LocalDateTime.of(2026, 6, 1, 0, 0),
                "Asia/Tokyo",
                null);

        // When / Then
        assertThatThrownBy(() -> service.createCampaign(ScopeType.ORGANIZATION, 1L, 2L, 3L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    // ─────────────────────────────────────────────
    // update
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("updateCampaign: DRAFT 以外の状態で AD_CAMPAIGN_NOT_EDITABLE")
    void updateCampaign_DRAFT以外で409() {
        // Given
        UUID campaignId = UUID.randomUUID();
        Long orgId = 100L;
        AdMessagingCampaign existing = buildCampaign(campaignId, orgId, AdCampaignStatus.REVIEW);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                        campaignId, ScopeType.ORGANIZATION, orgId))
                .willReturn(Optional.of(existing));

        UpdateCampaignRequest request = new UpdateCampaignRequest(
                "更新できないキャンペーン",
                10_000L,
                LocalDateTime.of(2026, 6, 1, 0, 0),
                LocalDateTime.of(2026, 6, 30, 0, 0),
                "Asia/Tokyo",
                null);

        // When / Then
        assertThatThrownBy(() -> service.updateCampaign(campaignId, ScopeType.ORGANIZATION, orgId, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_NOT_EDITABLE);
        verify(campaignRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────
    // getCampaign (テナント越境)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("getCampaign: 他テナント所有のキャンペーン参照は AD_CAMPAIGN_NOT_FOUND (IDOR 対策)")
    void getCampaign_テナント越境で404() {
        // Given
        UUID campaignId = UUID.randomUUID();
        Long orgId = 999L; // 攻撃者のテナント
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                        campaignId, ScopeType.ORGANIZATION, orgId))
                .willReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.getCampaign(campaignId, ScopeType.ORGANIZATION, orgId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND);
    }

    // ─────────────────────────────────────────────
    // addChannel
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("addChannel: UNIQUE (campaign_id, channel_type, locale) 重複で AD_CHANNEL_DUPLICATE")
    void addChannel_重複で409() {
        // Given
        UUID campaignId = UUID.randomUUID();
        Long orgId = 100L;
        AdMessagingCampaign existing = buildCampaign(campaignId, orgId, AdCampaignStatus.DRAFT);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                        campaignId, ScopeType.ORGANIZATION, orgId))
                .willReturn(Optional.of(existing));

        AdMessagingCampaignChannel duplicate = AdMessagingCampaignChannel.builder()
                .campaignId(campaignId)
                .channelType(AdChannelType.EMAIL)
                .locale("ja")
                .bodyMarkdown("既存")
                .build();
        given(channelRepository.findByCampaignIdAndChannelTypeAndLocale(
                campaignId, AdChannelType.EMAIL, "ja"))
                .willReturn(Optional.of(duplicate));

        CampaignChannelRequest request = new CampaignChannelRequest(
                AdChannelType.EMAIL,
                "ja",
                "件名",
                "本文 markdown",
                null,
                null,
                null,
                null);

        // When / Then
        assertThatThrownBy(() -> service.addChannel(campaignId, ScopeType.ORGANIZATION, orgId, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_CHANNEL_DUPLICATE);
        verify(channelRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────
    // setAudience (全件 replace)
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("setAudience: 既存セグメント全削除 → リクエスト配列を INSERT (全件 replace)")
    void setAudience_全件replace() {
        // Given
        UUID campaignId = UUID.randomUUID();
        Long orgId = 100L;
        AdMessagingCampaign existing = buildCampaign(campaignId, orgId, AdCampaignStatus.DRAFT);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                        campaignId, ScopeType.ORGANIZATION, orgId))
                .willReturn(Optional.of(existing));

        AudienceConfigRequest request = new AudienceConfigRequest(List.of(
                new AudienceSegmentRequest(
                        AdSegmentType.REGION_PREFECTURE,
                        Map.of("prefectures", List.of("東京都", "神奈川県")),
                        AdSegmentInclusionMode.INCLUDE),
                new AudienceSegmentRequest(
                        AdSegmentType.AGE_RANGE,
                        Map.of("min", 20, "max", 39),
                        AdSegmentInclusionMode.INCLUDE)
        ));

        given(segmentRepository.saveAll(any()))
                .willAnswer(inv -> {
                    List<AdAudienceSegment> arg = inv.getArgument(0);
                    return arg;
                });

        // When
        List<AudienceSegmentResponse> responses = service.setAudience(campaignId, ScopeType.ORGANIZATION, orgId, request);

        // Then
        // 既存セグメントを必ず先に削除している
        verify(segmentRepository, times(1)).deleteByCampaignId(campaignId);
        verify(segmentRepository, times(1)).saveAll(any());
        assertThat(responses).hasSize(2);
        assertThat(responses)
                .extracting(AudienceSegmentResponse::segmentType)
                .containsExactlyInAnyOrder(
                        AdSegmentType.REGION_PREFECTURE, AdSegmentType.AGE_RANGE);
    }

    // ─────────────────────────────────────────────
    // softDeleteCampaign
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("softDeleteCampaign: DRAFT のみ許可 / それ以外は AD_CAMPAIGN_NOT_EDITABLE")
    void softDeleteCampaign_DRAFTのみ() {
        // Given (DRAFT 成功ケース)
        UUID draftId = UUID.randomUUID();
        AdMessagingCampaign draft = buildCampaign(draftId, 100L, AdCampaignStatus.DRAFT);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                        draftId, ScopeType.ORGANIZATION, 100L))
                .willReturn(Optional.of(draft));
        given(campaignRepository.save(any(AdMessagingCampaign.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // When
        service.softDeleteCampaign(draftId, ScopeType.ORGANIZATION, 100L);

        // Then: deletedAt がセットされ save される
        verify(campaignRepository, times(1)).save(draft);
        assertThat(draft.getDeletedAt()).isNotNull();

        // Given (DELIVERING の論理削除は拒否)
        UUID liveId = UUID.randomUUID();
        AdMessagingCampaign live = buildCampaign(liveId, 100L, AdCampaignStatus.DELIVERING);
        given(campaignRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                        liveId, ScopeType.ORGANIZATION, 100L))
                .willReturn(Optional.of(live));

        // When / Then
        assertThatThrownBy(() -> service.softDeleteCampaign(liveId, ScopeType.ORGANIZATION, 100L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_CAMPAIGN_NOT_EDITABLE);
        // DRAFT 1 回のみ
        verify(campaignRepository, times(1)).save(any(AdMessagingCampaign.class));
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private AdMessagingCampaign buildCampaign(UUID id, Long orgId, AdCampaignStatus status) {
        AdMessagingCampaign campaign = AdMessagingCampaign.builder()
                .advertiserAccountId(200L)
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(orgId)
                .name("既存キャンペーン")
                .status(status)
                .moderationStatus(AdModerationStatus.PENDING)
                .totalBudgetYen(100_000L)
                .consumedBudgetYen(0L)
                .startsAt(LocalDateTime.of(2026, 6, 1, 0, 0))
                .endsAt(LocalDateTime.of(2026, 6, 30, 23, 59))
                .scheduledTimezone("Asia/Tokyo")
                .createdByUserId(300L)
                .build();
        // id は @PrePersist で発番されるが、テストでは UuidV7Entity#setId で明示的に詰める。
        campaign.setId(id);
        return campaign;
    }
}
