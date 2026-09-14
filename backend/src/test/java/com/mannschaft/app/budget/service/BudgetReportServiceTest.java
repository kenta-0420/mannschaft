package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetMapper;
import com.mannschaft.app.budget.BudgetReportStatus;
import com.mannschaft.app.budget.BudgetReportType;
import com.mannschaft.app.budget.entity.BudgetReportEntity;
import com.mannschaft.app.budget.repository.BudgetReportRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.storage.StorageErrorCode;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.common.storage.acl.StorageAccessService;
import com.mannschaft.app.common.storage.acl.StorageAclService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BudgetReportServiceTest {

    @Mock private BudgetReportRepository reportRepository;
    @Mock private BudgetFiscalYearService fiscalYearService;
    @Mock private BudgetSummaryService summaryService;
    @Mock private BudgetMapper budgetMapper;
    @Mock private AccessControlService accessControlService;
    @Mock private StorageService storageService;
    @Mock private StorageAclService storageAclService;
    @Mock private StorageAccessService storageAccessService;

    @InjectMocks private BudgetReportService service;

    @Test
    void getDownloadUrl_usesExactClaimedReportAcl() {
        BudgetReportEntity report = completedReport();
        given(reportRepository.findById(41L)).willReturn(Optional.of(report));
        given(accessControlService.isMember(10L, 7L, "TEAM")).willReturn(true);
        given(storageAccessService.generateDownloadUrl(anyString(), any(), any(), any(), any()))
                .willReturn("https://storage.example/signed");

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(10L);

            var response = service.getDownloadUrl(41L);

            assertThat(response.downloadUrl()).isEqualTo("https://storage.example/signed");
        }
        verify(storageAccessService).generateDownloadUrl(anyString(), any(), any(), any(), any());
    }

    @Test
    void getDownloadUrl_whenClaimDoesNotMatch_failsClosed() {
        BudgetReportEntity report = completedReport();
        given(reportRepository.findById(41L)).willReturn(Optional.of(report));
        given(accessControlService.isMember(10L, 7L, "TEAM")).willReturn(true);
        given(storageAccessService.generateDownloadUrl(anyString(), any(), any(), any(), any()))
                .willThrow(new BusinessException(StorageErrorCode.ACL_NOT_FOUND));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(10L);

            assertThatThrownBy(() -> service.getDownloadUrl(41L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(StorageErrorCode.ACL_NOT_FOUND.getMessage());
        }
    }

    @Test
    void getDownloadUrl_whenReportDoesNotExist_returnsNotFound() {
        given(reportRepository.findById(41L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDownloadUrl(41L))
                .isInstanceOf(BusinessException.class);
    }

    private BudgetReportEntity completedReport() {
        BudgetReportEntity report = BudgetReportEntity.builder()
                .fiscalYearId(3L).scopeType("TEAM").scopeId(7L)
                .reportType(BudgetReportType.ANNUAL)
                .periodStart(LocalDate.of(2026, 1, 1)).periodEnd(LocalDate.of(2026, 12, 31))
                .generatedBy(10L).status(BudgetReportStatus.GENERATING).build();
        ReflectionTestUtils.setField(report, "id", 41L);
        report.markCompleted("budget/reports/3/41.csv", 1L);
        return report;
    }
}
