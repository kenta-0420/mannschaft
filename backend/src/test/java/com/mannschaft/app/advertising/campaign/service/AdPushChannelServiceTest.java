package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.entity.AdPushDelivery;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.advertising.campaign.repository.AdPushDeliveryRepository;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
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
 * F09.17 Phase 11-b ε-B {@link AdPushChannelService} 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdPushChannelService 単体テスト")
class AdPushChannelServiceTest {

    @Mock private NotificationService notificationService;
    @Mock private NotificationDispatchService dispatchService;
    @Mock private AdPushDeliveryRepository deliveryRepository;
    /** Issue #2715 CMP-055 lot C-5/C-6: newly added i18n dependencies. */
    @Mock private MessageSource messageSource;

    @InjectMocks private AdPushChannelService service;


    /**
     * Issue #2715 CMP-055 lot C-5/C-6: the bare MessageSource mock would return null for
     * title/body. Return the supplied default message so existing assertions keep working.
     */
    @org.junit.jupiter.api.BeforeEach
    void stubI18nMessageSource() {
        org.mockito.Mockito.lenient().when(messageSource.getMessage(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(2));
    }
    private AdMessagingCampaign buildCampaign() {
        AdMessagingCampaign campaign = AdMessagingCampaign.builder()
                .advertiserAccountId(100L)
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(1L)
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

    private AdMessagingCampaignChannel buildChannel(UUID campaignId, String body) {
        AdMessagingCampaignChannel ch = AdMessagingCampaignChannel.builder()
                .campaignId(campaignId)
                .channelType(AdChannelType.PUSH)
                .locale("ja")
                .subject("広告タイトル")
                .bodyMarkdown(body)
                .build();
        ch.setId(UUID.randomUUID());
        return ch;
    }

    private NotificationEntity buildNotification() throws Exception {
        NotificationEntity n = NotificationEntity.builder()
                .userId(42L)
                .notificationType("ADVERTISER_AD")
                .priority(NotificationPriority.LOW)
                .title("広告タイトル")
                .body("【広告】本文")
                .sourceType("ADVERTISER_CAMPAIGN")
                .sourceId(1L)
                .scopeType(NotificationScopeType.SYSTEM)
                .scopeId(100L)
                .build();
        Field idField = NotificationEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(n, 7777L);
        return n;
    }

    @Test
    @DisplayName("deliver: 正常系で【広告】プレフィックスを付与し dispatch + delivery 保存")
    void deliver_正常系() throws Exception {
        AdMessagingCampaign campaign = buildCampaign();
        AdMessagingCampaignChannel channel = buildChannel(campaign.getId(), "オファー詳細");

        NotificationEntity notif = buildNotification();
        given(notificationService.createNotification(
                eq(42L),
                eq("ADVERTISER_AD"),
                eq(NotificationPriority.LOW),
                eq("広告タイトル"),
                any(String.class),
                eq("ADVERTISER_CAMPAIGN"),
                anyLong(),
                eq(NotificationScopeType.SYSTEM),
                eq(100L),
                any(),
                any(),
                eq(1L)))
                .willReturn(notif);

        boolean result = service.deliver(campaign, channel, 42L);

        assertThat(result).isTrue();
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService, times(1)).createNotification(
                eq(42L), any(), any(), any(), bodyCaptor.capture(),
                any(), anyLong(), any(), any(), any(), any(), any());
        assertThat(bodyCaptor.getValue()).startsWith("【広告】");
        verify(dispatchService, times(1)).dispatch(notif);

        ArgumentCaptor<AdPushDelivery> deliveryCaptor = ArgumentCaptor.forClass(AdPushDelivery.class);
        verify(deliveryRepository, times(1)).save(deliveryCaptor.capture());
        AdPushDelivery saved = deliveryCaptor.getValue();
        assertThat(saved.getCampaignId()).isEqualTo(campaign.getId());
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getNotificationId()).isEqualTo(7777L);
    }

    @Test
    @DisplayName("deliver: 本文に既に【広告】がある場合は二重付与しない")
    void deliver_二重付与しない() throws Exception {
        AdMessagingCampaign campaign = buildCampaign();
        AdMessagingCampaignChannel channel = buildChannel(campaign.getId(), "【広告】既存");

        NotificationEntity notif = buildNotification();
        given(notificationService.createNotification(
                anyLong(), any(), any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any()))
                .willReturn(notif);

        service.deliver(campaign, channel, 42L);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).createNotification(
                anyLong(), any(), any(), any(), bodyCaptor.capture(),
                any(), anyLong(), any(), any(), any(), any(), any());
        assertThat(bodyCaptor.getValue()).isEqualTo("【広告】既存");
    }

    @Test
    @DisplayName("deliver: notificationService が null を返す (visibility deny) なら false 返却・dispatch 呼ばれない")
    void deliver_visibility_deny_は_skip() {
        AdMessagingCampaign campaign = buildCampaign();
        AdMessagingCampaignChannel channel = buildChannel(campaign.getId(), "本文");
        given(notificationService.createNotification(
                anyLong(), any(), any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any()))
                .willReturn(null);

        boolean result = service.deliver(campaign, channel, 42L);

        assertThat(result).isFalse();
        verify(dispatchService, never()).dispatch(any());
        verify(deliveryRepository, never()).save(any());
    }
}
