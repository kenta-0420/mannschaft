package com.mannschaft.app.advertising.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.ReportFrequency;
import com.mannschaft.app.advertising.entity.AdCampaignEntity;
import com.mannschaft.app.advertising.entity.AdReportScheduleEntity;
import com.mannschaft.app.advertising.repository.AdCampaignRepository;
import com.mannschaft.app.advertising.repository.AdDailyStatsRepository;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReportSingleDeliveryService} のユニットテスト。
 *
 * <p>{@code includeCampaigns} 未指定時の「全キャンペーン」がプラットフォーム全体ではなく、
 * スケジュールが属する広告主アカウント（{@code advertiserAccountId}）に絞り込まれることを検証する
 * （CMP-035 で {@code ReportDeliveryBatchService} から分離・移設）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportSingleDeliveryService（レポート配信 1 件分ロジック）")
class ReportSingleDeliveryServiceTest {

    @Mock private AdCampaignRepository adCampaignRepository;
    @Mock private AdDailyStatsRepository adDailyStatsRepository;
    @Mock private EmailOutboxService emailOutboxService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ReportSingleDeliveryService service;

    @BeforeEach
    void setUp() {
        service = new ReportSingleDeliveryService(
                adCampaignRepository, adDailyStatsRepository, objectMapper, emailOutboxService);
    }

    @Test
    @DisplayName("includeCampaigns 未指定時は advertiserAccountId で絞り込んだキャンペーンのみを集計する"
            + "（他広告主のキャンペーンは混入しない）")
    void includeCampaigns未指定は自広告主のキャンペーンのみ集計() throws Exception {
        Long advertiserAccountId = 42L;
        AdReportScheduleEntity schedule = AdReportScheduleEntity.builder()
                .id(1L)
                .advertiserAccountId(advertiserAccountId)
                .frequency(ReportFrequency.WEEKLY)
                .recipients(objectMapper.writeValueAsString(List.of("advertiser@example.com")))
                .includeCampaigns(null)
                .enabled(true)
                .createdBy(1L)
                .build();

        AdCampaignEntity ownCampaign = AdCampaignEntity.builder()
                .id(100L)
                .advertiserAccountId(advertiserAccountId)
                .name("own-campaign")
                .build();

        given(adCampaignRepository.findByAdvertiserAccountId(advertiserAccountId))
                .willReturn(List.of(ownCampaign));
        given(adDailyStatsRepository.findByCampaignIdsAndDateBetween(anyList(), any(), any()))
                .willReturn(List.of());

        service.deliverSingleReport(schedule, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7));

        // 自広告主に限定した findByAdvertiserAccountId のみが呼ばれ、
        // プラットフォーム全体を無絞り込みで舐める findAll() は呼ばれない
        verify(adCampaignRepository).findByAdvertiserAccountId(advertiserAccountId);
        verify(adCampaignRepository, never()).findAll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> campaignIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(adDailyStatsRepository).findByCampaignIdsAndDateBetween(
                campaignIdsCaptor.capture(), any(), any());
        assertThat(campaignIdsCaptor.getValue()).containsExactly(100L);
    }

    @Test
    @DisplayName("配信先が空の場合は集計・送信を行わない")
    void 配信先が空なら何もしない() throws Exception {
        AdReportScheduleEntity schedule = AdReportScheduleEntity.builder()
                .id(2L)
                .advertiserAccountId(42L)
                .frequency(ReportFrequency.WEEKLY)
                .recipients(objectMapper.writeValueAsString(List.of()))
                .enabled(true)
                .createdBy(1L)
                .build();

        service.deliverSingleReport(schedule, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7));

        verify(emailOutboxService, never()).enqueue(any());
        verify(adCampaignRepository, never()).findByAdvertiserAccountId(any());
    }
}
