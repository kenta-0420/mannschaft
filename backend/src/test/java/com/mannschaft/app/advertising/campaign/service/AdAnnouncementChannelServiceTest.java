package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdAnnouncementDelivery;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.repository.AdAnnouncementDeliveryRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedService;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F09.17 Phase 11-b ε-B {@link AdAnnouncementChannelService} 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdAnnouncementChannelService 単体テスト")
class AdAnnouncementChannelServiceTest {

    @Mock private AnnouncementFeedService announcementFeedService;
    @Mock private AdAnnouncementDeliveryRepository deliveryRepository;
    @InjectMocks private AdAnnouncementChannelService service;

    private AdMessagingCampaign buildCampaign() {
        AdMessagingCampaign campaign = AdMessagingCampaign.builder()
                .advertiserAccountId(100L)
                .organizationId(1L)
                .name("テストキャンペーン")
                .status(AdCampaignStatus.DELIVERING)
                .totalBudgetYen(50_000L)
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

    private AdMessagingCampaignChannel buildChannel(UUID campaignId) {
        AdMessagingCampaignChannel ch = AdMessagingCampaignChannel.builder()
                .campaignId(campaignId)
                .channelType(AdChannelType.ANNOUNCEMENT)
                .locale("ja")
                .subject("広告タイトル")
                .bodyMarkdown("広告本文 markdown")
                .build();
        ch.setId(UUID.randomUUID());
        return ch;
    }

    private AnnouncementFeedEntity buildFeed() {
        AnnouncementFeedEntity feed = AnnouncementFeedEntity.builder()
                .scopeType(AnnouncementScopeType.ADVERTISER_AD)
                .scopeId(100L)
                .sourceType(AnnouncementSourceType.ADVERTISER_CAMPAIGN)
                .sourceId(1L)
                .titleCache("広告タイトル")
                .visibility("MEMBERS_ONLY")
                .isAdvertisement(true)
                .build();
        // BaseEntity.id 設定はリフレクション無しに不能なので id 取得時に null を許容するテストとする。
        return feed;
    }

    @Test
    @DisplayName("deliver: AnnouncementFeedService.createAdvertiserFeed を呼んで ad_announcement_deliveries に保存する")
    void deliver_正常系() {
        AdMessagingCampaign campaign = buildCampaign();
        AdMessagingCampaignChannel channel = buildChannel(campaign.getId());
        AnnouncementFeedEntity feed = buildFeed();
        given(announcementFeedService.createAdvertiserFeed(
                eq(campaign.getAdvertiserAccountId()),
                eq(campaign.getId()),
                eq(42L),
                eq("広告タイトル"),
                eq("広告本文 markdown")))
                .willReturn(feed);

        service.deliver(campaign, channel, 42L);

        verify(announcementFeedService, times(1)).createAdvertiserFeed(
                eq(campaign.getAdvertiserAccountId()),
                eq(campaign.getId()),
                eq(42L),
                eq("広告タイトル"),
                eq("広告本文 markdown"));

        ArgumentCaptor<AdAnnouncementDelivery> captor = ArgumentCaptor.forClass(AdAnnouncementDelivery.class);
        verify(deliveryRepository, times(1)).save(captor.capture());
        AdAnnouncementDelivery saved = captor.getValue();
        assertThat(saved.getCampaignId()).isEqualTo(campaign.getId());
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getDeliveredAt()).isNotNull();
        assertThat(saved.getMonthKey()).matches("\\d{4}-\\d{2}");
    }

    @Test
    @DisplayName("deliver: campaign が null なら IllegalArgumentException")
    void deliver_null_campaign() {
        AdMessagingCampaignChannel channel = buildChannel(UUID.randomUUID());
        try {
            service.deliver(null, channel, 1L);
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("必須");
            return;
        }
        org.junit.jupiter.api.Assertions.fail("IllegalArgumentException が発生する想定");
    }

    @Test
    @DisplayName("deliver: userId が null なら IllegalArgumentException")
    void deliver_null_userId() {
        AdMessagingCampaign campaign = buildCampaign();
        AdMessagingCampaignChannel channel = buildChannel(campaign.getId());
        try {
            service.deliver(campaign, channel, null);
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("必須");
            return;
        }
        org.junit.jupiter.api.Assertions.fail("IllegalArgumentException が発生する想定");
    }
}
