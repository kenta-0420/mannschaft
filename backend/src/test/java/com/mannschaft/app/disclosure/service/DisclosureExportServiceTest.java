package com.mannschaft.app.disclosure.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.excel.ExcelGeneratorService;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.DisclosureOutputFormat;
import com.mannschaft.app.disclosure.DraftStatus;
import com.mannschaft.app.disclosure.dto.DisclosureExportResponse;
import com.mannschaft.app.disclosure.entity.DisclosureExportEntity;
import com.mannschaft.app.disclosure.entity.DisclosureFormDraftEntity;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.mannschaft.app.disclosure.repository.DisclosureExportRepository;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFileVersionRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.property.repository.PropertyWorkPackageRepository;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DisclosureExportService} 単体テスト（F09.14 Phase 2-β-4）。
 *
 * <p>SHA-256 計算 / SharedFile 連携 / 改ざん検出 / バージョン整合性 / 必須項目チェックを網羅する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DisclosureExportService 単体テスト")
class DisclosureExportServiceTest {

    @Mock private DisclosureExportRepository exportRepository;
    @Mock private DisclosureFormDraftService draftService;
    @Mock private DisclosureFormTemplateService templateService;
    @Mock private DisclosureFormTemplateValidator templateValidator;
    @Mock private PdfGeneratorService pdfGeneratorService;
    @Mock private ExcelGeneratorService excelGeneratorService;
    @Mock private R2StorageService r2StorageService;
    @Mock private SharedFolderRepository folderRepository;
    @Mock private SharedFileRepository sharedFileRepository;
    @Mock private SharedFileVersionRepository sharedFileVersionRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private DwellingUnitRepository dwellingUnitRepository;
    @Mock private PropertyWorkPackageRepository propertyWorkPackageRepository;
    @Mock private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DisclosureExportService service;

    @BeforeEach
    void setUp() {
        service = new DisclosureExportService(
                exportRepository, draftService, templateService, templateValidator,
                pdfGeneratorService, excelGeneratorService, r2StorageService,
                folderRepository, sharedFileRepository, sharedFileVersionRepository,
                organizationRepository, dwellingUnitRepository,
                propertyWorkPackageRepository, userRepository, objectMapper);
    }

    @Test
    @DisplayName("exportDraft(PDF): SHA-256 + SharedFile 登録 + ドラフト EXPORTED 化が正しく行われる")
    void exportDraft_pdfSuccess() throws Exception {
        // given
        DisclosureFormTemplateEntity tpl = template("MLIT_STANDARD_2024", "2024.1",
                "{\"sections\":[{\"id\":\"s1\",\"title\":\"概要\",\"fields\":[]}]}");
        DisclosureFormDraftEntity draft = draft(100L, 1L, "2024.1", "{}");
        when(draftService.findDraftOrThrow(10L)).thenReturn(draft);
        when(templateService.getEntityOrThrow(1L)).thenReturn(tpl);
        when(organizationRepository.findById(100L))
                .thenReturn(Optional.of(organization(100L, "サンプルマンション")));

        byte[] pdf = "PDFCONTENT".getBytes();
        when(pdfGeneratorService.generateFromTemplate(anyString(), any())).thenReturn(pdf);

        SharedFolderEntity folder = SharedFolderEntity.builder()
                .scopeType(com.mannschaft.app.filesharing.FileScopeType.ORGANIZATION)
                .organizationId(100L).name("disclosure-exports").build();
        setBaseEntityId(folder, 50L);
        when(folderRepository.findByOrganizationIdAndParentIdIsNullOrderByNameAsc(100L))
                .thenReturn(List.of(folder));

        when(sharedFileRepository.save(any())).thenAnswer(inv -> {
            SharedFileEntity e = inv.getArgument(0);
            setBaseEntityId(e, 999L);
            return e;
        });
        when(exportRepository.save(any())).thenAnswer(inv -> {
            DisclosureExportEntity e = inv.getArgument(0);
            setEntityIdViaReflection(e, 7L);
            return e;
        });
        when(r2StorageService.generateDownloadUrl(anyString(), any())).thenReturn("https://r2/presigned");

        // when
        DisclosureExportResponse res = service.exportDraft(
                100L, 10L, DisclosureOutputFormat.PDF, 200L, "提出先メモ", false);

        // then
        assertThat(res.exportId()).isEqualTo(7L);
        assertThat(res.outputFormat()).isEqualTo(DisclosureOutputFormat.PDF);
        assertThat(res.sha256()).hasSize(64);
        assertThat(res.downloadUrl()).isEqualTo("https://r2/presigned");

        // R2 にバイト列がアップロードされたこと
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(r2StorageService).upload(keyCaptor.capture(), eq(pdf), eq("application/pdf"));
        assertThat(keyCaptor.getValue()).startsWith("files/ORGANIZATION/100/").endsWith(".pdf");

        // ドラフトが EXPORTED 化されたこと
        verify(draftService).markExported(eq(draft), eq(200L));
    }

