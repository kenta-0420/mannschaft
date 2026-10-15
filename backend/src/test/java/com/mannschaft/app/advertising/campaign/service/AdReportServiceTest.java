package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.dto.CreateAdReportRequest;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdUserReport;
import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.enums.AdReportReasonCode;
import com.mannschaft.app.advertising.campaign.repository.AdCampaignModerationLogRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.advertising.campaign.repository.AdUserReportRepository;
import com.mannschaft.app.advertising.repository.AdCampaignRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link AdReportService} の単体テスト（純 Mockito UT）。
 *
 * <p>MeAdReportController#create の自己スコープ性を固定する: 通報の作成者
 * （{@code reporterUserId}）は Service に渡された {@code userId} 引数（Controller で
 * {@code SecurityUtils.getCurrentUserId()} により確定）に一致し、リクエストボディから
 * 通報者を偽装する経路が無いことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdReportService 単体テスト（MeAdReportController#create の自己スコープ性）")
class AdReportServiceTest {

    private static final Long USER_ID = 555L;
    private static final UUID CAMPAIGN_ID = UUID.randomUUID();

    @Mock private AdUserReportRepository reportRepository;
    @Mock private AdMessagingCampaignRepository messagingCampaignRepository;
    @Mock private AdCampaignRepository operationalCampaignRepository;
    @Mock private AdCampaignModerationLogRepository moderationLogRepository;
    @Mock private AdvertiserAccountRepository advertiserAccountRepository;
    @Mock private AuditLogService auditLogService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC);

    private AdReportService service;

    @BeforeEach
    void setUp() {
        service = new AdReportService(
                reportRepository,
                messagingCampaignRepository,
                operationalCampaignRepository,
                moderationLogRepository,
                advertiserAccountRepository,
                auditLogService,
                clock);
    }

    @Test
    @DisplayName("正常系: 通報作成は認証主体を reporterUserId として記録する（自己スコープ）")
    void createReport_recordsCallerAsReporter() {
        // given
        AdMessagingCampaign campaign = AdMessagingCampaign.builder().build();
        given(messagingCampaignRepository.findById(CAMPAIGN_ID)).willReturn(Optional.of(campaign));
        given(reportRepository.saveAndFlush(any(AdUserReport.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(reportRepository.countByCampaignIdAndStatusIn(any(), any())).willReturn(1L);

        CreateAdReportRequest request = new CreateAdReportRequest(
                CAMPAIGN_ID, null, AdChannelType.BANNER, AdReportReasonCode.SPAM, "テスト通報");

        // when
        service.createReport(USER_ID, request);

        // then: リクエストが通報者 ID を一切含まなくても、保存される行の reporterUserId は
        // 呼び出し元ユーザー（USER_ID）に固定される。
        ArgumentCaptor<AdUserReport> captor = ArgumentCaptor.forClass(AdUserReport.class);
        verify(reportRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getReporterUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("異なる呼び出し元ユーザーで作成すると、それぞれ自分自身が reporterUserId になる")
    void createReport_differentCallers_recordThemselves() {
        AdMessagingCampaign campaign = AdMessagingCampaign.builder().build();
        given(messagingCampaignRepository.findById(CAMPAIGN_ID)).willReturn(Optional.of(campaign));
        given(reportRepository.saveAndFlush(any(AdUserReport.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(reportRepository.countByCampaignIdAndStatusIn(any(), any())).willReturn(1L);

        CreateAdReportRequest request = new CreateAdReportRequest(
                CAMPAIGN_ID, null, AdChannelType.BANNER, AdReportReasonCode.OFFENSIVE, null);

        Long otherUserId = 999L;
        service.createReport(otherUserId, request);

        ArgumentCaptor<AdUserReport> captor = ArgumentCaptor.forClass(AdUserReport.class);
        verify(reportRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getReporterUserId())
                .isEqualTo(otherUserId)
                .isNotEqualTo(USER_ID);
    }
}
