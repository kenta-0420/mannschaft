package com.mannschaft.app.disclosure.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.DisclosureOutputFormat;
import com.mannschaft.app.disclosure.DraftStatus;
import com.mannschaft.app.disclosure.dto.DisclosureExportRequest;
import com.mannschaft.app.disclosure.dto.DisclosureExportResponse;
import com.mannschaft.app.disclosure.dto.DisclosureFormDraftRequest;
import com.mannschaft.app.disclosure.dto.DisclosureFormDraftResponse;
import com.mannschaft.app.disclosure.entity.DisclosureFormDraftEntity;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.mannschaft.app.disclosure.repository.DisclosureExportRepository;
import com.mannschaft.app.disclosure.repository.DisclosureFormDraftRepository;
import com.mannschaft.app.disclosure.repository.DisclosureFormTemplateRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DisclosureExportController} 統合テスト（F09.14 Phase 2-ζ-A）。
 *
 * <h3>検証観点</h3>
 * <ul>
 *   <li>POST /{draftId}/export?format=pdf → 201 + 64 文字 hex SHA-256 / downloadUrl / expiresAt</li>
 *   <li>POST /{draftId}/export?format=xlsx → 同上</li>
 *   <li>POST format=docx → DISCLOSURE_004</li>
 *   <li>出力後ドラフト status=EXPORTED</li>
 *   <li>GET /disclosure-exports → 履歴一覧</li>
 *   <li>GET /{exportId}/download → presigned URL を含むレスポンス</li>
 *   <li>改ざん検出: download 時に R2 が異なるバイトを返す → DISCLOSURE_010</li>
 * </ul>
 *
 * <p>R2 通信は {@link AbstractDisclosureIntegrationTest} で MockitoBean 化済み。
 * 各テストで {@code r2StorageService.download()} / {@code generateDownloadUrl()} を
 * 必要に応じてスタブする。{@code upload()} は void なのでデフォルト no-op で問題なし。</p>
 */