    @Test
    @DisplayName("exportDraft(): バージョン不一致は DISCLOSURE_006")
    void exportDraft_templateVersionMismatch() {
        DisclosureFormTemplateEntity tpl = template("MLIT", "2025.1",
                "{\"sections\":[]}"); // 最新は 2025.1
        DisclosureFormDraftEntity draft = draft(100L, 1L, "2024.1", "{}"); // 古い
        when(draftService.findDraftOrThrow(10L)).thenReturn(draft);
        when(templateService.getEntityOrThrow(1L)).thenReturn(tpl);

        assertThatThrownBy(() -> service.exportDraft(
                100L, 10L, DisclosureOutputFormat.PDF, 200L, null, false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_006);
    }

    @Test
    @DisplayName("exportDraft(): 必須項目未入力は DISCLOSURE_007")
    void exportDraft_requiredFieldMissing() {
        DisclosureFormTemplateEntity tpl = template("MLIT", "v1",
                "{\"sections\":[{\"id\":\"s1\",\"title\":\"基本\",\"fields\":["
                + "{\"id\":\"orgName\",\"label\":\"物件名\",\"type\":\"TEXT\",\"required\":true}"
                + "]}]}");
        DisclosureFormDraftEntity draft = draft(100L, 1L, "v1", "{}");
        when(draftService.findDraftOrThrow(10L)).thenReturn(draft);
        when(templateService.getEntityOrThrow(1L)).thenReturn(tpl);

        assertThatThrownBy(() -> service.exportDraft(
                100L, 10L, DisclosureOutputFormat.PDF, 200L, null, false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_007);
    }

    @Test
    @DisplayName("exportDraft(): スコープ不一致は DISCLOSURE_002")
    void exportDraft_scopeMismatch() {
        DisclosureFormDraftEntity draft = draft(999L, 1L, "v1", "{}"); // 別組織
        when(draftService.findDraftOrThrow(10L)).thenReturn(draft);

        assertThatThrownBy(() -> service.exportDraft(
                100L, 10L, DisclosureOutputFormat.PDF, 200L, null, false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_002);
    }

    @Test
    @DisplayName("exportDraft(): WORD は Phase 3 のため DISCLOSURE_004")
    void exportDraft_wordRejected() {
        assertThatThrownBy(() -> service.exportDraft(
                100L, 10L, DisclosureOutputFormat.WORD, 200L, null, false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_004);
    }

    @Test
    @DisplayName("generateDownloadUrl(): SHA-256 不一致は DISCLOSURE_010（改ざん検出）")
    void generateDownloadUrl_sha256Mismatch() throws Exception {
        DisclosureExportEntity e = DisclosureExportEntity.builder()
                .scopeType("ORGANIZATION").scopeId(100L)
                .templateId(1L).templateCodeSnapshot("MLIT").templateVersionSnapshot("v1")
                .outputFormat(DisclosureOutputFormat.PDF)
                .sharedFileId(999L)
                .requesterUserId(200L)
                .dataSnapshot("{}")
                .outputSha256("0".repeat(64))
                .build();
        setEntityIdViaReflection(e, 7L);
        when(exportRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(e));

        SharedFileEntity sf = SharedFileEntity.builder()
                .folderId(50L).name("f.pdf").fileKey("files/ORGANIZATION/100/abc.pdf")
                .fileSize(10L).contentType("application/pdf").build();
        when(sharedFileRepository.findById(999L)).thenReturn(Optional.of(sf));
        when(r2StorageService.download("files/ORGANIZATION/100/abc.pdf"))
                .thenReturn("DIFFERENT_CONTENT".getBytes());

        assertThatThrownBy(() -> service.generateDownloadUrl(100L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_010);
    }

    // ===========================================================================
    // F09.14 Phase 3-E: extendExpiry
    // ===========================================================================

    @Test
    @DisplayName("extendExpiry(): 未来日時で正常に更新される")
    void extendExpiry_success() throws Exception {
        DisclosureExportEntity e = DisclosureExportEntity.builder()
                .scopeType("ORGANIZATION").scopeId(100L)
                .templateId(1L).templateCodeSnapshot("MLIT").templateVersionSnapshot("v1")
                .outputFormat(DisclosureOutputFormat.PDF)
                .sharedFileId(999L)
                .requesterUserId(200L)
                .dataSnapshot("{}")
                .expiresAt(java.time.LocalDateTime.now().plusDays(30))
                .build();
        setEntityIdViaReflection(e, 7L);
        when(exportRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(e));
        when(exportRepository.save(any(DisclosureExportEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        java.time.LocalDateTime newExpiresAt = java.time.LocalDateTime.now().plusYears(1);
        DisclosureExportResponse res = service.extendExpiry(100L, 7L, newExpiresAt);

        assertThat(res.expiresAt()).isEqualTo(newExpiresAt);
        verify(exportRepository).save(e);
        assertThat(e.getExpiresAt()).isEqualTo(newExpiresAt);
    }

    @Test
    @DisplayName("extendExpiry(): 過去日時は DISCLOSURE_011")
    void extendExpiry_past() {
        java.time.LocalDateTime past = java.time.LocalDateTime.now().minusDays(1);
        assertThatThrownBy(() -> service.extendExpiry(100L, 7L, past))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_011);
    }

    @Test
    @DisplayName("extendExpiry(): 7年超は DISCLOSURE_011")
    void extendExpiry_over7Years() {
        java.time.LocalDateTime tooFar = java.time.LocalDateTime.now().plusYears(7).plusDays(2);
        assertThatThrownBy(() -> service.extendExpiry(100L, 7L, tooFar))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_011);
    }

    @Test
    @DisplayName("extendExpiry(): null は DISCLOSURE_011")
    void extendExpiry_null() {
        assertThatThrownBy(() -> service.extendExpiry(100L, 7L, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_011);
    }

    @Test
    @DisplayName("extendExpiry(): スコープ不一致は DISCLOSURE_002")
    void extendExpiry_scopeMismatch() throws Exception {
        DisclosureExportEntity e = DisclosureExportEntity.builder()
                .scopeType("ORGANIZATION").scopeId(999L)  // 別組織
                .templateId(1L).templateCodeSnapshot("MLIT").templateVersionSnapshot("v1")
                .outputFormat(DisclosureOutputFormat.PDF)
                .sharedFileId(999L).requesterUserId(200L).dataSnapshot("{}")
                .build();
        setEntityIdViaReflection(e, 7L);
        when(exportRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(e));

        java.time.LocalDateTime newExpiresAt = java.time.LocalDateTime.now().plusYears(1);
        assertThatThrownBy(() -> service.extendExpiry(100L, 7L, newExpiresAt))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_002);
    }

    @Test
    @DisplayName("generateDownloadUrl(): SHA-256 一致時は presigned URL を返す")
    void generateDownloadUrl_sha256Match() throws Exception {
        byte[] data = "PDFCONTENT".getBytes();
        // sha256 を実際に計算
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        String expectedSha = sb.toString();

        DisclosureExportEntity e = DisclosureExportEntity.builder()
                .scopeType("ORGANIZATION").scopeId(100L)
                .templateId(1L).templateCodeSnapshot("MLIT").templateVersionSnapshot("v1")
                .outputFormat(DisclosureOutputFormat.PDF)
                .sharedFileId(999L)
                .requesterUserId(200L)
                .dataSnapshot("{}")
                .outputSha256(expectedSha)
                .build();
        setEntityIdViaReflection(e, 7L);
        when(exportRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(e));

        SharedFileEntity sf = SharedFileEntity.builder()
                .folderId(50L).name("f.pdf").fileKey("files/ORGANIZATION/100/x.pdf")
                .fileSize(10L).contentType("application/pdf").build();
        when(sharedFileRepository.findById(999L)).thenReturn(Optional.of(sf));
        when(r2StorageService.download("files/ORGANIZATION/100/x.pdf")).thenReturn(data);
        when(r2StorageService.generateDownloadUrl(anyString(), any())).thenReturn("https://r2/url");

        DisclosureExportResponse res = service.generateDownloadUrl(100L, 7L);
        assertThat(res.downloadUrl()).isEqualTo("https://r2/url");
        assertThat(res.sha256()).isEqualTo(expectedSha);
    }

    // ----- ヘルパー -----

    private DisclosureFormTemplateEntity template(String code, String version, String formSchema) {
        DisclosureFormTemplateEntity e = DisclosureFormTemplateEntity.builder()
                .code(code).name("テスト様式").version(version)
                .isSystemTemplate(true).isStandard(true)
                .formSchema(formSchema).isActive(true).build();
        try {
            setBaseEntityId(e, 1L);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    private DisclosureFormDraftEntity draft(Long scopeId, Long templateId,
                                            String snapshot, String formData) {
        DisclosureFormDraftEntity e = DisclosureFormDraftEntity.builder()
                .scopeType("ORGANIZATION").scopeId(scopeId)
                .templateId(templateId).templateVersionSnapshot(snapshot)
                .title("テストドラフト").formData(formData)
                .status(DraftStatus.DRAFT).createdBy(1L)
                .build();
        try {
            setBaseEntityId(e, 10L);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    private OrganizationEntity organization(Long id, String name) {
        OrganizationEntity org = OrganizationEntity.builder()
                .name(name).build();
        try {
            setBaseEntityId(org, id);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return org;
    }

    /** BaseEntity の private id を Reflection でセット（テスト専用）。 */
    private static void setBaseEntityId(Object entity, Long id) throws Exception {
        Field f = com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, id);
    }

    /** DisclosureExportEntity の id（独立、BaseEntity 非継承）を Reflection でセット。 */
    private static void setEntityIdViaReflection(DisclosureExportEntity entity, Long id)
            throws Exception {
        Field f = DisclosureExportEntity.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, id);
    }
}
