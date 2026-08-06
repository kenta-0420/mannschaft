package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F09.17 Phase 11-b ε-B {@link AdCampaignDeliveryWorker} 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdCampaignDeliveryWorker 単体テスト")
class AdCampaignDeliveryWorkerTest {

    @Mock private AdMessagingCampaignRepository campaignRepository;
    @Mock private AdAudienceResolver audienceResolver;
    @Mock private AdCampaignDeliveryDispatcher dispatcher;
    @InjectMocks private AdCampaignDeliveryWorker worker;

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

    @Test
    @DisplayName("processCampaign: 候補ユーザー全員に dispatcher.deliverForUser が呼ばれる")
    void processCampaign_全ユーザー配信() {
        AdMessagingCampaign campaign = buildCampaign();
        given(audienceResolver.streamCandidateUserIds(campaign.getId()))
                .willReturn(Stream.of(1L, 2L, 3L));
        given(dispatcher.deliverForUser(eq(campaign), anyLong())).willReturn(true);

        AdCampaignDeliveryWorker.DeliveryResult r = worker.processCampaign(campaign);

        assertThat(r.users()).isEqualTo(3);
        assertThat(r.delivered()).isEqualTo(3);
        verify(dispatcher, times(1)).deliverForUser(campaign, 1L);
        verify(dispatcher, times(1)).deliverForUser(campaign, 2L);
        verify(dispatcher, times(1)).deliverForUser(campaign, 3L);
    }

    @Test
    @DisplayName("processCampaign: 1 ユーザー失敗で次ユーザーへ続行する")
    void processCampaign_例外で続行() {
        AdMessagingCampaign campaign = buildCampaign();
        given(audienceResolver.streamCandidateUserIds(campaign.getId()))
                .willReturn(Stream.of(1L, 2L, 3L));
        given(dispatcher.deliverForUser(eq(campaign), eq(1L))).willReturn(true);
        given(dispatcher.deliverForUser(eq(campaign), eq(2L)))
                .willThrow(new RuntimeException("user2 failure"));
        given(dispatcher.deliverForUser(eq(campaign), eq(3L))).willReturn(true);

        AdCampaignDeliveryWorker.DeliveryResult r = worker.processCampaign(campaign);

        assertThat(r.users()).isEqualTo(3);
        // delivered: user1=true, user2=失敗カウント外, user3=true → 2
        assertThat(r.delivered()).isEqualTo(2);
        verify(dispatcher, times(1)).deliverForUser(campaign, 1L);
        verify(dispatcher, times(1)).deliverForUser(campaign, 2L);
        verify(dispatcher, times(1)).deliverForUser(campaign, 3L);
    }

    @Test
    @DisplayName("processCampaign: 候補が CHUNK_SIZE を超える場合は上限件数のみ処理し、次回はカーソルの続きから処理する")
    void processCampaign_boundedByChunkSizeAndResumesFromCursor() {
        AdMessagingCampaign campaign = buildCampaign();
        int total = AdCampaignDeliveryWorker.CHUNK_SIZE + 10;
        List<Long> allIds = java.util.stream.LongStream.rangeClosed(1, total).boxed().toList();

        given(audienceResolver.streamCandidateUserIds(campaign.getId()))
                .willAnswer(inv -> allIds.stream());
        given(dispatcher.deliverForUser(eq(campaign), anyLong())).willReturn(true);

        // 1 回目: 先頭から CHUNK_SIZE 件のみ処理される（有界化）
        AdCampaignDeliveryWorker.DeliveryResult r1 = worker.processCampaign(campaign);
        assertThat(r1.users()).isEqualTo(AdCampaignDeliveryWorker.CHUNK_SIZE);
        for (long id = 1; id <= AdCampaignDeliveryWorker.CHUNK_SIZE; id++) {
            verify(dispatcher, times(1)).deliverForUser(campaign, id);
        }
        // 残りの候補はまだ処理されていない
        verify(dispatcher, never()).deliverForUser(campaign, (long) AdCampaignDeliveryWorker.CHUNK_SIZE + 1);

        // 2 回目: カーソルの続きから残り 10 件が処理される（取りこぼしなし）
        AdCampaignDeliveryWorker.DeliveryResult r2 = worker.processCampaign(campaign);
        assertThat(r2.users()).isEqualTo(10);
        for (long id = AdCampaignDeliveryWorker.CHUNK_SIZE + 1; id <= total; id++) {
            verify(dispatcher, times(1)).deliverForUser(campaign, id);
        }
    }

    @Test
    @DisplayName("loadActiveCampaigns: status=DELIVERING で FOR UPDATE クエリを呼ぶ")
    void loadActiveCampaigns_クエリ呼び出し() {
        given(campaignRepository.findActiveDeliveringForUpdate(
                eq(AdCampaignStatus.DELIVERING), any(LocalDateTime.class)))
                .willReturn(List.of());

        List<AdMessagingCampaign> result = worker.loadActiveCampaigns();

        assertThat(result).isEmpty();
        verify(campaignRepository, times(1))
                .findActiveDeliveringForUpdate(eq(AdCampaignStatus.DELIVERING), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("runDelivery: 対象キャンペーンを順次処理する（個別失敗で全体停止しない）")
    void runDelivery_順次処理() {
        AdMessagingCampaign c1 = buildCampaign();
        AdMessagingCampaign c2 = buildCampaign();
        given(campaignRepository.findActiveDeliveringForUpdate(
                eq(AdCampaignStatus.DELIVERING), any(LocalDateTime.class)))
                .willReturn(List.of(c1, c2));
        given(audienceResolver.streamCandidateUserIds(c1.getId()))
                .willThrow(new RuntimeException("c1 failure"));
        given(audienceResolver.streamCandidateUserIds(c2.getId()))
                .willReturn(Stream.of(10L));
        given(dispatcher.deliverForUser(eq(c2), eq(10L))).willReturn(true);

        worker.runDelivery();

        // c1 は例外で skip、c2 だけ処理される
        verify(dispatcher, never()).deliverForUser(eq(c1), anyLong());
        verify(dispatcher, times(1)).deliverForUser(c2, 10L);
    }
}
