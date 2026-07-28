package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.repository.AdBannerDeliveryRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F09.17 Phase 11-b ε-A {@link AdCampaignStateTransitionScheduler} 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdCampaignStateTransitionScheduler 単体テスト")
class AdCampaignStateTransitionSchedulerTest {

    @Mock private AdMessagingCampaignRepository campaignRepository;
    @Mock private AdBannerDeliveryRepository bannerDeliveryRepository;
    @Mock private AdFrequencyCapService frequencyCapService;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private AdCampaignStateTransitionScheduler scheduler;

    private AdMessagingCampaign buildCampaign(AdCampaignStatus status,
                                              LocalDateTime startsAt, LocalDateTime endsAt) {
        AdMessagingCampaign campaign = AdMessagingCampaign.builder()
                .advertiserAccountId(100L)
                .name("test")
                .status(status)
                .totalBudgetYen(50_000L)
                .consumedBudgetYen(0L)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .scheduledTimezone("Asia/Tokyo")
                .moderationStatus(AdModerationStatus.APPROVED)
                .createdByUserId(10L)
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();
        campaign.setId(UUID.randomUUID());
        return campaign;
    }

    @Test
    @DisplayName("promoteScheduledToDelivering: SCHEDULED かつ starts_at <= now を DELIVERING に変える")
    void promote_開始時刻到達済を配信中へ() {
        LocalDateTime past = LocalDateTime.now().minusHours(1);
        AdMessagingCampaign target = buildCampaign(AdCampaignStatus.SCHEDULED, past, past.plusDays(7));
        given(campaignRepository.findByStatusAndStartsAtLessThanEqualAndDeletedAtIsNull(
                eq(AdCampaignStatus.SCHEDULED), any(LocalDateTime.class)))
                .willReturn(List.of(target));

        int count = scheduler.promoteScheduledToDelivering();

        assertThat(count).isEqualTo(1);
        assertThat(target.getStatus()).isEqualTo(AdCampaignStatus.DELIVERING);
        verify(campaignRepository, times(1)).save(target);
        // F09.19.7 AC-7.5: システムユーザー(id=1) actor で CAMPAIGN_DELIVERING_STARTED 発火
        String meta = "{\"campaign_id\":\"" + target.getId() + "\"}";
        verify(auditLogService, times(1)).record(
                eq("CAMPAIGN_DELIVERING_STARTED"), eq(1L), any(), any(), any(),
                any(), any(), any(), eq(meta));
    }

    @Test
    @DisplayName("promoteScheduledToDelivering: 対象 0 件なら save 呼ばれない")
    void promote_対象なしは何もしない() {
        given(campaignRepository.findByStatusAndStartsAtLessThanEqualAndDeletedAtIsNull(
                eq(AdCampaignStatus.SCHEDULED), any(LocalDateTime.class)))
                .willReturn(List.of());

        int count = scheduler.promoteScheduledToDelivering();

        assertThat(count).isZero();
        verify(campaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("completeDeliveringPastEndsAt: DELIVERING かつ ends_at <= now を COMPLETED に変える")
    void complete_終了時刻到達済を完了へ() {
        LocalDateTime past = LocalDateTime.now().minusHours(1);
        AdMessagingCampaign target = buildCampaign(AdCampaignStatus.DELIVERING, past.minusDays(10), past);
        given(campaignRepository.findByStatusAndEndsAtLessThanEqualAndDeletedAtIsNull(
                eq(AdCampaignStatus.DELIVERING), any(LocalDateTime.class)))
                .willReturn(List.of(target));

        int count = scheduler.completeDeliveringPastEndsAt();

        assertThat(count).isEqualTo(1);
        assertThat(target.getStatus()).isEqualTo(AdCampaignStatus.COMPLETED);
        verify(campaignRepository, times(1)).save(target);
        // F09.19.7 AC-7.5: システムユーザー(id=1) actor で CAMPAIGN_COMPLETED 発火
        verify(auditLogService, times(1)).record(
                eq("CAMPAIGN_COMPLETED"), eq(1L), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("completeDeliveringPastEndsAt: 対象 0 件なら save 呼ばれない")
    void complete_対象なしは何もしない() {
        given(campaignRepository.findByStatusAndEndsAtLessThanEqualAndDeletedAtIsNull(
                eq(AdCampaignStatus.DELIVERING), any(LocalDateTime.class)))
                .willReturn(List.of());

        int count = scheduler.completeDeliveringPastEndsAt();

        assertThat(count).isZero();
        verify(campaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("runTransitions: 両方が順に呼ばれる")
    void runTransitions_両方順次() {
        given(campaignRepository.findByStatusAndStartsAtLessThanEqualAndDeletedAtIsNull(
                eq(AdCampaignStatus.SCHEDULED), any(LocalDateTime.class)))
                .willReturn(List.of());
        given(campaignRepository.findByStatusAndEndsAtLessThanEqualAndDeletedAtIsNull(
                eq(AdCampaignStatus.DELIVERING), any(LocalDateTime.class)))
                .willReturn(List.of());

        scheduler.runTransitions();

        verify(campaignRepository).findByStatusAndStartsAtLessThanEqualAndDeletedAtIsNull(
                eq(AdCampaignStatus.SCHEDULED), any(LocalDateTime.class));
        verify(campaignRepository).findByStatusAndEndsAtLessThanEqualAndDeletedAtIsNull(
                eq(AdCampaignStatus.DELIVERING), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("promoteScheduledToDelivering: 複数キャンペーンの場合も全て遷移")
    void promote_複数件も処理() {
        LocalDateTime past = LocalDateTime.now().minusHours(1);
        AdMessagingCampaign t1 = buildCampaign(AdCampaignStatus.SCHEDULED, past, past.plusDays(7));
        AdMessagingCampaign t2 = buildCampaign(AdCampaignStatus.SCHEDULED, past.minusHours(2), past.plusDays(5));
        given(campaignRepository.findByStatusAndStartsAtLessThanEqualAndDeletedAtIsNull(
                eq(AdCampaignStatus.SCHEDULED), any(LocalDateTime.class)))
                .willReturn(List.of(t1, t2));

        int count = scheduler.promoteScheduledToDelivering();

        assertThat(count).isEqualTo(2);
        ArgumentCaptor<AdMessagingCampaign> captor = ArgumentCaptor.forClass(AdMessagingCampaign.class);
        verify(campaignRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AdMessagingCampaign::getStatus)
                .containsOnly(AdCampaignStatus.DELIVERING);
    }
}
