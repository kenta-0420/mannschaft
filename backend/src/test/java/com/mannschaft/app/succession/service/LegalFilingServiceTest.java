package com.mannschaft.app.succession.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.succession.SuccessionErrorCode;
import com.mannschaft.app.succession.entity.DelinquencyEscalationEntity;
import com.mannschaft.app.succession.entity.LegalFilingEntity;
import com.mannschaft.app.succession.repository.DelinquencyEscalationRepository;
import com.mannschaft.app.succession.repository.LegalFilingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LegalFilingService} のユニットテスト（F09.15 S6-C）。
 *
 * <p>外部依存（Repository / PdfGeneratorService / StorageService / AuditLogService）は
 * すべて Mockito スタブ化する。S3 操作と PDF 生成は mock 化し、振る舞いのみ検証する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LegalFilingService")
class LegalFilingServiceTest {

    @Mock
    private LegalFilingRepository legalFilingRepository;

    @Mock
    private DelinquencyEscalationRepository escalationRepository;

    @Mock
    private PdfGeneratorService pdfGeneratorService;

    @Mock
    private StorageService storageService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private LegalFilingService service;

    private static final Long ORG_ID = 100L;
    private static final Long DWELLING_ID = 200L;
    private static final Long RESIDENT_REGISTRY_ID = 300L;
    private static final Long REQUESTING_USER_ID = 400L;

    private static final byte[] DUMMY_PDF = new byte[]{0x25, 0x50, 0x44, 0x46};
    private static final byte[] DUMMY_TIMELINE_PDF = new byte[]{0x25, 0x50, 0x44, 0x46, 0x32};
    private static final byte[] DUMMY_COVER_PDF = new byte[]{0x25, 0x50, 0x44, 0x46, 0x33};
    private static final String DUMMY_SHA256 = "abc123def456abc123def456abc123def456abc123def456abc123def4567890";
    private static final String DUMMY_DOWNLOAD_URL = "https://test.s3/evidence.zip?signed=1";

    // ─── createLegalFiling ──────────────────────────────────────────────

    @Nested
    @DisplayName("createLegalFiling")
    class CreateLegalFiling {

