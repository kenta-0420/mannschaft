package com.mannschaft.app.property.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.excel.ExcelFontConfig;
import com.mannschaft.app.common.excel.ExcelGeneratorService;
import com.mannschaft.app.common.excel.ExcelResponseHelper;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.property.PropertyHistoryErrorCode;
import com.mannschaft.app.property.VendorCategory;
import com.mannschaft.app.property.WorkPackageStatus;
import com.mannschaft.app.property.WorkPackageVisibility;
import com.mannschaft.app.property.WorkType;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.entity.VendorEntity;
import com.mannschaft.app.property.repository.PropertyWorkDocumentRepository;
import com.mannschaft.app.property.repository.PropertyWorkPackageRepository;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link PropertyWorkExportService} 単体テスト（F09.13 Phase 1-ζ-A）。
 *
 * <p>設計書 §5.6 エクスポートに対応:</p>
 * <ul>
 *   <li>exportSinglePackage(format=pdf/xlsx): バイト配列 + Content-Type 検証</li>
 *   <li>exportList: 4 シート構成（XSSFWorkbook で読み戻して名前検証）</li>
 *   <li>マスキング統合: MEMBER viewer で出力 → 金額カラムが ●●● 化される</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PropertyWorkExportService 単体テスト（F09.13 Phase 1-ζ-A）")
class PropertyWorkExportServiceTest {

    @Mock
    private PropertyWorkPackageRepository packageRepository;
    @Mock
    private PropertyWorkDocumentRepository documentRepository;
    @Mock
    private VendorService vendorService;
    @Mock
    private PdfGeneratorService pdfGenerator;

    private PropertyWorkPackageMaskingService maskingService;
    private ExcelGeneratorService excelGenerator;
    private ExcelResponseHelper excelResponseHelper;

    private PropertyWorkExportService service;

    private static final String SCOPE_TEAM = "TEAM";
    private static final Long TEAM_ID = 100L;
    private static final Long PACKAGE_ID = 555L;
    private static final Long VENDOR_ID = 11L;

    /** PDF シグネチャ: %PDF-。 */
    private static final byte[] PDF_SIGNATURE = {0x25, 0x50, 0x44, 0x46, 0x2D};

    @BeforeEach
    void setUp() {
        // マスキングサービスは実体（Service 本体の動作と整合）
        maskingService = new PropertyWorkPackageMaskingService();
        // Excel は本物を使い、生成バイト列を実際に検証する
        excelGenerator = new ExcelGeneratorService(new ExcelFontConfig());
        excelResponseHelper = new ExcelResponseHelper();

        service = new PropertyWorkExportService(
                packageRepository, documentRepository, maskingService, vendorService,
                excelGenerator, excelResponseHelper, pdfGenerator);
    }

    private PropertyWorkPackageEntity packageOf(WorkPackageVisibility v) {
        PropertyWorkPackageEntity e = PropertyWorkPackageEntity.builder()
                .scopeType(SCOPE_TEAM)
                .scopeId(TEAM_ID)
                .workType(WorkType.RENOVATION)
                .category("外壁塗装")
                .title("南側外壁大規模修繕")
                .estimatedAmount(12_000_000L)
                .contractAmount(11_500_000L)
                .actualAmount(11_400_000L)
                .currency("JPY")
                .vendorId(VENDOR_ID)
                .vendorNameSnapshot("○○塗装工業")
                .visibility(v)
                .status(WorkPackageStatus.IN_PROGRESS)
                .attachmentCount(0)
                .commentCount(0)
                .isDisclosable(true)
                .createdBy(7L)
                .build();
        ReflectionTestUtils.setField(e, "id", PACKAGE_ID);
        return e;
    }

    private VendorEntity vendor() {
        VendorEntity v = VendorEntity.builder()
                .scopeType(SCOPE_TEAM)
                .scopeId(TEAM_ID)
                .name("○○塗装工業")
                .nameKana("マルマル")
                .category(VendorCategory.CONSTRUCTION)
                .phone("03-1234-5678")
                .email("info@example.jp")
                .address("東京都千代田区1-2-3")
                .contactPerson("担当者")
                .isActive(true)
                .createdBy(7L)
                .build();
        ReflectionTestUtils.setField(v, "id", VENDOR_ID);
        return v;
    }

    private UserScopeRoleSnapshot snapshotWith(String role) {
        return new UserScopeRoleSnapshot(
                false, Map.of(new ScopeKey(SCOPE_TEAM, TEAM_ID), role),
                Map.of(), Set.of(), Set.of());
    }

    // =========================================================================
    // PDF 出力
    // =========================================================================

    @Nested
    @DisplayName("PDF 出力")
    class PdfExport {

