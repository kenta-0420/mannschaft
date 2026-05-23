package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdBannerDelivery;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.repository.AdBannerDeliveryRepository;
import com.mannschaft.app.advertising.service.AdImpressionService;
import com.mannschaft.app.membership.domain.ScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F09.17 Phase 10 第二陣-A {@link AdBannerChannelService} 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdBannerChannelService 単体テスト")
class AdBannerChannelServiceTest {

    @Mock
    private AdImpressionService adImpressionService;

    @Mock
    private AdBannerDeliveryRepository adBannerDeliveryRepository;

    @InjectMocks
    private AdBannerChannelService service;

    private AdMessagingCampaign buildCampaign() {
        AdMessagingCampaign campaign = AdMessagingCampaign.builder()
                .advertiserAccountId(100L)
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(1L)
                .name("バナーキャンペーン")
                .status(AdCampaignStatus.DELIVERING)
                .totalBudgetYen(100_000L)
                .consumedBudgetYen(0L)
                .startsAt(LocalDateTime.now().minusDays(1))
                .endsAt(LocalDateTime.now().plusDays(7))
                .scheduledTimezone("Asia/Tokyo")
                .moderationStatus(AdModerationStatus.APPROVED)
                .createdByUserId(10L)
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();
        campaign.setId(UUID.randomUUID());
        return campaign;
    }

    private AdMessagingCampaignChannel buildBannerChannel(UUID campaignId, Long bannerCreativeId) {
        AdMessagingCampaignChannel ch = AdMessagingCampaignChannel.builder()
                .campaignId(campaignId)
                .channelType(AdChannelType.BANNER)
                .locale("ja")
                .bodyMarkdown("バナー広告本文")
                .bannerCreativeId(bannerCreativeId)
                .build();
        ch.setId(UUID.randomUUID());
        return ch;
    }

    @Test
    @DisplayName("deliver: AdImpressionService.recordForMessagingCampaign を呼び、AdBannerDelivery を保存して true を返す")
    void deliver_正常系() {
        // given
        AdMessagingCampaign campaign = buildCampaign();
        long bannerCreativeId = 55L;
        AdMessagingCampaignChannel channel = buildBannerChannel(campaign.getId(), bannerCreativeId);
        long userId = 42L;
        long impressionId = 999L;

        given(adImpressionService.recordForMessagingCampaign(
                eq(bannerCreativeId),
                eq(campaign.getId()),
                eq(userId)))
                .willReturn(impressionId);
        given(adBannerDeliveryRepository.save(any(AdBannerDelivery.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // when
        boolean result = service.deliver(campaign, channel, userId);

        // then
        assertThat(result).isTrue();

        // recordForMessagingCampaign を 1 回だけ呼ぶこと（F09.7/F09.17 型不一致根治確認）
        verify(adImpressionService, times(1)).recordForMessagingCampaign(
                eq(bannerCreativeId),
                eq(campaign.getId()),
                eq(userId));

        ArgumentCaptor<AdBannerDelivery> captor = ArgumentCaptor.forClass(AdBannerDelivery.class);
        verify(adBannerDeliveryRepository, times(1)).save(captor.capture());
        AdBannerDelivery saved = captor.getValue();
        assertThat(saved.getCampaignId()).isEqualTo(campaign.getId());
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getAdImpressionId()).isEqualTo(impressionId);
        assertThat(saved.getServedAt()).isNotNull();
        assertThat(saved.getMonthKey()).matches("\\d{4}-\\d{2}");
    }

    @Test
    @DisplayName("deliver: campaign が null なら IllegalArgumentException")
    void deliver_null_campaign() {
        AdMessagingCampaignChannel channel = buildBannerChannel(UUID.randomUUID(), 1L);

        assertThatThrownBy(() -> service.deliver(null, channel, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必須");
    }

    @Test
    @DisplayName("deliver: channel が null なら IllegalArgumentException")
    void deliver_null_channel() {
        AdMessagingCampaign campaign = buildCampaign();

        assertThatThrownBy(() -> service.deliver(campaign, null, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必須");
    }

    @Test
    @DisplayName("deliver: userId が null なら IllegalArgumentException")
    void deliver_null_userId() {
        AdMessagingCampaign campaign = buildCampaign();
        AdMessagingCampaignChannel channel = buildBannerChannel(campaign.getId(), 1L);

        assertThatThrownBy(() -> service.deliver(campaign, channel, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必須");
    }
}