        @Test
        @DisplayName("正常系: filingType=ABSENTEE_PROPERTY_MANAGER で成功し申立書 PDF を S3 アップロードする")
        void createLegalFiling_ABSENTEE_PROPERTY_MANAGER_成功() {
            // エスカレーション無し
            when(escalationRepository.findByResidentRegistryIdAndDeletedAtIsNull(RESIDENT_REGISTRY_ID))
                    .thenReturn(Optional.empty());
            when(pdfGeneratorService.generateFromTemplate(eq("pdf/legal-filing-absentee-property-manager"), any()))
                    .thenReturn(DUMMY_PDF);
            when(legalFilingRepository.save(any(LegalFilingEntity.class)))
                    .thenAnswer(inv -> {
                        LegalFilingEntity e = inv.getArgument(0);
                        if (e.getId() == null) {
                            setId(e, UUID.randomUUID());
                        }
                        return e;
                    });

            LegalFilingEntity result = service.createLegalFiling(
                    ORG_ID, RESIDENT_REGISTRY_ID, DWELLING_ID,
                    "ABSENTEE_PROPERTY_MANAGER", "備考テスト", REQUESTING_USER_ID);

            assertThat(result).isNotNull();
            assertThat(result.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(result.getResidentRegistryId()).isEqualTo(RESIDENT_REGISTRY_ID);
            assertThat(result.getDwellingUnitId()).isEqualTo(DWELLING_ID);
            assertThat(result.getFilingType()).isEqualTo("ABSENTEE_PROPERTY_MANAGER");
            assertThat(result.getNote()).isEqualTo("備考テスト");
            assertThat(result.getTemplatePdfS3Key()).contains("organizations/100/succession/legal-filings/");
            assertThat(result.getTemplatePdfS3Key()).endsWith("/template.pdf");

            // S3 アップロードが呼ばれていること
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
            verify(storageService).upload(keyCaptor.capture(), dataCaptor.capture(), eq("application/pdf"));
            assertThat(keyCaptor.getValue()).endsWith("/template.pdf");
            assertThat(dataCaptor.getValue()).isEqualTo(DUMMY_PDF);

            // 監査ログが呼ばれていること
            verify(auditLogService).record(
                    eq(AuditEventType.LEGAL_FILING_CREATED.name()),
                    eq(REQUESTING_USER_ID), any(), any(), eq(ORG_ID),
                    any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("正常系: filingType=INHERITANCE_LIQUIDATOR で成功する")
        void createLegalFiling_INHERITANCE_LIQUIDATOR_成功() {
            when(escalationRepository.findByResidentRegistryIdAndDeletedAtIsNull(RESIDENT_REGISTRY_ID))
                    .thenReturn(Optional.empty());
            when(pdfGeneratorService.generateFromTemplate(eq("pdf/legal-filing-inheritance-liquidator"), any()))
                    .thenReturn(DUMMY_PDF);
            when(legalFilingRepository.save(any(LegalFilingEntity.class)))
                    .thenAnswer(inv -> {
                        LegalFilingEntity e = inv.getArgument(0);
                        if (e.getId() == null) {
                            setId(e, UUID.randomUUID());
                        }
                        return e;
                    });

            LegalFilingEntity result = service.createLegalFiling(
                    ORG_ID, RESIDENT_REGISTRY_ID, DWELLING_ID,
                    "INHERITANCE_LIQUIDATOR", null, REQUESTING_USER_ID);

            assertThat(result.getFilingType()).isEqualTo("INHERITANCE_LIQUIDATOR");
            // 相続財産清算人テンプレートが呼ばれていること
            verify(pdfGeneratorService).generateFromTemplate(eq("pdf/legal-filing-inheritance-liquidator"), any());
        }

        @Test
        @DisplayName("異常系: 不正な filingType は INVALID_COVENANT_TYPE をスローする")
        void createLegalFiling_不正な_filingType_例外() {
            assertThatThrownBy(() -> service.createLegalFiling(
                    ORG_ID, RESIDENT_REGISTRY_ID, DWELLING_ID,
                    "INVALID_TYPE", null, REQUESTING_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.INVALID_COVENANT_TYPE);

            // PDF も S3 もリポも呼ばれないこと
            verify(pdfGeneratorService, never()).generateFromTemplate(anyString(), any());
            verify(storageService, never()).upload(anyString(), any(byte[].class), anyString());
            verify(legalFilingRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: エスカレーション存在時にエスカレーション情報を PDF テンプレ vars に注入する")
        void createLegalFiling_エスカレーション存在_テンプレ変数注入() {
            DelinquencyEscalationEntity escalation = DelinquencyEscalationEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(DWELLING_ID)
                    .residentRegistryId(RESIDENT_REGISTRY_ID)
                    .delinquencyStartedAt(LocalDate.of(2026, 1, 1))
                    .currentStage("STAGE_5_LEGAL_PREP")
                    .build();
            setId(escalation, UUID.randomUUID());

            when(escalationRepository.findByResidentRegistryIdAndDeletedAtIsNull(RESIDENT_REGISTRY_ID))
                    .thenReturn(Optional.of(escalation));
            when(pdfGeneratorService.generateFromTemplate(anyString(), any())).thenReturn(DUMMY_PDF);
            when(legalFilingRepository.save(any(LegalFilingEntity.class)))
                    .thenAnswer(inv -> {
                        LegalFilingEntity e = inv.getArgument(0);
                        if (e.getId() == null) {
                            setId(e, UUID.randomUUID());
                        }
                        return e;
                    });

            service.createLegalFiling(
                    ORG_ID, RESIDENT_REGISTRY_ID, DWELLING_ID,
                    "ABSENTEE_PROPERTY_MANAGER", null, REQUESTING_USER_ID);

            // テンプレ vars に escalation 情報が含まれていることを確認
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, Object>> varsCaptor =
                    ArgumentCaptor.forClass(java.util.Map.class);
            verify(pdfGeneratorService).generateFromTemplate(
                    eq("pdf/legal-filing-absentee-property-manager"), varsCaptor.capture());
            java.util.Map<String, Object> vars = varsCaptor.getValue();
            assertThat(vars).containsEntry("currentStage", "STAGE_5_LEGAL_PREP");
            assertThat(vars).containsEntry("delinquencyStartedAt", LocalDate.of(2026, 1, 1));
            assertThat(vars).containsEntry("residentRegistryId", RESIDENT_REGISTRY_ID);
        }

        @Test
        @DisplayName("正常系: エスカレーション不在でも null 許容で動作する")
        void createLegalFiling_エスカレーション不在_null許容() {
            when(escalationRepository.findByResidentRegistryIdAndDeletedAtIsNull(RESIDENT_REGISTRY_ID))
                    .thenReturn(Optional.empty());
            when(pdfGeneratorService.generateFromTemplate(anyString(), any())).thenReturn(DUMMY_PDF);
            when(legalFilingRepository.save(any(LegalFilingEntity.class)))
                    .thenAnswer(inv -> {
                        LegalFilingEntity e = inv.getArgument(0);
                        if (e.getId() == null) {
                            setId(e, UUID.randomUUID());
                        }
                        return e;
                    });

            LegalFilingEntity result = service.createLegalFiling(
                    ORG_ID, RESIDENT_REGISTRY_ID, DWELLING_ID,
                    "ABSENTEE_PROPERTY_MANAGER", null, REQUESTING_USER_ID);

            assertThat(result).isNotNull();
            // エスカ vars が null でも例外なく処理されること
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, Object>> varsCaptor =
                    ArgumentCaptor.forClass(java.util.Map.class);
            verify(pdfGeneratorService).generateFromTemplate(anyString(), varsCaptor.capture());
            java.util.Map<String, Object> vars = varsCaptor.getValue();
            assertThat(vars).doesNotContainKeys("currentStage", "delinquencyStartedAt");
        }
    }

    // ─── buildEvidencePackage ──────────────────────────────────────────

    @Nested
    @DisplayName("buildEvidencePackage")
    class BuildEvidencePackage {

        @Test
        @DisplayName("正常系: 3 つの PDF を組み立てて ZIP 生成・SHA-256 を保存する")
        void buildEvidencePackage_ZIP生成成功() {
            UUID legalFilingId = UUID.randomUUID();
            LegalFilingEntity entity = LegalFilingEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(DWELLING_ID)
                    .residentRegistryId(RESIDENT_REGISTRY_ID)
                    .filingType("ABSENTEE_PROPERTY_MANAGER")
                    .templatePdfS3Key("organizations/100/succession/legal-filings/" + legalFilingId + "/template.pdf")
                    .build();
            setId(entity, legalFilingId);

            when(legalFilingRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(legalFilingId, ORG_ID))
                    .thenReturn(Optional.of(entity));
            when(storageService.download(entity.getTemplatePdfS3Key())).thenReturn(DUMMY_PDF);
            when(escalationRepository.findByResidentRegistryIdAndDeletedAtIsNull(RESIDENT_REGISTRY_ID))
                    .thenReturn(Optional.empty());
            when(pdfGeneratorService.generateFromTemplate(
                    eq("pdf/legal-filing-evidence-timeline"), any())).thenReturn(DUMMY_TIMELINE_PDF);
            when(pdfGeneratorService.generateFromTemplate(
                    eq("pdf/legal-filing-art8-evidence-cover"), any())).thenReturn(DUMMY_COVER_PDF);
            when(pdfGeneratorService.sha256Hex(any(byte[].class))).thenReturn(DUMMY_SHA256);
            when(legalFilingRepository.save(any(LegalFilingEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            LegalFilingEntity result = service.buildEvidencePackage(legalFilingId, ORG_ID, REQUESTING_USER_ID);

            assertThat(result.getEvidencePackageS3Key())
                    .contains("organizations/100/succession/legal-filings/")
                    .endsWith("/evidence-package.zip");
            assertThat(result.getEvidenceSha256()).isEqualTo(DUMMY_SHA256);
            assertThat(result.getEvidenceBuiltAt()).isNotNull();

            // ZIP の S3 アップロードを検証
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
            verify(storageService).upload(keyCaptor.capture(), dataCaptor.capture(), eq("application/zip"));
            assertThat(keyCaptor.getValue()).endsWith("/evidence-package.zip");
            // ZIP バイト列に PK ヘッダが含まれることだけ簡易検証（最初の 2 バイト = 0x50, 0x4B）
            byte[] zipBytes = dataCaptor.getValue();
            assertThat(zipBytes).isNotEmpty();
            assertThat(zipBytes[0]).isEqualTo((byte) 0x50);
            assertThat(zipBytes[1]).isEqualTo((byte) 0x4B);

            verify(auditLogService).record(
                    eq(AuditEventType.EVIDENCE_PACKAGE_BUILT.name()),
                    eq(REQUESTING_USER_ID), any(), any(), eq(ORG_ID),
                    any(), any(), any(), anyString());
        }
    }

    // ─── generateEvidenceDownloadUrl ───────────────────────────────────

    @Nested
    @DisplayName("generateEvidenceDownloadUrl")
    class GenerateEvidenceDownloadUrl {

        @Test
        @DisplayName("異常系: evidence_package_未生成は EVIDENCE_NOT_READY をスローする")
        void generateEvidenceDownloadUrl_未生成_例外() {
            UUID legalFilingId = UUID.randomUUID();
            LegalFilingEntity entity = LegalFilingEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(DWELLING_ID)
                    .residentRegistryId(RESIDENT_REGISTRY_ID)
                    .filingType("ABSENTEE_PROPERTY_MANAGER")
                    .templatePdfS3Key("organizations/100/succession/legal-filings/" + legalFilingId + "/template.pdf")
                    // evidencePackageS3Key は null
                    .build();
            setId(entity, legalFilingId);

            when(legalFilingRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(legalFilingId, ORG_ID))
                    .thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.generateEvidenceDownloadUrl(legalFilingId, ORG_ID, REQUESTING_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.EVIDENCE_NOT_READY);

            verify(storageService, never()).generateDownloadUrl(anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("正常系: 生成済みなら Pre-signed URL を返し監査ログ記録する")
        void generateEvidenceDownloadUrl_生成済み_URL返却() {
            UUID legalFilingId = UUID.randomUUID();
            String zipKey = "organizations/100/succession/legal-filings/" + legalFilingId + "/evidence-package.zip";
            LegalFilingEntity entity = LegalFilingEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(DWELLING_ID)
                    .residentRegistryId(RESIDENT_REGISTRY_ID)
                    .filingType("ABSENTEE_PROPERTY_MANAGER")
                    .templatePdfS3Key("organizations/100/succession/legal-filings/" + legalFilingId + "/template.pdf")
                    .evidencePackageS3Key(zipKey)
                    .evidenceSha256(DUMMY_SHA256)
                    .evidenceBuiltAt(LocalDateTime.now().minusMinutes(5))
                    .build();
            setId(entity, legalFilingId);

            when(legalFilingRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(legalFilingId, ORG_ID))
                    .thenReturn(Optional.of(entity));
            when(storageService.generateDownloadUrl(eq(zipKey), any(Duration.class)))
                    .thenReturn(DUMMY_DOWNLOAD_URL);

            String url = service.generateEvidenceDownloadUrl(legalFilingId, ORG_ID, REQUESTING_USER_ID);

            assertThat(url).isEqualTo(DUMMY_DOWNLOAD_URL);

            // 有効期間 1h で URL 発行されたか
            ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
            verify(storageService).generateDownloadUrl(eq(zipKey), ttlCaptor.capture());
            assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(1));

            verify(auditLogService).record(
                    eq(AuditEventType.EVIDENCE_PACKAGE_DOWNLOADED.name()),
                    eq(REQUESTING_USER_ID), any(), any(), eq(ORG_ID),
                    any(), any(), any(), anyString());
        }
    }

    // ─── getById ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getById")
    class GetById {

        @Test
        @DisplayName("異常系: 存在しない id は LEGAL_FILING_NOT_FOUND をスローする")
        void getById_存在しない_例外() {
            UUID legalFilingId = UUID.randomUUID();
            when(legalFilingRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(legalFilingId, ORG_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(legalFilingId, ORG_ID, REQUESTING_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.LEGAL_FILING_NOT_FOUND);
        }
    }

    // ─── ヘルパー ──────────────────────────────────────────────────────

    private static void setId(Object target, UUID id) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField("id");
                f.setAccessible(true);
                f.set(target, id);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("id フィールドが見つかりません");
    }
}