        @Test
        @DisplayName("exportSinglePackage(format=pdf): PDF byte[] と Content-Type が application/pdf で返る")
        void exportSinglePdf() {
            PropertyWorkPackageEntity pkg = packageOf(WorkPackageVisibility.MEMBERS_MASKED);
            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.of(pkg));
            given(vendorService.getVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID)).willReturn(vendor());
            given(documentRepository.findByPackageIdOrderByDisplayOrderAscIdAsc(PACKAGE_ID))
                    .willReturn(List.of());

            byte[] fakePdf = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37}; // "%PDF-1.7"
            given(pdfGenerator.generateFromTemplate(eq("pdf/property-work-history"), any()))
                    .willReturn(fakePdf);

            ResponseEntity<byte[]> resp = service.exportSinglePackage(
                    SCOPE_TEAM, TEAM_ID, PACKAGE_ID, "pdf", snapshotWith("ADMIN"));

            assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
            assertThat(resp.getBody()).startsWith(PDF_SIGNATURE);
        }

        @Test
        @DisplayName("不在 ID は PROPERTY_001")
        void notFound_throws() {
            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.exportSinglePackage(
                    SCOPE_TEAM, TEAM_ID, PACKAGE_ID, "pdf", snapshotWith("ADMIN")))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_001);
        }

        @Test
        @DisplayName("IDOR: 他スコープのパッケージ ID を当てると PROPERTY_001 で 404 扱い")
        void idor_throws() {
            PropertyWorkPackageEntity otherScope = packageOf(WorkPackageVisibility.ADMINS_ONLY);
            ReflectionTestUtils.setField(otherScope, "scopeType", "ORGANIZATION");
            ReflectionTestUtils.setField(otherScope, "scopeId", 999L);
            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.of(otherScope));

            assertThatThrownBy(() -> service.exportSinglePackage(
                    SCOPE_TEAM, TEAM_ID, PACKAGE_ID, "pdf", snapshotWith("ADMIN")))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_001);
        }

        @Test
        @DisplayName("不可視 viewer（visibility が SUPPORTER しか見えないが MEMBER でない）→ PROPERTY_002")
        void notVisible_throws() {
            PropertyWorkPackageEntity pkg = packageOf(WorkPackageVisibility.ADMINS_ONLY);
            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.of(pkg));
            given(vendorService.getVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID)).willReturn(vendor());

            assertThatThrownBy(() -> service.exportSinglePackage(
                    SCOPE_TEAM, TEAM_ID, PACKAGE_ID, "pdf", snapshotWith("MEMBER")))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_002);
        }
    }

    // =========================================================================
    // Excel 出力（4 シート）
    // =========================================================================

    @Nested
    @DisplayName("Excel 出力（4 シート構成）")
    class ExcelExport {

        @Test
        @DisplayName("exportSinglePackage(format=xlsx): Content-Type が xlsx で body が PK 始まり")
        void exportSingleExcel_signatures() throws Exception {
            PropertyWorkPackageEntity pkg = packageOf(WorkPackageVisibility.ADMINS_ONLY);
            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.of(pkg));
            given(vendorService.getVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID)).willReturn(vendor());

            ResponseEntity<byte[]> resp = service.exportSinglePackage(
                    SCOPE_TEAM, TEAM_ID, PACKAGE_ID, "xlsx", snapshotWith("ADMIN"));

            assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(resp.getHeaders().getContentType().toString())
                    .contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            assertThat(resp.getBody()).isNotNull();
            // Excel (xlsx) は ZIP なので 'PK' (0x50, 0x4B) シグネチャ
            assertThat(resp.getBody()[0]).isEqualTo((byte) 0x50);
            assertThat(resp.getBody()[1]).isEqualTo((byte) 0x4B);
        }

        @Test
        @DisplayName("exportList: 4 シート (サマリ/履歴一覧/業者別集計/カテゴリ別集計) で構成される")
        void exportList_fourSheets() throws Exception {
            PropertyWorkPackageEntity pkg = packageOf(WorkPackageVisibility.ADMINS_ONLY);
            Page<PropertyWorkPackageEntity> page = new PageImpl<>(List.of(pkg));
            given(packageRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                    eq(SCOPE_TEAM), eq(TEAM_ID), any(Pageable.class))).willReturn(page);
            given(vendorService.getVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID)).willReturn(vendor());

            ResponseEntity<byte[]> resp = service.exportList(
                    SCOPE_TEAM, TEAM_ID, null, null, null, null, null,
                    "xlsx", snapshotWith("ADMIN"));

            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(resp.getBody()))) {
                assertThat(wb.getNumberOfSheets()).isEqualTo(4);
                assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("サマリ");
                assertThat(wb.getSheetAt(1).getSheetName()).isEqualTo("履歴一覧");
                assertThat(wb.getSheetAt(2).getSheetName()).isEqualTo("業者別集計");
                assertThat(wb.getSheetAt(3).getSheetName()).isEqualTo("カテゴリ別集計");
            }
        }

        @Test
        @DisplayName("マスキング統合: MEMBER viewer の MEMBERS_MASKED パッケージは金額カラムが ●●● 化")
        void masking_member_amountMasked() throws Exception {
            PropertyWorkPackageEntity pkg = packageOf(WorkPackageVisibility.MEMBERS_MASKED);
            Page<PropertyWorkPackageEntity> page = new PageImpl<>(List.of(pkg));
            given(packageRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                    eq(SCOPE_TEAM), eq(TEAM_ID), any(Pageable.class))).willReturn(page);
            given(vendorService.getVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID)).willReturn(vendor());

            ResponseEntity<byte[]> resp = service.exportList(
                    SCOPE_TEAM, TEAM_ID, null, null, null, null, null,
                    "xlsx", snapshotWith("MEMBER"));

            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(resp.getBody()))) {
                XSSFSheet detail = wb.getSheet("履歴一覧");
                assertThat(detail).isNotNull();
                // ヘッダ行 + データ行 1 行
                assertThat(detail.getPhysicalNumberOfRows()).isGreaterThanOrEqualTo(2);
                // ヘッダ "見積金額" の列インデックスを取得
                org.apache.poi.ss.usermodel.Row header = detail.getRow(0);
                int estimatedCol = -1;
                for (int i = 0; i < header.getLastCellNum(); i++) {
                    if ("見積金額".equals(header.getCell(i).getStringCellValue())) {
                        estimatedCol = i;
                        break;
                    }
                }
                assertThat(estimatedCol).isGreaterThan(-1);
                org.apache.poi.ss.usermodel.Row dataRow = detail.getRow(1);
                org.apache.poi.ss.usermodel.Cell cell = dataRow.getCell(estimatedCol);
                // マスク時は文字列 "●●●"
                assertThat(cell.getCellType().toString()).isEqualTo("STRING");
                assertThat(cell.getStringCellValue()).isEqualTo("●●●");
            }
        }

        @Test
        @DisplayName("マスキング統合: ADMIN viewer は金額が数値で出力される")
        void masking_admin_amountVisible() throws Exception {
            PropertyWorkPackageEntity pkg = packageOf(WorkPackageVisibility.MEMBERS_MASKED);
            Page<PropertyWorkPackageEntity> page = new PageImpl<>(List.of(pkg));
            given(packageRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                    eq(SCOPE_TEAM), eq(TEAM_ID), any(Pageable.class))).willReturn(page);
            given(vendorService.getVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID)).willReturn(vendor());

            ResponseEntity<byte[]> resp = service.exportList(
                    SCOPE_TEAM, TEAM_ID, null, null, null, null, null,
                    "xlsx", snapshotWith("ADMIN"));

            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(resp.getBody()))) {
                XSSFSheet detail = wb.getSheet("履歴一覧");
                org.apache.poi.ss.usermodel.Row header = detail.getRow(0);
                int contractCol = -1;
                for (int i = 0; i < header.getLastCellNum(); i++) {
                    if ("契約金額".equals(header.getCell(i).getStringCellValue())) {
                        contractCol = i;
                        break;
                    }
                }
                assertThat(contractCol).isGreaterThan(-1);
                org.apache.poi.ss.usermodel.Cell cell = detail.getRow(1).getCell(contractCol);
                assertThat(cell.getCellType().toString()).isEqualTo("NUMERIC");
                assertThat((long) cell.getNumericCellValue()).isEqualTo(11_500_000L);
            }
        }

        @Test
        @DisplayName("不可視パッケージは出力対象から除外（履歴一覧シートが空）")
        void invisible_excluded() throws Exception {
            PropertyWorkPackageEntity pkg = packageOf(WorkPackageVisibility.ADMINS_ONLY);
            Page<PropertyWorkPackageEntity> page = new PageImpl<>(List.of(pkg));
            given(packageRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                    eq(SCOPE_TEAM), eq(TEAM_ID), any(Pageable.class))).willReturn(page);
            given(vendorService.getVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID)).willReturn(vendor());

            ResponseEntity<byte[]> resp = service.exportList(
                    SCOPE_TEAM, TEAM_ID, null, null, null, null, null,
                    "xlsx", snapshotWith("MEMBER"));

            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(resp.getBody()))) {
                XSSFSheet detail = wb.getSheet("履歴一覧");
                // ヘッダ 1 行のみ（データ行なし）
                assertThat(detail.getPhysicalNumberOfRows()).isEqualTo(1);
            }
        }
    }

    // =========================================================================
    // フォーマット指定異常
    // =========================================================================

    @Test
    @DisplayName("未対応フォーマットは PROPERTY_004")
    void unsupportedFormat_throws() {
        PropertyWorkPackageEntity pkg = packageOf(WorkPackageVisibility.ADMINS_ONLY);
        given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID)).willReturn(Optional.of(pkg));
        given(vendorService.getVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID)).willReturn(vendor());

        assertThatThrownBy(() -> service.exportSinglePackage(
                SCOPE_TEAM, TEAM_ID, PACKAGE_ID, "csv", snapshotWith("ADMIN")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_004);
    }
}
