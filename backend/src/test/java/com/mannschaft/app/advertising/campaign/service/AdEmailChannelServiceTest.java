package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdEmailDelivery;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.entity.UserAdPreference;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.repository.AdEmailDeliveryRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.directmail.entity.DirectMailRecipientEntity;
import com.mannschaft.app.directmail.service.DirectMailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
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
 * F09.17 Phase 11-b ε-B {@link AdEmailChannelService} 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdEmailChannelService 単体テスト")
class AdEmailChannelServiceTest {

    @Mock private DirectMailService directMailService;
    @Mock private UserRepository userRepository;
    @Mock private UserAdPreferenceService userAdPreferenceService;
    @Mock private AdUnsubscribeJwtService unsubscribeJwtService;
    @Mock private AdOpenPixelJwtService openPixelJwtService;
    @Mock private AdEmailDeliveryRepository deliveryRepository;

    private AdEmailChannelService service;

    private static final String APP_BASE_URL = "http://localhost:3000";

    @BeforeEach
    void setUp() {
        service = new AdEmailChannelService(
                directMailService,
                userRepository,
                userAdPreferenceService,
                unsubscribeJwtService,
                openPixelJwtService,
                deliveryRepository,
                APP_BASE_URL);
    }

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
                .channelType(AdChannelType.EMAIL)
                .locale("ja")
                .subject("広告タイトル")
                .bodyMarkdown("広告本文 markdown\n2 行目")
                .build();
        ch.setId(UUID.randomUUID());
        return ch;
    }

    private DirectMailRecipientEntity buildRecipient() throws Exception {
        DirectMailRecipientEntity r = DirectMailRecipientEntity.builder()
                .mailLogId(1L)
                .userId(42L)
                .email("user@example.com")
                .build();
        // id を強制セット
        Field idField = DirectMailRecipientEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(r, 9999L);
        return r;
    }

    @Test
    @DisplayName("deliver: 正常系で DirectMailService.sendSystemAdMail を呼び ad_email_deliveries に保存する")
    void deliver_正常系() throws Exception {
        AdMessagingCampaign campaign = buildCampaign();
        AdMessagingCampaignChannel channel = buildChannel(campaign.getId());

        UserEntity user = UserEntity.builder().email("user@example.com").build();
        given(userRepository.findById(42L)).willReturn(Optional.of(user));

        UserAdPreference pref = UserAdPreference.builder()
                .userId(42L)
                .unsubscribeTokenVersion(5)
                .build();
        given(userAdPreferenceService.getOrCreateEntityForUser(42L)).willReturn(pref);
        given(unsubscribeJwtService.generate(42L, 5, "EMAIL")).willReturn("UNSUB_JWT");

        DirectMailRecipientEntity recipient = buildRecipient();
        given(directMailService.sendSystemAdMail(
                eq(100L), eq(42L), eq("user@example.com"), any(String.class), any(String.class)))
                .willReturn(recipient);

        boolean result = service.deliver(campaign, channel, 42L);

        assertThat(result).isTrue();
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(directMailService, times(1)).sendSystemAdMail(
                eq(100L), eq(42L), eq("user@example.com"),
                eq("広告タイトル"), bodyCaptor.capture());
        // unsubscribe JWT がメール本文に埋め込まれていること
        assertThat(bodyCaptor.getValue()).contains("UNSUB_JWT").contains("配信停止");

        ArgumentCaptor<AdEmailDelivery> deliveryCaptor = ArgumentCaptor.forClass(AdEmailDelivery.class);
        verify(deliveryRepository, times(1)).save(deliveryCaptor.capture());
        AdEmailDelivery saved = deliveryCaptor.getValue();
        assertThat(saved.getCampaignId()).isEqualTo(campaign.getId());
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getDirectMailRecipientId()).isEqualTo(9999L);
        assertThat(saved.getMonthKey()).matches("\\d{4}-\\d{2}");
    }

    @Test
    @DisplayName("deliver: email 未取得ユーザーは false 返却（DirectMailService 呼ばれない）")
    void deliver_email_なしユーザーは_skip() {
        AdMessagingCampaign campaign = buildCampaign();
        AdMessagingCampaignChannel channel = buildChannel(campaign.getId());

        given(userRepository.findById(42L)).willReturn(Optional.empty());

        boolean result = service.deliver(campaign, channel, 42L);

        assertThat(result).isFalse();
        verify(directMailService, never()).sendSystemAdMail(
                anyLong(), anyLong(), any(), any(), any());
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    @DisplayName("buildHtmlBody: unsubscribe SPA リンクと open pixel が埋め込まれる")
    void buildHtmlBody_リンク埋め込み() {
        String html = service.buildHtmlBody("本文", "JWT-U", "JWT-P");
        assertThat(html).contains("<html>").contains("<body>");
        // F09.17 残課題 4: メール本文の unsubscribe URL は SPA 経路 (/ads/unsubscribe) に切替済み
        assertThat(html).contains(APP_BASE_URL + "/ads/unsubscribe?token=JWT-U");
        assertThat(html).contains("/api/v1/ads/pixels/open?token=JWT-P");
        assertThat(html).contains("配信停止");
    }

    @Test
    @DisplayName("buildHtmlBody: open pixel token が null なら <img> タグは無し")
    void buildHtmlBody_pixel_null_は_imgなし() {
        String html = service.buildHtmlBody("本文", "JWT-U", null);
        assertThat(html).doesNotContain("/api/v1/ads/pixels/open");
        assertThat(html).contains(APP_BASE_URL + "/ads/unsubscribe?token=JWT-U");
    }
}
