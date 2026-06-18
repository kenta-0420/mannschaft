package com.mannschaft.app.moderation.service;

import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.repository.ContentReportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * F10.1.1 / P3b: {@link ReportScopeStatsQueryService} 単体テスト。
 *
 * <p>番人テスト: stats は {@code scope_type + scope_id} で絞り込まれ（全体集計でなく）、
 * 「未対応(PENDING)／確認中(REVIEWING)」のみを返すこと（テナント越境防止）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportScopeStatsQueryService 単体テスト")
class ReportScopeStatsQueryServiceTest {

    @Mock
    private ContentReportRepository reportRepository;

    @InjectMocks
    private ReportScopeStatsQueryService service;

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 42L;

    @Test
    @DisplayName("scopeStats → 当該 scope の PENDING/REVIEWING 件数を返す")
    void scopeStatsReturnsPendingAndReviewing() {
        given(reportRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, ReportStatus.PENDING))
                .willReturn(5L);
        given(reportRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, ReportStatus.REVIEWING))
                .willReturn(2L);

        ReportScopeStatsQueryService.ScopeReportStats result = service.scopeStats(SCOPE_TYPE, SCOPE_ID);

        assertThat(result.pendingCount()).isEqualTo(5L);
        assertThat(result.reviewingCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("scopeStats → 番人: 全カウントが scope_type + scope_id で絞り込まれる（IDOR 防止・全体集計でない）")
    void scopeStatsScopesToScope() {
        given(reportRepository.countByScopeTypeAndScopeIdAndStatus(eq(SCOPE_TYPE), eq(SCOPE_ID), eq(ReportStatus.PENDING)))
                .willReturn(0L);
        given(reportRepository.countByScopeTypeAndScopeIdAndStatus(eq(SCOPE_TYPE), eq(SCOPE_ID), eq(ReportStatus.REVIEWING)))
                .willReturn(0L);

        service.scopeStats(SCOPE_TYPE, SCOPE_ID);

        verify(reportRepository).countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, ReportStatus.PENDING);
        verify(reportRepository).countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, ReportStatus.REVIEWING);
    }
}
