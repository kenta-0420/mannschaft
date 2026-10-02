package com.mannschaft.app.succession.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.pdf.SignedPdfResult;
import com.mannschaft.app.common.pdf.SuccessionCovenantContext;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.entity.ResidentRegistryEntity;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import com.mannschaft.app.resident.repository.ResidentRegistryRepository;
import com.mannschaft.app.succession.SuccessionErrorCode;
import com.mannschaft.app.succession.dto.SignCovenantRequest;
import com.mannschaft.app.succession.dto.SuccessionCovenantResponse;
import com.mannschaft.app.succession.entity.SuccessionCovenantEntity;
import com.mannschaft.app.succession.repository.SuccessionCovenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SuccessionCovenantService} のユニットテスト（F09.15 S1 第三陣B）。
 *
 * <p>外部依存（PDF/S3/AuditLog/AccessControl/Repository）はすべて Mockito スタブ化する。
 * テナント分離は {@code AbstractTenantAwareRepository#findByIdAndOrganizationIdAndDeletedAtIsNull}
 * の呼び出しを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SuccessionCovenantService")
class SuccessionCovenantServiceTest {

    @Mock
    private SuccessionCovenantRepository covenantRepository;
    @Mock
    private ResidentRegistryRepository residentRegistryRepository;
    @Mock
    private DwellingUnitRepository dwellingUnitRepository;
    @Mock
    private PdfGeneratorService pdfGeneratorService;
    @Mock
    private StorageService storageService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private SuccessionCovenantService service;

    private static final Long ORG_ID = 100L;
    private static final Long DWELLING_ID = 200L;
    private static final Long RESIDENT_ID = 300L;
    private static final Long SIGNER_USER_ID = 400L;
    private static final Long OTHER_USER_ID = 999L;

    private ResidentRegistryEntity resident;
    private DwellingUnitEntity unit;

    @BeforeEach
    void setUp() {
        resident = ResidentRegistryEntity.builder()
                .dwellingUnitId(DWELLING_ID)
                .userId(SIGNER_USER_ID)
                .residentType("OWNER")
                .lastName("山田")
                .firstName("太郎")
                .moveInDate(LocalDate.of(2026, 1, 1))
                .build();
        setField(resident, "id", RESIDENT_ID);

        unit = DwellingUnitEntity.builder()
                .scopeType("ORGANIZATION")
                .organizationId(ORG_ID)
                .unitNumber("301")
                .unitType("STANDARD")
                .build();
        setField(unit, "id", DWELLING_ID);
    }

    // ─── 署名（発行+署名一括）─────────────────────────────────

    @Nested
    @DisplayName("signCovenant")
    class SignCovenant {

        @Test
        @DisplayName("正常系: PDF 生成・S3 アップロード・INSERT・監査ログが順に実行される")
        void signCovenant_success() {
            SignCovenantRequest req = SignCovenantRequest.builder()
                    .covenantType("PRIVACY_CONSENT")
                    .residentRegistryId(RESIDENT_ID)
                    .covenantVersion("v1.0.0")
                    .confirmedItems(List.of(
                            "agree_personal_data_collection",
                            "agree_data_retention_10y"))
                    .build();

            when(residentRegistryRepository.findById(RESIDENT_ID)).thenReturn(Optional.of(resident));
            when(dwellingUnitRepository.findById(DWELLING_ID)).thenReturn(Optional.of(unit));
            when(covenantRepository.findByResidentRegistryIdAndCovenantTypeAndRevokedAtIsNullAndDeletedAtIsNull(
                    RESIDENT_ID, "PRIVACY_CONSENT"))
                    .thenReturn(List.of());

            byte[] pdfBytes = "dummy-pdf-bytes".getBytes();
            SignedPdfResult signedPdf = new SignedPdfResult(
                    pdfBytes, "abc123", "token.123", Instant.now(), "subject-id");
            when(pdfGeneratorService.generateSignedCovenantPdf(any(SuccessionCovenantContext.class)))
                    .thenReturn(signedPdf);

            // covenantRepository.save は呼ばれた entity に id を付与して返す（UUIDv7 採番のエミュレート）
            UUID generatedId = UUID.randomUUID();
            when(covenantRepository.save(any(SuccessionCovenantEntity.class)))
                    .thenAnswer(inv -> {
                        SuccessionCovenantEntity e = inv.getArgument(0);
                        if (e.getId() == null) {
                            setField(e, "id", generatedId);
                        }
                        return e;
                    });

            SuccessionCovenantResponse resp = service.signCovenant(req, SIGNER_USER_ID);

            assertThat(resp.getId()).isEqualTo(generatedId);
            assertThat(resp.getCovenantType()).isEqualTo("PRIVACY_CONSENT");
            assertThat(resp.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(resp.getSignerUserId()).isEqualTo(SIGNER_USER_ID);
            assertThat(resp.getPdfSha256()).isEqualTo("abc123");
            assertThat(resp.getInternalSignatureToken()).isEqualTo("token.123");

            // S3 アップロードが呼ばれている
            ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
            verify(storageService).upload(keyCap.capture(), eq(pdfBytes), eq("application/pdf"));
            assertThat(keyCap.getValue()).startsWith("organizations/" + ORG_ID + "/succession/covenants/");
            assertThat(keyCap.getValue()).endsWith("_signed.pdf");

            // 監査ログ COVENANT_ISSUED + COVENANT_SIGNED の 2 回記録
            verify(auditLogService).record(
                    eq("COVENANT_ISSUED"), eq(SIGNER_USER_ID),
                    any(), any(), eq(ORG_ID), any(), any(), any(), anyString());
            verify(auditLogService).record(
                    eq("COVENANT_SIGNED"), eq(SIGNER_USER_ID),
                    any(), any(), eq(ORG_ID), any(), any(), any(), anyString());

            // Entity の save は 2 回（id 確定前 / s3Key 確定後）
            verify(covenantRepository, times(2)).save(any(SuccessionCovenantEntity.class));
        }

        @Test
        @DisplayName("異常系: 居住者台帳が無ければ RESIDENT_REGISTRY_NOT_FOUND")
        void signCovenant_resident_not_found() {
            when(residentRegistryRepository.findById(RESIDENT_ID)).thenReturn(Optional.empty());
            SignCovenantRequest req = SignCovenantRequest.builder()
                    .covenantType("PRIVACY_CONSENT")
                    .residentRegistryId(RESIDENT_ID)
                    .covenantVersion("v1.0.0")
                    .confirmedItems(List.of("agree_personal_data_collection", "agree_data_retention_10y"))
                    .build();

            assertThatThrownBy(() -> service.signCovenant(req, SIGNER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.RESIDENT_REGISTRY_NOT_FOUND);

            verify(pdfGeneratorService, never()).generateSignedCovenantPdf(any());
            verify(storageService, never()).upload(anyString(), any(byte[].class), anyString());
        }

        @Test
        @DisplayName("異常系(BOLA): 他人の residentRegistryId を指定すると RESIDENT_REGISTRY_NOT_FOUND"
                + "（存在秘匿・PDF生成に到達しない）")
        void signCovenant_otherUsersResidentRegistry_notFound() {
            when(residentRegistryRepository.findById(RESIDENT_ID)).thenReturn(Optional.of(resident));
            SignCovenantRequest req = SignCovenantRequest.builder()
                    .covenantType("PRIVACY_CONSENT")
                    .residentRegistryId(RESIDENT_ID)
                    .covenantVersion("v1.0.0")
                    .confirmedItems(List.of("agree_personal_data_collection", "agree_data_retention_10y"))
                    .build();

            // resident の userId は SIGNER_USER_ID であり、OTHER_USER_ID とは別人。
            assertThatThrownBy(() -> service.signCovenant(req, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.RESIDENT_REGISTRY_NOT_FOUND);

            // 他人の台帳情報（PII含む）を使った PDF 生成・アップロード・保存へ到達しないことの裏取り。
            verify(dwellingUnitRepository, never()).findById(any());
            verify(pdfGeneratorService, never()).generateSignedCovenantPdf(any());
            verify(storageService, never()).upload(anyString(), any(byte[].class), anyString());
            verify(covenantRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: 必須同意項目が不足なら COVENANT_CONFIRMED_ITEMS_INSUFFICIENT")
        void signCovenant_confirmed_items_insufficient() {
            when(residentRegistryRepository.findById(RESIDENT_ID)).thenReturn(Optional.of(resident));
            when(dwellingUnitRepository.findById(DWELLING_ID)).thenReturn(Optional.of(unit));

            SignCovenantRequest req = SignCovenantRequest.builder()
                    .covenantType("PRIVACY_CONSENT")
                    .residentRegistryId(RESIDENT_ID)
                    .covenantVersion("v1.0.0")
                    .confirmedItems(List.of("agree_personal_data_collection"))  // agree_data_retention_10y 欠落
                    .build();

            assertThatThrownBy(() -> service.signCovenant(req, SIGNER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.COVENANT_CONFIRMED_ITEMS_INSUFFICIENT);
        }

        @Test
        @DisplayName("異常系: 同一台帳×区分で有効な誓約があれば COVENANT_ALREADY_SIGNED")
        void signCovenant_duplicate() {
            when(residentRegistryRepository.findById(RESIDENT_ID)).thenReturn(Optional.of(resident));
            when(dwellingUnitRepository.findById(DWELLING_ID)).thenReturn(Optional.of(unit));
            SuccessionCovenantEntity existing = SuccessionCovenantEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(DWELLING_ID)
                    .residentRegistryId(RESIDENT_ID)
                    .signerUserId(SIGNER_USER_ID)
                    .covenantType("MONITORING_CONSENT")
                    .covenantVersion("v1.0.0")
                    .pdfS3Key("k")
                    .pdfSha256("h")
                    .internalSignatureToken("t")
                    .signedAt(LocalDateTime.now())
                    .build();
            when(covenantRepository.findByResidentRegistryIdAndCovenantTypeAndRevokedAtIsNullAndDeletedAtIsNull(
                    RESIDENT_ID, "MONITORING_CONSENT"))
                    .thenReturn(List.of(existing));

            SignCovenantRequest req = SignCovenantRequest.builder()
                    .covenantType("MONITORING_CONSENT")
                    .residentRegistryId(RESIDENT_ID)
                    .covenantVersion("v1.0.0")
                    .confirmedItems(List.of("agree_activity_monitoring", "agree_data_retention_10y"))
                    .build();

            assertThatThrownBy(() -> service.signCovenant(req, SIGNER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.COVENANT_ALREADY_SIGNED);
        }
    }

    // ─── 撤回 ────────────────────────────────────────────────

    @Nested
    @DisplayName("revokeCovenant")
    class RevokeCovenant {

        @Test
        @DisplayName("正常系: 本人が撤回すると revoked_at がセットされ監査ログが記録される")
        void revoke_success() {
            UUID id = UUID.randomUUID();
            SuccessionCovenantEntity entity = SuccessionCovenantEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(DWELLING_ID)
                    .residentRegistryId(RESIDENT_ID)
                    .signerUserId(SIGNER_USER_ID)
                    .covenantType("PRIVACY_CONSENT")
                    .covenantVersion("v1.0.0")
                    .pdfS3Key("k")
                    .pdfSha256("h")
                    .internalSignatureToken("t")
                    .signedAt(LocalDateTime.now())
                    .build();
            setField(entity, "id", id);
            when(covenantRepository.findById(id)).thenReturn(Optional.of(entity));
            when(covenantRepository.save(any(SuccessionCovenantEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            SuccessionCovenantResponse resp = service.revokeCovenant(id, SIGNER_USER_ID);

            assertThat(resp.getRevokedAt()).isNotNull();
            verify(auditLogService).record(
                    eq("COVENANT_REVOKED"), eq(SIGNER_USER_ID),
                    any(), any(), eq(ORG_ID), any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("異常系: 他人が撤回しようとすると COVENANT_FORBIDDEN")
        void revoke_forbidden_for_other_user() {
            UUID id = UUID.randomUUID();
            SuccessionCovenantEntity entity = SuccessionCovenantEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(DWELLING_ID)
                    .residentRegistryId(RESIDENT_ID)
                    .signerUserId(SIGNER_USER_ID)
                    .covenantType("PRIVACY_CONSENT")
                    .covenantVersion("v1.0.0")
                    .pdfS3Key("k")
                    .pdfSha256("h")
                    .internalSignatureToken("t")
                    .signedAt(LocalDateTime.now())
                    .build();
            setField(entity, "id", id);
            when(covenantRepository.findById(id)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.revokeCovenant(id, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.COVENANT_FORBIDDEN);
        }

        @Test
        @DisplayName("異常系: 既に撤回済みなら COVENANT_ALREADY_REVOKED")
        void revoke_already_revoked() {
            UUID id = UUID.randomUUID();
            SuccessionCovenantEntity entity = SuccessionCovenantEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(DWELLING_ID)
                    .residentRegistryId(RESIDENT_ID)
                    .signerUserId(SIGNER_USER_ID)
                    .covenantType("PRIVACY_CONSENT")
                    .covenantVersion("v1.0.0")
                    .pdfS3Key("k")
                    .pdfSha256("h")
                    .internalSignatureToken("t")
                    .signedAt(LocalDateTime.now())
                    .revokedAt(LocalDateTime.now().minusMinutes(1))
                    .build();
            setField(entity, "id", id);
            when(covenantRepository.findById(id)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.revokeCovenant(id, SIGNER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.COVENANT_ALREADY_REVOKED);
        }
    }

    // ─── 取得 ────────────────────────────────────────────────

    @Nested
    @DisplayName("getCovenant")
    class GetCovenant {

        @Test
        @DisplayName("本人なら閲覧可能")
        void self_can_view() {
            UUID id = UUID.randomUUID();
            SuccessionCovenantEntity entity = buildEntity(id);
            when(covenantRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, ORG_ID))
                    .thenReturn(Optional.of(entity));

            SuccessionCovenantResponse resp = service.getCovenant(id, ORG_ID, SIGNER_USER_ID);
            assertThat(resp.getId()).isEqualTo(id);
        }

        @Test
        @DisplayName("他人かつ非 ADMIN なら COVENANT_FORBIDDEN")
        void other_non_admin_forbidden() {
            UUID id = UUID.randomUUID();
            SuccessionCovenantEntity entity = buildEntity(id);
            when(covenantRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, ORG_ID))
                    .thenReturn(Optional.of(entity));
            when(accessControlService.isAdminOrAbove(OTHER_USER_ID, ORG_ID, "ORGANIZATION"))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.getCovenant(id, ORG_ID, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.COVENANT_FORBIDDEN);
        }

        @Test
        @DisplayName("組織 ADMIN なら他人の誓約も閲覧可能")
        void admin_can_view() {
            UUID id = UUID.randomUUID();
            SuccessionCovenantEntity entity = buildEntity(id);
            when(covenantRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, ORG_ID))
                    .thenReturn(Optional.of(entity));
            when(accessControlService.isAdminOrAbove(OTHER_USER_ID, ORG_ID, "ORGANIZATION"))
                    .thenReturn(true);

            SuccessionCovenantResponse resp = service.getCovenant(id, ORG_ID, OTHER_USER_ID);
            assertThat(resp.getId()).isEqualTo(id);
        }

        @Test
        @DisplayName("異テナント（organization_id 不一致）なら NOT_FOUND")
        void cross_tenant_not_found() {
            UUID id = UUID.randomUUID();
            when(covenantRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, 99999L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getCovenant(id, 99999L, SIGNER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.COVENANT_NOT_FOUND);
        }
    }

    // ─── ヘルパー ──────────────────────────────────────────

    private SuccessionCovenantEntity buildEntity(UUID id) {
        SuccessionCovenantEntity entity = SuccessionCovenantEntity.builder()
                .organizationId(ORG_ID)
                .dwellingUnitId(DWELLING_ID)
                .residentRegistryId(RESIDENT_ID)
                .signerUserId(SIGNER_USER_ID)
                .covenantType("PRIVACY_CONSENT")
                .covenantVersion("v1.0.0")
                .pdfS3Key("k")
                .pdfSha256("h")
                .internalSignatureToken("t")
                .signedAt(LocalDateTime.now())
                .build();
        setField(entity, "id", id);
        return entity;
    }

    private static void setField(Object target, String fieldName, Object value) {
        // BaseEntity#id (Long) は private のためリフレクションでセット
        // UuidV7Entity#id (UUID) も同様
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }
}
