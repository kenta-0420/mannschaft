package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.entity.UserAdPreference;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignChannelRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F09.17 Phase 11-b ε-B {@link AdCampaignDeliveryDispatcher} 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdCampaignDeliveryDispatcher 単体テスト")
class AdCampaignDeliveryDispatcherTest {

    @Mock private UserAdPreferenceService userAdPreferenceService;
    @Mock private AdFrequencyCapService frequencyCapService;
    @Mock private AdCampaignDeliveryClaimService claimService;
    @Mock private AdMessagingCampaignChannelRepository channelRepository;
    @Mock private UserRepository userRepository;
    @Mock private AdAnnouncementChannelService announcementChannelService;
    @Mock private AdEmailChannelService emailChannelService;
    @Mock private AdPushChannelService pushChannelService;
    @Mock private AdBannerChannelService bannerChannelService;
    @InjectMocks private AdCampaignDeliveryDispatcher dispatcher;

    private AdMessagingCampaign buildCampaign() {
        AdMessagingCampaign campaign = AdMessagingCampaign.builder()
                .advertiserAccountId(100L)
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

    private AdMessagingCampaignChannel buildChannel(UUID campaignId, AdChannelType type, String locale) {
        AdMessagingCampaignChannel ch = AdMessagingCampaignChannel.builder()
                .campaignId(campaignId)
                .channelType(type)
                .locale(locale)
                .subject("広告")
                .bodyMarkdown("本文")
                .build();
        ch.setId(UUID.randomUUID());
        return ch;
    }

    private UserAdPreference prefAllAccept() {
        return UserAdPreference.builder()
                .userId(42L)
                .acceptAnnouncementAds(Boolean.TRUE)
                .acceptEmailAds(Boolean.TRUE)
                .acceptPushAds(Boolean.TRUE)
                .acceptBannerAds(Boolean.TRUE)
                .blockedAdvertiserAccountIds("[]")
                .unsubscribeTokenVersion(0)
                .build();
    }

    @Test
    @DisplayName("deliverForUser: 正常系 — 全チャネル登録ありで全て配信される")
    void deliverForUser_正常系() {
        AdMessagingCampaign campaign = buildCampaign();
        AdMessagingCampaignChannel ann = buildChannel(campaign.getId(), AdChannelType.ANNOUNCEMENT, "ja");
        AdMessagingCampaignChannel email = buildChannel(campaign.getId(), AdChannelType.EMAIL, "ja");
        AdMessagingCampaignChannel push = buildChannel(campaign.getId(), AdChannelType.PUSH, "ja");
        AdMessagingCampaignChannel banner = buildChannel(campaign.getId(), AdChannelType.BANNER, "ja");

        given(userAdPreferenceService.getOrCreateEntityForUser(42L)).willReturn(prefAllAccept());
        given(userAdPreferenceService.decodeBlockedAdvertiserIds(any()))
                .willReturn(List.of());
        given(frequencyCapService.tryConsume(eq(42L), eq(100L), eq(campaign.getId())))
                .willReturn(true);
        given(frequencyCapService.resolveUserZone(42L)).willReturn(java.time.ZoneId.of("Asia/Tokyo"));
        given(claimService.tryClaim(eq(campaign.getId()), eq(42L), any())).willReturn(true);
        given(channelRepository.findByCampaignId(campaign.getId()))
                .willReturn(List.of(ann, email, push, banner));
        given(userRepository.findLocaleById(42L)).willReturn(Optional.of("ja"));
        given(emailChannelService.deliver(eq(campaign), eq(email), eq(42L))).willReturn(true);
        given(pushChannelService.deliver(eq(campaign), eq(push), eq(42L))).willReturn(true);
        given(bannerChannelService.deliver(eq(campaign), eq(banner), eq(42L))).willReturn(true);

        AdDeliveryOutcome result = dispatcher.deliverForUser(campaign, 42L);

        assertThat(result).isEqualTo(AdDeliveryOutcome.DELIVERED);
        verify(announcementChannelService, times(1)).deliver(campaign, ann, 42L);
        verify(emailChannelService, times(1)).deliver(campaign, email, 42L);
        verify(pushChannelService, times(1)).deliver(campaign, push, 42L);
        verify(bannerChannelService, times(1)).deliver(campaign, banner, 42L);
    }

    @Test
    @DisplayName("deliverForUser: 広告主ブロック中なら全チャネルスキップ")
    void deliverForUser_advertiser_blocked() {
        AdMessagingCampaign campaign = buildCampaign();
        given(userAdPreferenceService.getOrCreateEntityForUser(42L)).willReturn(prefAllAccept());
        given(userAdPreferenceService.decodeBlockedAdvertiserIds(any()))
                .willReturn(List.of(100L)); // ブロック中

        AdDeliveryOutcome result = dispatcher.deliverForUser(campaign, 42L);

        assertThat(result).isEqualTo(AdDeliveryOutcome.SKIPPED);
        verify(frequencyCapService, never()).tryConsume(anyLong(), anyLong(), any());
        verify(announcementChannelService, never()).deliver(any(), any(), anyLong());
    }

    @Test
    @DisplayName("deliverForUser: FreqCap 超過なら全チャネルスキップ")
    void deliverForUser_freqcap_exceeded() {
        AdMessagingCampaign campaign = buildCampaign();
        given(userAdPreferenceService.getOrCreateEntityForUser(42L)).willReturn(prefAllAccept());
        given(userAdPreferenceService.decodeBlockedAdvertiserIds(any())).willReturn(List.of());
        given(frequencyCapService.tryConsume(eq(42L), eq(100L), eq(campaign.getId())))
                .willReturn(false);

        AdDeliveryOutcome result = dispatcher.deliverForUser(campaign, 42L);

        assertThat(result).isEqualTo(AdDeliveryOutcome.SKIPPED);
        verify(claimService, never()).tryClaim(any(), anyLong(), any());
        verify(channelRepository, never()).findByCampaignId(any());
        verify(announcementChannelService, never()).deliver(any(), any(), anyLong());
    }

    @Test
    @DisplayName("deliverForUser: ANNOUNCEMENT のみ accept=false で他は配信される")
    void deliverForUser_announcement_opt_out() {
        AdMessagingCampaign campaign = buildCampaign();
        AdMessagingCampaignChannel ann = buildChannel(campaign.getId(), AdChannelType.ANNOUNCEMENT, "ja");
        AdMessagingCampaignChannel email = buildChannel(campaign.getId(), AdChannelType.EMAIL, "ja");

        UserAdPreference pref = prefAllAccept();
        pref.setAcceptAnnouncementAds(Boolean.FALSE);

        given(userAdPreferenceService.getOrCreateEntityForUser(42L)).willReturn(pref);
        given(userAdPreferenceService.decodeBlockedAdvertiserIds(any())).willReturn(List.of());
        given(frequencyCapService.tryConsume(eq(42L), eq(100L), eq(campaign.getId())))
                .willReturn(true);
        given(frequencyCapService.resolveUserZone(42L)).willReturn(java.time.ZoneId.of("Asia/Tokyo"));
        given(claimService.tryClaim(eq(campaign.getId()), eq(42L), any())).willReturn(true);
        given(channelRepository.findByCampaignId(campaign.getId()))
                .willReturn(List.of(ann, email));
        given(userRepository.findLocaleById(42L)).willReturn(Optional.of("ja"));
        given(emailChannelService.deliver(eq(campaign), eq(email), eq(42L))).willReturn(true);

        AdDeliveryOutcome result = dispatcher.deliverForUser(campaign, 42L);

        assertThat(result).isEqualTo(AdDeliveryOutcome.DELIVERED);
        verify(announcementChannelService, never()).deliver(any(), any(), anyLong());
        verify(emailChannelService, times(1)).deliver(campaign, email, 42L);
    }

    @Test
    @DisplayName("F09.19.7 AC-7.4: channel 未登録（0 件配信）で releaseSlot が消費週で呼ばれ FreqCap を返却する")
    void deliverForUser_no_channels_releasesFreqCap() {
        AdMessagingCampaign campaign = buildCampaign();
        given(userAdPreferenceService.getOrCreateEntityForUser(42L)).willReturn(prefAllAccept());
        given(userAdPreferenceService.decodeBlockedAdvertiserIds(any())).willReturn(List.of());
        given(frequencyCapService.tryConsume(eq(42L), eq(100L), eq(campaign.getId())))
                .willReturn(true);
        given(frequencyCapService.resolveUserZone(42L)).willReturn(java.time.ZoneId.of("Asia/Tokyo"));
        given(claimService.tryClaim(eq(campaign.getId()), eq(42L), any())).willReturn(true);
        // channel 未登録 → 全チャネル skip → rollbackFreqCapAndClaim 経路
        given(channelRepository.findByCampaignId(campaign.getId())).willReturn(List.of());

        AdDeliveryOutcome result = dispatcher.deliverForUser(campaign, 42L);

        assertThat(result).isEqualTo(AdDeliveryOutcome.SKIPPED);
        java.time.LocalDate expectedWeek = AdFrequencyCapService
                .currentWeekStart(java.time.ZoneId.of("Asia/Tokyo"));
        verify(frequencyCapService, times(1)).releaseSlot(42L, 100L, expectedWeek);
        verify(claimService, times(1)).releaseClaim(campaign.getId(), 42L, expectedWeek);
    }

    @Test
    @DisplayName("F09.19.7 AC-7.4: 全チャネル opt-out（0 件配信）でも releaseSlot が呼ばれる")
    void deliverForUser_all_opt_out_releasesFreqCap() {
        AdMessagingCampaign campaign = buildCampaign();
        AdMessagingCampaignChannel email = buildChannel(campaign.getId(), AdChannelType.EMAIL, "ja");

        UserAdPreference pref = prefAllAccept();
        pref.setAcceptAnnouncementAds(Boolean.FALSE);
        pref.setAcceptEmailAds(Boolean.FALSE);
        pref.setAcceptPushAds(Boolean.FALSE);
        pref.setAcceptBannerAds(Boolean.FALSE);

        given(userAdPreferenceService.getOrCreateEntityForUser(42L)).willReturn(pref);
        given(userAdPreferenceService.decodeBlockedAdvertiserIds(any())).willReturn(List.of());
        given(frequencyCapService.tryConsume(eq(42L), eq(100L), eq(campaign.getId())))
                .willReturn(true);
        given(frequencyCapService.resolveUserZone(42L)).willReturn(java.time.ZoneId.of("Asia/Tokyo"));
        given(claimService.tryClaim(eq(campaign.getId()), eq(42L), any())).willReturn(true);
        given(channelRepository.findByCampaignId(campaign.getId())).willReturn(List.of(email));
        given(userRepository.findLocaleById(42L)).willReturn(Optional.of("ja"));

        AdDeliveryOutcome result = dispatcher.deliverForUser(campaign, 42L);

        assertThat(result).isEqualTo(AdDeliveryOutcome.SKIPPED);
        verify(frequencyCapService, times(1)).releaseSlot(eq(42L), eq(100L), any());
        verify(claimService, times(1)).releaseClaim(eq(campaign.getId()), eq(42L), any());
    }

    @Test
    @DisplayName("claim-then-act: DB claim が既に確保済みなら SKIPPED_ALREADY_CLAIMED を返し FreqCap を返却する")
    void deliverForUser_claim_conflict_releasesFreqCapAndSkips() {
        AdMessagingCampaign campaign = buildCampaign();
        given(userAdPreferenceService.getOrCreateEntityForUser(42L)).willReturn(prefAllAccept());
        given(userAdPreferenceService.decodeBlockedAdvertiserIds(any())).willReturn(List.of());
        given(frequencyCapService.tryConsume(eq(42L), eq(100L), eq(campaign.getId())))
                .willReturn(true);
        given(frequencyCapService.resolveUserZone(42L)).willReturn(java.time.ZoneId.of("Asia/Tokyo"));
        given(claimService.tryClaim(eq(campaign.getId()), eq(42L), any())).willReturn(false);

        AdDeliveryOutcome result = dispatcher.deliverForUser(campaign, 42L);

        assertThat(result).isEqualTo(AdDeliveryOutcome.SKIPPED_ALREADY_CLAIMED);
        verify(frequencyCapService, times(1)).releaseSlot(eq(42L), eq(100L), any());
        verify(channelRepository, never()).findByCampaignId(any());
    }

    @Test
    @DisplayName("fail-closed: FreqCap 判定で例外（Valkey 接続異常等）が起きたら配信せず SKIPPED_FREQ_CAP_UNAVAILABLE を返す")
    void deliverForUser_freqcap_error_failsClosed() {
        AdMessagingCampaign campaign = buildCampaign();
        given(userAdPreferenceService.getOrCreateEntityForUser(42L)).willReturn(prefAllAccept());
        given(userAdPreferenceService.decodeBlockedAdvertiserIds(any())).willReturn(List.of());
        given(frequencyCapService.tryConsume(eq(42L), eq(100L), eq(campaign.getId())))
                .willThrow(new org.springframework.data.redis.RedisConnectionFailureException("接続不可"));

        AdDeliveryOutcome result = dispatcher.deliverForUser(campaign, 42L);

        assertThat(result).isEqualTo(AdDeliveryOutcome.SKIPPED_FREQ_CAP_UNAVAILABLE);
        verify(claimService, never()).tryClaim(any(), anyLong(), any());
        verify(channelRepository, never()).findByCampaignId(any());
    }

    @Test
    @DisplayName("pickLocaleChannelByType: users.locale 一致が最優先される")
    void pickLocaleChannelByType_locale_match() {
        UUID cid = UUID.randomUUID();
        AdMessagingCampaignChannel ja = buildChannel(cid, AdChannelType.EMAIL, "ja");
        AdMessagingCampaignChannel en = buildChannel(cid, AdChannelType.EMAIL, "en");
        AdMessagingCampaignChannel zh = buildChannel(cid, AdChannelType.EMAIL, "zh");

        var result = dispatcher.pickLocaleChannelByType(List.of(en, ja, zh), "en");
        assertThat(result.get(AdChannelType.EMAIL)).isSameAs(en);
    }

    @Test
    @DisplayName("pickLocaleChannelByType: users.locale 一致なしなら ja フォールバック")
    void pickLocaleChannelByType_ja_fallback() {
        UUID cid = UUID.randomUUID();
        AdMessagingCampaignChannel ja = buildChannel(cid, AdChannelType.EMAIL, "ja");
        AdMessagingCampaignChannel zh = buildChannel(cid, AdChannelType.EMAIL, "zh");

        var result = dispatcher.pickLocaleChannelByType(List.of(zh, ja), "ko");
        assertThat(result.get(AdChannelType.EMAIL)).isSameAs(ja);
    }
}
