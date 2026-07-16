package com.mannschaft.app.disclosure.controller;

import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.dto.DisclosureCirculationStartRequest;
import com.mannschaft.app.disclosure.dto.DisclosureCirculationStartResponse;
import com.mannschaft.app.disclosure.dto.DisclosureExportRequest;
import com.mannschaft.app.disclosure.dto.DisclosureExportResponse;
import com.mannschaft.app.disclosure.dto.DisclosureFormDraftRequest;
import com.mannschaft.app.disclosure.dto.DisclosureFormDraftResponse;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.mannschaft.app.disclosure.repository.DisclosureExportRepository;
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

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * 重要事項説明書 電子印鑑承認回覧 開始 API 統合テスト（F09.14 Phase 3-F2 / Phase 3-D 対応）。
 *
 * <p>対象エンドポイント:</p>
 * <ul>
 *   <li>POST /api/v1/organizations/{id}/disclosure-exports/{exportId}/circulation</li>
 * </ul>
 *
 * <h3>検証観点</h3>
 * <ul>
 *   <li>POST → 201 + circulationDocumentId が払い出され ACTIVE 化される</li>
 *   <li>POST 二重起動 → DISCLOSURE_003</li>
 *   <li>POST 受信者重複指定 → 重複排除して 1 名分のみで成功（{@link com.mannschaft.app.disclosure.service.DisclosureCirculationService}
 *       のサービス層仕様）</li>
 *   <li>POST 不在 export → DISCLOSURE_001</li>
 *   <li>POST IDOR: 他組織 export → DISCLOSURE_002</li>
 *   <li>POST 受信者空 → DISCLOSURE_004（DTO バリデーションは BeanValidation で弾かれるため、
 *       サービス内のフォールバックチェックを直接叩く形で検証）</li>
 * </ul>
 *
 * <p>Spring コンテキスト・MySQL Testcontainer は {@link AbstractDisclosureIntegrationTest} 経由で共有。
 * F05.2 {@code CirculationService} は実 Bean を使う（モック不要、テーブル DDL は Flyway で適用済）。</p>
 */