@DisplayName("DisclosureExportController 統合テスト（F09.14 Phase 2-ζ-A）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DisclosureExportControllerIntegrationTest extends AbstractDisclosureIntegrationTest {

    @Autowired
    private DisclosureExportController controller;

    @Autowired
    private DisclosureFormDraftController draftController;

    @Autowired
    private DisclosureFormTemplateRepository templateRepository;

    @Autowired
    private DisclosureFormDraftRepository draftRepository;

    @Autowired
    private DisclosureExportRepository exportRepository;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_ID = 931_001L;
    private static final Long ORG_OTHER_ID = 931_002L;

    private Long userId;
    private Long templateId;

    /** {@code R2StorageService.upload} で実際にアップロードされた key→bytes の記録。download スタブで使う。 */
    private final Map<String, byte[]> r2Store = new HashMap<>();

    @BeforeEach
    void setUp() {
        userId = insertUser("dex-test-" + System.nanoTime() + "@example.jp");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, ORG_ID, RoleKind.MEMBER);
        // 認可根治戦役 Wave3-B4: 出力実行/ダウンロード/期限延長は checkAdminOrAbove の対象になったため ADMIN を付与
        MembershipTestHelper.insertUserRole(em, userId, "ADMIN", null, ORG_ID);
        insertOrganization(ORG_ID, "出力テスト組合");

        templateId = saveSystemTemplate(
                "DEX_TPL_" + System.nanoTime(),
                "1.0",
                // 必須項目を含めると refresh-auto-fill 経由で埋めねばならず複雑なので、
                // 必須なし（required=false）の最小スキーマにしておく。
                "{\"sections\":[{\"id\":\"basic\",\"title\":\"基本\",\"fields\":["
                        + "{\"id\":\"property_name\",\"label\":\"物件名\",\"type\":\"TEXT\"}"
                        + "]}]}");

        // R2 upload は記録、download は記録から返却。lenient で未使用テストを許容
        lenient().doAnswer(inv -> {
            String key = inv.getArgument(0);
            byte[] data = inv.getArgument(1);
            r2Store.put(key, data);
            return null;
        }).when(r2StorageService).upload(anyString(), any(byte[].class), anyString());

        lenient().when(r2StorageService.download(anyString()))
                .thenAnswer(inv -> r2Store.getOrDefault(inv.getArgument(0), new byte[0]));

        lenient().when(r2StorageService.generateDownloadUrl(anyString(), any()))
                .thenReturn("https://r2-presigned.example/file?sig=test");

        em.flush();
    }

    @Test
    @DisplayName("POST /{draftId}/export?format=pdf → 201 + 64 文字 SHA-256 hex / downloadUrl 含む")
    void export_pdf_returns201() {
        Long draftId = createDraft();

        ResponseEntity<ApiResponse<DisclosureExportResponse>> resp =
                controller.exportDraft(ORG_ID, draftId, "pdf",
                        new DisclosureExportRequest(null, null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        DisclosureExportResponse body = resp.getBody().getData();
        assertThat(body.exportId()).isNotNull();
        assertThat(body.outputFormat()).isEqualTo(DisclosureOutputFormat.PDF);
        assertThat(body.sha256()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(body.downloadUrl()).startsWith("https://");
        assertThat(body.downloadUrlExpiresAt()).isNotNull();
        assertThat(body.expiresAt()).isNotNull();

        // R2 upload が呼ばれたこと
        verify(r2StorageService).upload(anyString(), any(byte[].class), anyString());
    }

    @Test
    @DisplayName("POST /{draftId}/export?format=xlsx → 201 + xlsx 形式で出力される")
    void export_xlsx_returns201() {
        Long draftId = createDraft();

        ResponseEntity<ApiResponse<DisclosureExportResponse>> resp =
                controller.exportDraft(ORG_ID, draftId, "xlsx",
                        new DisclosureExportRequest("提出先メモ", null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        DisclosureExportResponse body = resp.getBody().getData();
        assertThat(body.outputFormat()).isEqualTo(DisclosureOutputFormat.EXCEL);
        assertThat(body.sha256()).hasSize(64);
        assertThat(body.recipientNote()).isEqualTo("提出先メモ");
    }

    @Test
    @DisplayName("POST /{draftId}/export?format=docx → 201 + Word (.docx) 形式で出力される（Phase 3-B）")
    void export_docx_returns201() {
        Long draftId = createDraft();

        ResponseEntity<ApiResponse<DisclosureExportResponse>> resp =
                controller.exportDraft(ORG_ID, draftId, "docx",
                        new DisclosureExportRequest("Word 出力テスト", null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        DisclosureExportResponse body = resp.getBody().getData();
        assertThat(body.outputFormat()).isEqualTo(DisclosureOutputFormat.WORD);
        assertThat(body.sha256()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(body.recipientNote()).isEqualTo("Word 出力テスト");
    }

    @Test
    @DisplayName("POST /{draftId}/export?format=invalid → DISCLOSURE_004")
    void export_invalidFormat_throwsDisclosure004() {
        Long draftId = createDraft();

        assertThatThrownBy(() -> controller.exportDraft(ORG_ID, draftId, "txt",
                new DisclosureExportRequest(null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_004);
    }

    @Test
    @DisplayName("出力後 ドラフト status = EXPORTED に遷移している")
    void export_changesDraftStatusToExported() {
        Long draftId = createDraft();
        controller.exportDraft(ORG_ID, draftId, "pdf",
                new DisclosureExportRequest(null, null));

        em.flush();
        em.clear();

        // EXPORTED は @SQLRestriction(deleted_at IS NULL) の影響を受けないので findById で取得可
        DisclosureFormDraftEntity refreshed = draftRepository.findById(draftId).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(DraftStatus.EXPORTED);
    }

    @Test
    @DisplayName("GET /disclosure-exports → 出力済み履歴が返る")
    void listExports_returnsHistory() {
        Long draftId = createDraft();
        DisclosureExportResponse first = controller.exportDraft(ORG_ID, draftId, "pdf",
                new DisclosureExportRequest(null, null)).getBody().getData();

        var page = controller.listExports(ORG_ID, 0, 20);
        List<Long> ids = page.getData().stream().map(DisclosureExportResponse::exportId).toList();
        assertThat(ids).contains(first.exportId());
    }

    @Test
    @DisplayName("GET /{exportId}/download → presigned URL 付きレスポンス")
    void downloadExport_returnsPresignedUrl() {
        Long draftId = createDraft();
        DisclosureExportResponse exported = controller.exportDraft(ORG_ID, draftId, "pdf",
                new DisclosureExportRequest(null, null)).getBody().getData();

        em.flush();

        ApiResponse<DisclosureExportResponse> resp =
                controller.downloadExport(ORG_ID, exported.exportId());

        assertThat(resp.getData().downloadUrl()).startsWith("https://");
        assertThat(resp.getData().sha256()).isEqualTo(exported.sha256());
    }

    @Test
    @DisplayName("改ざん検出: SHA-256 不一致 → DISCLOSURE_010")
    void downloadExport_sha256Mismatch_throwsDisclosure010() {
        Long draftId = createDraft();
        DisclosureExportResponse exported = controller.exportDraft(ORG_ID, draftId, "pdf",
                new DisclosureExportRequest(null, null)).getBody().getData();

        // download 時に異なるバイト列を返すよう差し替える（改ざんシミュレーション）
        when(r2StorageService.download(anyString()))
                .thenReturn("TAMPERED_CONTENT_BYTES".getBytes());

        em.flush();

        assertThatThrownBy(() -> controller.downloadExport(ORG_ID, exported.exportId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_010);
    }

    @Test
    @DisplayName("改ざん検出: SHA-256 一致 → 正常 download")
    void downloadExport_sha256Match_succeeds() throws Exception {
        Long draftId = createDraft();
        DisclosureExportResponse exported = controller.exportDraft(ORG_ID, draftId, "pdf",
                new DisclosureExportRequest(null, null)).getBody().getData();

        // download 時の bytes は upload 時に記録した同一バイト列なので、SHA-256 は一致するはず
        em.flush();

        ApiResponse<DisclosureExportResponse> resp =
                controller.downloadExport(ORG_ID, exported.exportId());

        // 念のため出力時 sha256 と SHA-256(upload時 bytes) が等しいことを確認
        byte[] storedBytes = r2Store.values().iterator().next();
        String recomputed = sha256Hex(storedBytes);
        assertThat(resp.getData().sha256()).isEqualTo(recomputed);
    }

    @Test
    @DisplayName("IDOR: 他組織から exportId アクセス → DISCLOSURE_002")
    void getExport_otherOrg_throwsDisclosure002() {
        Long draftId = createDraft();
        DisclosureExportResponse exported = controller.exportDraft(ORG_ID, draftId, "pdf",
                new DisclosureExportRequest(null, null)).getBody().getData();

        assertThatThrownBy(() -> controller.getExport(ORG_OTHER_ID, exported.exportId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_002);
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    private Long createDraft() {
        DisclosureFormDraftRequest req = new DisclosureFormDraftRequest(
                templateId, "出力テストドラフト", null, null, null);
        ResponseEntity<ApiResponse<DisclosureFormDraftResponse>> resp =
                draftController.create(ORG_ID, req);
        return resp.getBody().getData().id();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                "INSERT INTO users ("
                        + "email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, "
                        + "created_at, updated_at) "
                        + "VALUES (:email, :ln, :fn, '出力 太郎', 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", encryptForTest("出力"))
                .setParameter("fn", encryptForTest("太郎"))
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private void insertOrganization(Long id, String name) {
        em.createNativeQuery(
                "INSERT INTO organizations (id, name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, slug, created_at, updated_at) "
                        + "VALUES (:id, :name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("id", id)
                .setParameter("name", name)
                .executeUpdate();
    }

    private Long saveSystemTemplate(String code, String version, String formSchema) {
        DisclosureFormTemplateEntity entity = DisclosureFormTemplateEntity.builder()
                .code(code)
                .name("テンプレ " + code)
                .prefectureCode(null)
                .version(version)
                .isStandard(true)
                .isSystemTemplate(true)
                .scopeType(null)
                .scopeId(null)
                .formSchema(formSchema)
                .effectiveFrom(LocalDate.of(2024, 4, 1))
                .isActive(true)
                .build();
        return templateRepository.save(entity).getId();
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
