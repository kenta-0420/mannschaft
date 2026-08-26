package com.mannschaft.app.disclosure.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.dto.DisclosureExportRequest;
import com.mannschaft.app.disclosure.dto.DisclosureExportResponse;
import com.mannschaft.app.disclosure.dto.DisclosureFormDraftRequest;
import com.mannschaft.app.disclosure.dto.DisclosureFormDraftResponse;
import com.mannschaft.app.disclosure.dto.ExtendExpiryRequest;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * 重要事項説明書 出力履歴 自動削除予定日延長 API 統合テスト
 *（F09.14 Phase 3-F2 / Phase 3-E 対応）。
 *
 * <p>対象エンドポイント:</p>
 * <ul>
 *   <li>PATCH /api/v1/organizations/{id}/disclosure-exports/{exportId}/extend-expiry</li>
 * </ul>
 *
 * <h3>検証観点</h3>
 * <ul>
 *   <li>PATCH → 200 + expires_at 更新</li>
 *   <li>過去日時 → DISCLOSURE_011 (422)</li>
 *   <li>本日から 7 年超 → DISCLOSURE_011 (422)</li>
 *   <li>null → DISCLOSURE_011 (422)（サービス内ガード経路。BeanValidation で先に弾かれる場合は例外で OK）</li>
 *   <li>IDOR: 他組織 → DISCLOSURE_002 (403)</li>
 *   <li>不在 export → DISCLOSURE_001 (404)</li>
 * </ul>
 *
 * <p>本テストは {@link DisclosureExportController#extendExpiry} のみを対象とし、
 * 通常の export/download パスは {@link DisclosureExportControllerIntegrationTest} に委ねる。</p>
 */
@DisplayName("DisclosureExportController#extendExpiry 統合テスト（F09.14 Phase 3-F2）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DisclosureExpiryControllerIntegrationTest extends AbstractDisclosureIntegrationTest {

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

    private static final Long ORG_ID = 961_001L;
    private static final Long ORG_OTHER_ID = 961_002L;

    private Long userId;
    private Long templateId;

    /** R2 upload で記録した key→bytes（download スタブ用）。 */
    private final Map<String, byte[]> r2Store = new HashMap<>();

    @BeforeEach
    void setUp() {
        userId = insertUser("dee-test-" + System.nanoTime() + "@example.jp");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, ORG_ID, RoleKind.MEMBER);
        // 認可根治戦役 Wave3-B4: 出力実行/期限延長は checkAdminOrAbove の対象になったため ADMIN を付与
        MembershipTestHelper.insertUserRole(em, userId, "ADMIN", null, ORG_ID);
        insertOrganization(ORG_ID, "延長テスト組合");

        templateId = saveSystemTemplate(
                "DEE_TPL_" + System.nanoTime(),
                "1.0",
                "{\"sections\":[{\"id\":\"basic\",\"title\":\"基本\",\"fields\":["
                        + "{\"id\":\"property_name\",\"label\":\"物件名\",\"type\":\"TEXT\"}"
                        + "]}]}");

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
    @DisplayName("PATCH /extend-expiry → 200 + expires_at 更新")
    void extendExpiry_returns200AndUpdatesExpiresAt() {
        Long exportId = createExportedRecord();
        LocalDateTime newExpiresAt = LocalDateTime.now().plusDays(180);

        ApiResponse<DisclosureExportResponse> resp =
                controller.extendExpiry(ORG_ID, exportId, new ExtendExpiryRequest(newExpiresAt));

        assertThat(resp.getData().expiresAt()).isNotNull();
        // 秒以下の精度差吸収のため近似比較
        assertThat(resp.getData().expiresAt())
                .isCloseTo(newExpiresAt, within(2L, java.time.temporal.ChronoUnit.SECONDS));

        em.flush();
        em.clear();
        var saved = exportRepository.findById(exportId).orElseThrow();
        assertThat(saved.getExpiresAt())
                .isCloseTo(newExpiresAt, within(2L, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("PATCH 過去日時 → DISCLOSURE_011")
    void extendExpiry_pastDate_throwsDisclosure011() {
        Long exportId = createExportedRecord();
        LocalDateTime past = LocalDateTime.now().minusDays(1);

        assertThatThrownBy(() -> controller.extendExpiry(
                ORG_ID, exportId, new ExtendExpiryRequest(past)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_011);
    }

    @Test
    @DisplayName("PATCH 本日から 7 年超 → DISCLOSURE_011")
    void extendExpiry_beyondSevenYears_throwsDisclosure011() {
        Long exportId = createExportedRecord();
        LocalDateTime tooFar = LocalDateTime.now().plusYears(7).plusDays(1);

        assertThatThrownBy(() -> controller.extendExpiry(
                ORG_ID, exportId, new ExtendExpiryRequest(tooFar)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_011);
    }

    @Test
    @DisplayName("PATCH null → DISCLOSURE_011（または BeanValidation 例外）")
    void extendExpiry_nullDate_rejected() {
        Long exportId = createExportedRecord();

        // ExtendExpiryRequest.newExpiresAt は @NotNull だが、@Valid 経由でない直呼びでは
        // サービス層の null ガードに到達して DISCLOSURE_011 となる。
        ExtendExpiryRequest req = new ExtendExpiryRequest(null);

        assertThatThrownBy(() -> controller.extendExpiry(ORG_ID, exportId, req))
                .satisfiesAnyOf(
                        e -> assertThat(e).isInstanceOf(BusinessException.class)
                                .extracting("errorCode").isEqualTo(DisclosureErrorCode.DISCLOSURE_011),
                        e -> assertThat(e).isInstanceOf(jakarta.validation.ConstraintViolationException.class));
    }

    @Test
    @DisplayName("IDOR: 他組織 ID から exportId に対する PATCH → DISCLOSURE_002")
    void extendExpiry_otherOrg_throwsDisclosure002() {
        Long exportId = createExportedRecord();
        LocalDateTime newExpiresAt = LocalDateTime.now().plusDays(30);

        assertThatThrownBy(() -> controller.extendExpiry(
                ORG_OTHER_ID, exportId, new ExtendExpiryRequest(newExpiresAt)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_002);
    }

    @Test
    @DisplayName("PATCH 不在 export → DISCLOSURE_001")
    void extendExpiry_unknownExport_throwsDisclosure001() {
        LocalDateTime newExpiresAt = LocalDateTime.now().plusDays(30);

        assertThatThrownBy(() -> controller.extendExpiry(
                ORG_ID, 99_999_999L, new ExtendExpiryRequest(newExpiresAt)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_001);
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    private Long createExportedRecord() {
        DisclosureFormDraftRequest draftReq = new DisclosureFormDraftRequest(
                templateId, "延長テスト用ドラフト", null, null, null);
        ResponseEntity<ApiResponse<DisclosureFormDraftResponse>> draftResp =
                draftController.create(ORG_ID, draftReq);
        Long draftId = draftResp.getBody().getData().id();

        ResponseEntity<ApiResponse<DisclosureExportResponse>> exportResp = controller.exportDraft(
                ORG_ID, draftId, "pdf",
                new DisclosureExportRequest("延長テスト", null));
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
                        + "VALUES (:email, :ln, :fn, '延長 太郎', 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", encryptForTest("延長"))
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
