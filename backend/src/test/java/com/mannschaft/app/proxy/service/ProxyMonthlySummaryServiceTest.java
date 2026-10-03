package com.mannschaft.app.proxy.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private ProxyInputConsentRepository consentRepository;

    @Mock
    private AccessControlService accessControlService;

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

    // ════════════════════════════════════════════════
    // getDownloadUrl の認可（認可根治 Wave7）
    // ════════════════════════════════════════════════

    @Test
    @DisplayName("認可: 本人は自分の月次サマリURLを取得できる")
    void getDownloadUrl_本人は取得できる() {
        given(storageService.generateDownloadUrl(anyString(), any(Duration.class))).willReturn("https://signed");

        String url = sut.getDownloadUrl(100L, 100L, YearMonth.of(2026, 4));

        assertThat(url).isEqualTo("https://signed");
    }

    @Test
    @DisplayName("認可: 無関係の他人は他人のsubjectUserIdを指定してもURLを取得できない（403）")
    void getDownloadUrl_無関係の他人は403() {
        given(accessControlService.isSystemAdmin(999L)).willReturn(false);
        given(consentRepository.findActiveBySubjectUserId(100L)).willReturn(List.of());

        assertThatThrownBy(() -> sut.getDownloadUrl(999L, 100L, YearMonth.of(2026, 4)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.COMMON_002));
    }

    @Test
    @DisplayName("認可: 対象住民の同意書が属する組合のADMINは取得できる")
    void getDownloadUrl_同意書組合のADMINは取得できる() {
        given(accessControlService.isSystemAdmin(999L)).willReturn(false);
        given(consentRepository.findActiveBySubjectUserId(100L))
                .willReturn(List.of(buildConsent(100L, 7L)));
        given(accessControlService.isAdminOrAbove(999L, 7L, "ORGANIZATION")).willReturn(true);
        given(storageService.generateDownloadUrl(anyString(), any(Duration.class))).willReturn("https://signed");

        assertThat(sut.getDownloadUrl(999L, 100L, YearMonth.of(2026, 4))).isEqualTo("https://signed");
    }

    @Test
    @DisplayName("認可: 別組合のADMINは取得できない（403・BOLA）")
    void getDownloadUrl_別組合のADMINは403() {
        given(accessControlService.isSystemAdmin(999L)).willReturn(false);
        given(consentRepository.findActiveBySubjectUserId(100L))
                .willReturn(List.of(buildConsent(100L, 7L)));
        given(accessControlService.isAdminOrAbove(999L, 7L, "ORGANIZATION")).willReturn(false);

        assertThatThrownBy(() -> sut.getDownloadUrl(999L, 100L, YearMonth.of(2026, 4)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.COMMON_002));
    }

    @Test
    @DisplayName("認可: SYSTEM_ADMIN は取得できる")
    void getDownloadUrl_SYSTEM_ADMINは取得できる() {
        given(accessControlService.isSystemAdmin(999L)).willReturn(true);
        given(storageService.generateDownloadUrl(anyString(), any(Duration.class))).willReturn("https://signed");

        assertThat(sut.getDownloadUrl(999L, 100L, YearMonth.of(2026, 4))).isEqualTo("https://signed");
    }

    /** テスト用の同意書 entity を構築する（認可判定に使う organizationId のみ意味を持つ）。 */
    private ProxyInputConsentEntity buildConsent(Long subjectUserId, Long organizationId) {
        return ProxyInputConsentEntity.create(
                subjectUserId, 500L, organizationId,
                ProxyInputConsentEntity.ConsentMethod.PAPER_SIGNED,
                "proxy-consents/dummy.pdf", null, null,
                java.time.LocalDate.now().minusDays(1), java.time.LocalDate.now().plusDays(30));
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