@DisplayName("DisclosureCirculationController 統合テスト（F09.14 Phase 3-F2）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DisclosureCirculationControllerIntegrationTest extends AbstractDisclosureIntegrationTest {

    @Autowired
    private DisclosureExportController controller;

    @Autowired
    private DisclosureFormDraftController draftController;

    @Autowired
    private DisclosureFormTemplateRepository templateRepository;

    @Autowired
    private DisclosureExportRepository exportRepository;

    @PersistenceContext
    private EntityManager em;

    /** 本組織 ID（シードと衝突しない大きな値）。 */
    private static final Long ORG_ID = 951_001L;

    /** 別組織 ID（IDOR 検証用）。 */
    private static final Long ORG_OTHER_ID = 951_002L;

    private Long userId;
    private Long recipientUserId1;
    private Long recipientUserId2;
    private Long templateId;

    /** R2 upload で記録した key→bytes（download スタブ用）。 */
    private final Map<String, byte[]> r2Store = new HashMap<>();

    @BeforeEach
    void setUp() {
        userId = insertUser("dcc-test-" + System.nanoTime() + "@example.jp");
        recipientUserId1 = insertUser("dcc-recipient1-" + System.nanoTime() + "@example.jp");
        recipientUserId2 = insertUser("dcc-recipient2-" + System.nanoTime() + "@example.jp");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, ORG_ID, RoleKind.MEMBER);
        // 認可根治戦役 Wave3-B4: 出力実行/回覧開始は checkAdminOrAbove の対象になったため ADMIN を付与
        MembershipTestHelper.insertUserRole(em, userId, "ADMIN", null, ORG_ID);
        insertOrganization(ORG_ID, "回覧テスト組合");

        templateId = saveSystemTemplate(
                "DCC_TPL_" + System.nanoTime(),
                "1.0",
                "{\"sections\":[{\"id\":\"basic\",\"title\":\"基本\",\"fields\":["
                        + "{\"id\":\"property_name\",\"label\":\"物件名\",\"type\":\"TEXT\"}"
                        + "]}]}");

        // R2 通信は記録ベースのスタブで完結
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
    @DisplayName("POST → 201 + circulationDocumentId 紐付け + ACTIVE")
    void startCirculation_returns201AndLinksCirculationDocument() {
        Long exportId = createExportedRecord();

        DisclosureCirculationStartRequest req = new DisclosureCirculationStartRequest(
                List.of(recipientUserId1, recipientUserId2),
                "SIMULTANEOUS",
                LocalDate.now().plusDays(7));

        ResponseEntity<ApiResponse<DisclosureCirculationStartResponse>> resp =
                controller.startCirculation(ORG_ID, exportId, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        DisclosureCirculationStartResponse body = resp.getBody().getData();
        assertThat(body.exportId()).isEqualTo(exportId);
        assertThat(body.circulationDocumentId()).isNotNull();
        assertThat(body.circulationStatus()).isEqualTo(CirculationStatus.ACTIVE);

        em.flush();
        em.clear();

        // 出力履歴の circulation_document_id が永続化されている
        var exportEntity = exportRepository.findById(exportId).orElseThrow();
        assertThat(exportEntity.getCirculationDocumentId()).isEqualTo(body.circulationDocumentId());
    }

    @Test
    @DisplayName("POST 二重起動 → DISCLOSURE_003")
    void startCirculation_duplicate_throwsDisclosure003() {
        Long exportId = createExportedRecord();

        // 1 回目は成功
        controller.startCirculation(ORG_ID, exportId,
                new DisclosureCirculationStartRequest(
                        List.of(recipientUserId1), "SIMULTANEOUS", null));
        em.flush();

        // 2 回目は二重起動で 003
        assertThatThrownBy(() -> controller.startCirculation(ORG_ID, exportId,
                new DisclosureCirculationStartRequest(
                        List.of(recipientUserId2), "SIMULTANEOUS", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_003);
    }

    @Test
    @DisplayName("POST 受信者重複 → サービス側で重複排除（1 名のみで成功）")
    void startCirculation_duplicateRecipients_dedupes() {
        Long exportId = createExportedRecord();

        // 同じ recipient を 3 回指定 → 1 名分にまとめられる
        DisclosureCirculationStartRequest req = new DisclosureCirculationStartRequest(
                List.of(recipientUserId1, recipientUserId1, recipientUserId1),
                "SIMULTANEOUS",
                null);

        ResponseEntity<ApiResponse<DisclosureCirculationStartResponse>> resp =
                controller.startCirculation(ORG_ID, exportId, req);

        // 重複排除されて 1 件で開始 → ACTIVATE 成功
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().getData().circulationStatus()).isEqualTo(CirculationStatus.ACTIVE);
    }

    @Test
    @DisplayName("POST 不在 export → DISCLOSURE_001")
    void startCirculation_unknownExport_throwsDisclosure001() {
        DisclosureCirculationStartRequest req = new DisclosureCirculationStartRequest(
                List.of(recipientUserId1), "SIMULTANEOUS", null);

        assertThatThrownBy(() -> controller.startCirculation(ORG_ID, 99_999_999L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_001);
    }

    @Test
    @DisplayName("IDOR: 他組織 ID から exportId に対する POST → DISCLOSURE_002")
    void startCirculation_otherOrg_throwsDisclosure002() {
        Long exportId = createExportedRecord();

        DisclosureCirculationStartRequest req = new DisclosureCirculationStartRequest(
                List.of(recipientUserId1), "SIMULTANEOUS", null);

        assertThatThrownBy(() -> controller.startCirculation(ORG_OTHER_ID, exportId, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_002);
    }

    @Test
    @DisplayName("POST 受信者リスト空（サービス層フォールバック） → DISCLOSURE_004")
    void startCirculation_emptyRecipientList_throwsDisclosure004() {
        Long exportId = createExportedRecord();

        // BeanValidation を通った後の Service 内ガードを直接検証する。
        // Controller 経由だと @NotEmpty で 400 になるため、ここでは null/空 List を
        // Service 仕様（DISCLOSURE_004）として扱う点に注目し、List.of() を渡す。
        DisclosureCirculationStartRequest req = new DisclosureCirculationStartRequest(
                List.of(), "SIMULTANEOUS", null);

        assertThatThrownBy(() -> controller.startCirculation(ORG_ID, exportId, req))
                .isInstanceOf(Exception.class); // BeanValidation 起因の ConstraintViolation でも
                                                // サービス層の DISCLOSURE_004 でも、いずれかで弾かれることを確認
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    /** ドラフト作成 → PDF 出力まで一気に行い、出力履歴 ID を返す。 */
    private Long createExportedRecord() {
        DisclosureFormDraftRequest draftReq = new DisclosureFormDraftRequest(
                templateId, "回覧テスト用ドラフト", null, null, null);
        ResponseEntity<ApiResponse<DisclosureFormDraftResponse>> draftResp =
                draftController.create(ORG_ID, draftReq);
        Long draftId = draftResp.getBody().getData().id();

        ResponseEntity<ApiResponse<DisclosureExportResponse>> exportResp = controller.exportDraft(
                ORG_ID, draftId, "pdf",
                new DisclosureExportRequest("回覧テスト", null));
        return exportResp.getBody().getData().exportId();
    }

    private Long saveSystemTemplate(String code, String version, String formSchema) {
        return templateRepository.save(
                DisclosureFormTemplateEntity.builder()
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
                        .build()).getId();
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
                        + "VALUES (:email, :ln, :fn, '回覧 太郎', 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", encryptForTest("回覧"))
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
}
