package com.mannschaft.app.proxy.service;

import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * ProxyMonthlySummaryService 単体テスト（F14.1 Phase 13-β）。
 */
@ExtendWith(MockitoExtension.class)
class ProxyMonthlySummaryServiceTest {

    @Mock
    private ProxyInputRecordRepository recordRepository;

    @Mock
    private PdfGeneratorService pdfGeneratorService;

    @Mock
    private StorageService storageService;

    @InjectMocks
    private ProxyMonthlySummaryService sut;

    @Test
    @DisplayName("対象月のレコードが0件の場合は0を返す")
    void noRecords() {
        given(recordRepository.findForMonthlySummary(any(), any())).willReturn(List.of());

        int result = sut.generateForMonth(YearMonth.of(2026, 4));

        assertThat(result).isZero();
    }

    @Test
    @DisplayName("レコードがある場合はPDFを生成してS3にアップロードする")
    void generatePdfAndUpload() {
        // GIVEN
        ProxyInputRecordEntity record = buildRecord(100L, "SURVEY");
        given(recordRepository.findForMonthlySummary(any(), any())).willReturn(List.of(record));
        given(pdfGeneratorService.generateFromTemplate(anyString(), anyMap())).willReturn(new byte[]{1, 2, 3});

        // WHEN
        int count = sut.generateForMonth(YearMonth.of(2026, 4));

        // THEN
        assertThat(count).isEqualTo(1);
        verify(storageService).upload(
                eq("proxy-monthly-summaries/100/2026/04/summary.pdf"),
                any(byte[].class),
                eq("application/pdf")
        );
    }

    @Test
    @DisplayName("異なる subjectUserId の場合はそれぞれ別のPDFを生成する")
    void multipleSubjectUsers() {
        // GIVEN
        ProxyInputRecordEntity record1 = buildRecord(100L, "SURVEY");
        ProxyInputRecordEntity record2 = buildRecord(200L, "MEETING");
        given(recordRepository.findForMonthlySummary(any(), any())).willReturn(List.of(record1, record2));
        given(pdfGeneratorService.generateFromTemplate(anyString(), anyMap())).willReturn(new byte[]{1});

        // WHEN
        int count = sut.generateForMonth(YearMonth.of(2026, 4));

        // THEN: 住民2人分のPDFが生成される
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("S3キーのフォーマットが正しい")
    void s3KeyFormat() {
        String key = sut.buildS3Key(100L, YearMonth.of(2026, 4));
        assertThat(key).isEqualTo("proxy-monthly-summaries/100/2026/04/summary.pdf");
    }

    @Test
    @DisplayName("S3キーの月が1桁の場合でも2桁でゼロ埋めされる")
    void s3KeyFormatSingleDigitMonth() {
        String key = sut.buildS3Key(1L, YearMonth.of(2026, 1));
        assertThat(key).isEqualTo("proxy-monthly-summaries/1/2026/01/summary.pdf");
    }

    /**
     * テスト用のProxyInputRecordEntityを構築する。
     * organizationId フィールドは存在しないため subjectUserId と featureScope のみ指定する。
     */
    private ProxyInputRecordEntity buildRecord(Long subjectUserId, String featureScope) {
        return ProxyInputRecordEntity.builder()
                .proxyInputConsentId(1L)
                .subjectUserId(subjectUserId)
                .proxyUserId(200L)
                .featureScope(featureScope)
                .targetEntityType("SURVEY_RESPONSE")
                .targetEntityId(999L)
                .inputSource(ProxyInputRecordEntity.InputSource.PAPER_FORM)
                .originalStorageLocation("書類棚A-1")
                .build();
    }
}
