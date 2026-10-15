package com.mannschaft.app.disclosure.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.dto.DisclosureCustomTemplateRequest;
import com.mannschaft.app.disclosure.dto.DisclosureFormTemplateResponse;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DisclosureFormTemplateEditController} 統合テスト（F09.14 Phase 3-F2 / Phase 3-C 対応）。
 *
 * <h3>検証観点</h3>
 * <ul>
 *   <li>POST /api/v1/organizations/{id}/disclosure-templates → 201 + 新規 templateId 払い出し</li>
 *   <li>POST 件数 10 件超過 → DISCLOSURE_013 (422)</li>
 *   <li>PUT /{templateId} → 200 + version_lock インクリメント・version 文字列更新</li>
 *   <li>PUT 楽観ロック競合（旧 versionLock） → DISCLOSURE_003 (409)</li>
 *   <li>PUT システムテンプレ拒否 → DISCLOSURE_014 (403)</li>
 *   <li>DELETE /{templateId} → 204 + 以降 GET で DISCLOSURE_001 (404)</li>
 *   <li>DELETE システムテンプレ拒否 → DISCLOSURE_014 (403)</li>
 *   <li>IDOR: 他組織のカスタムテンプレ ID 操作 → DISCLOSURE_002 (403)</li>
 * </ul>
 *
 * <p>Spring コンテキスト・MySQL Testcontainer は {@link AbstractDisclosureIntegrationTest} 経由で共有。
 * Phase 3-C で追加された {@code DisclosureFormTemplateEditController} が対象。</p>
 */
@DisplayName("DisclosureFormTemplateEditController 統合テスト（F09.14 Phase 3-F2）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DisclosureFormTemplateEditControllerIntegrationTest extends AbstractDisclosureIntegrationTest {

    @Autowired
    private DisclosureFormTemplateEditController controller;

    @Autowired
    private DisclosureFormTemplateController readController;

    @Autowired
    private DisclosureFormTemplateRepository templateRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    /** 本組織 ID。シードと衝突しない大きな値を使う。 */
    private static final Long ORG_ID = 941_001L;

    /** 別組織 ID（IDOR 検証用）。 */
    private static final Long ORG_OTHER_ID = 941_002L;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = insertUser("dfte-test-" + System.nanoTime() + "@example.jp");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, ORG_ID, RoleKind.MEMBER);
        // 認可根治戦役 Wave3-B4: カスタム様式 CRUD は checkAdminOrAbove の対象になったため ADMIN を付与
        MembershipTestHelper.insertUserRole(em, userId, "ADMIN", null, ORG_ID);
        em.flush();
    }

    // =========================================================================
    // POST /api/v1/organizations/{id}/disclosure-templates
    // =========================================================================

    @Test
    @DisplayName("POST → 201 で新規 templateId が払い出される")
    void create_returns201() {
        DisclosureCustomTemplateRequest req = buildRequest(
                "ORG_TPL_" + System.nanoTime() % 1_000_000L, "1.0", null);

        ResponseEntity<ApiResponse<DisclosureFormTemplateResponse>> resp =
                controller.create(ORG_ID, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        DisclosureFormTemplateResponse body = resp.getBody().getData();
        assertThat(body.id()).isNotNull();
        assertThat(body.scopeType()).isEqualTo("ORGANIZATION");
        assertThat(body.scopeId()).isEqualTo(ORG_ID);
        assertThat(body.isSystemTemplate()).isFalse();
        assertThat(body.createdBy()).isEqualTo(userId);
        assertThat(body.versionLock()).isEqualTo(0L);
    }

    @Test
    @DisplayName("POST 件数 10 件超過 → DISCLOSURE_013")
    void create_exceedingLimit_throwsDisclosure013() {
        // 10 件まで作る（正常系）
        for (int i = 0; i < 10; i++) {
            controller.create(ORG_ID, buildRequest("ORG_LIMIT_" + i + "_" + System.nanoTime(), "1.0", null));
        }
        em.flush();

        DisclosureCustomTemplateRequest overflow = buildRequest(
                "ORG_OVERFLOW_" + System.nanoTime() % 1_000_000L, "1.0", null);

        assertThatThrownBy(() -> controller.create(ORG_ID, overflow))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_013);
    }

    // =========================================================================
    // PUT /api/v1/organizations/{id}/disclosure-templates/{templateId}
    // =========================================================================

    @Test
    @DisplayName("PUT → 200 + version 文字列更新 + version_lock インクリメント")
    void update_returns200AndIncrementsVersionLock() {
        Long templateId = createCustomViaController("ORG_PUT_" + System.nanoTime() % 1_000_000L, "1.0");
        em.flush();

        DisclosureFormTemplateEntity before = templateRepository.findById(templateId).orElseThrow();
        Long beforeLock = before.getVersionLock();
        String code = before.getCode();

        DisclosureCustomTemplateRequest update = buildRequestWithVersionLock(
                code, "2.0", null, beforeLock);
        ApiResponse<DisclosureFormTemplateResponse> resp = controller.update(ORG_ID, templateId, update);

        DisclosureFormTemplateResponse body = resp.getData();
        assertThat(body.version()).isEqualTo("2.0");
        assertThat(body.versionLock()).isEqualTo(beforeLock + 1);
        assertThat(body.code()).isEqualTo(code);
    }

    @Test
    @DisplayName("PUT 楽観ロック競合 → DISCLOSURE_003")
    void update_optimisticLockConflict_throwsDisclosure003() {
        Long templateId = createCustomViaController("ORG_LOCK_" + System.nanoTime() % 1_000_000L, "1.0");
        em.flush();

        DisclosureFormTemplateEntity entity = templateRepository.findById(templateId).orElseThrow();
        String code = entity.getCode();
        Long currentLock = entity.getVersionLock();

        // 古い versionLock（current-1 や明らかに不整合な値）を送ると DISCLOSURE_003
        DisclosureCustomTemplateRequest stale = buildRequestWithVersionLock(
                code, "1.1", null, currentLock + 99L);

        assertThatThrownBy(() -> controller.update(ORG_ID, templateId, stale))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_003);
    }

    @Test
    @DisplayName("PUT システム提供テンプレ → DISCLOSURE_014")
    void update_systemTemplate_throwsDisclosure014() {
        Long systemTemplateId = saveSystemTemplate(
                "SYS_PUT_" + System.nanoTime() % 1_000_000L, "1.0");
        em.flush();

        DisclosureCustomTemplateRequest req = buildRequestWithVersionLock(
                "SYS_PUT_NEW_CODE", "2.0", null, 0L);

        assertThatThrownBy(() -> controller.update(ORG_ID, systemTemplateId, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_014);
    }

    // =========================================================================
    // DELETE /api/v1/organizations/{id}/disclosure-templates/{templateId}
    // =========================================================================

    @Test
    @DisplayName("DELETE → 204 + 以降 GET で DISCLOSURE_001（論理削除済）")
    void delete_returnsNoContentAndSubsequentGetThrows001() {
        Long templateId = createCustomViaController("ORG_DEL_" + System.nanoTime() % 1_000_000L, "1.0");
        em.flush();

        ResponseEntity<Void> resp = controller.delete(ORG_ID, templateId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        em.flush();
        em.clear();

        // 削除後、参照系 (GET) で DISCLOSURE_001（論理削除分は SQLRestriction で除外される）
        assertThatThrownBy(() -> readController.get(templateId, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_001);
    }

    @Test
    @DisplayName("DELETE システム提供テンプレ → DISCLOSURE_014")
    void delete_systemTemplate_throwsDisclosure014() {
        Long systemTemplateId = saveSystemTemplate(
                "SYS_DEL_" + System.nanoTime() % 1_000_000L, "1.0");
        em.flush();

        assertThatThrownBy(() -> controller.delete(ORG_ID, systemTemplateId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_014);
    }

    // =========================================================================
    // IDOR
    // =========================================================================

    @Test
    @DisplayName("IDOR: 他組織のカスタムテンプレに対する PUT → DISCLOSURE_002")
    void update_otherOrgCustom_throwsDisclosure002() {
        Long otherTemplateId = saveCustomTemplate(
                "OTHER_PUT_" + System.nanoTime() % 1_000_000L, "1.0", ORG_OTHER_ID);
        em.flush();

        DisclosureFormTemplateEntity entity = templateRepository.findById(otherTemplateId).orElseThrow();
        DisclosureCustomTemplateRequest req = buildRequestWithVersionLock(
                entity.getCode(), "2.0", null, entity.getVersionLock());

        assertThatThrownBy(() -> controller.update(ORG_ID, otherTemplateId, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_002);
    }

    @Test
    @DisplayName("IDOR: 他組織のカスタムテンプレに対する DELETE → DISCLOSURE_002")
    void delete_otherOrgCustom_throwsDisclosure002() {
        Long otherTemplateId = saveCustomTemplate(
                "OTHER_DEL_" + System.nanoTime() % 1_000_000L, "1.0", ORG_OTHER_ID);
        em.flush();

        assertThatThrownBy(() -> controller.delete(ORG_ID, otherTemplateId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_002);
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    private DisclosureCustomTemplateRequest buildRequest(String code, String version, Long versionLock) {
        return new DisclosureCustomTemplateRequest(
                code,
                "テンプレ " + code,
                null,
                version,
                buildMinimalSchema(),
                null,
                null,
                LocalDate.of(2024, 4, 1),
                null,
                Boolean.TRUE,
                versionLock);
    }

    private DisclosureCustomTemplateRequest buildRequestWithVersionLock(
            String code, String version, String prefectureCode, Long versionLock) {
        return new DisclosureCustomTemplateRequest(
                code,
                "テンプレ " + code,
                prefectureCode,
                version,
                buildMinimalSchema(),
                null,
                null,
                LocalDate.of(2024, 4, 1),
                null,
                Boolean.TRUE,
                versionLock);
    }

    private JsonNode buildMinimalSchema() {
        try {
            return objectMapper.readTree(
                    "{\"sections\":[{\"id\":\"basic\",\"title\":\"基本\",\"fields\":["
                            + "{\"id\":\"property_name\",\"label\":\"物件名\",\"type\":\"TEXT\"}"
                            + "]}]}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Controller 経由でカスタムテンプレを作成して ID を返す。
     * 監査ログ・件数カウントなどサービス層の通常パスを通すため、saveCustomTemplate（直接 save）
     * ではなくこの経路を更新系テストでは使う。
     */
    private Long createCustomViaController(String code, String version) {
        ResponseEntity<ApiResponse<DisclosureFormTemplateResponse>> resp =
                controller.create(ORG_ID, buildRequest(code, version, null));
        return resp.getBody().getData().id();
    }

    /** システム提供テンプレを直接 save。is_system_template=true のため Service 経由では作れない。 */
    private Long saveSystemTemplate(String code, String version) {
        DisclosureFormTemplateEntity entity = DisclosureFormTemplateEntity.builder()
                .code(code)
                .name("システム提供 " + code)
                .prefectureCode(null)
                .version(version)
                .isStandard(true)
                .isSystemTemplate(true)
                .scopeType(null)
                .scopeId(null)
                .formSchema("{\"sections\":[]}")
                .effectiveFrom(LocalDate.of(2024, 4, 1))
                .isActive(true)
                .build();
        return templateRepository.save(entity).getId();
    }

    /** 別組織のカスタムテンプレ（IDOR 検証用）を直接 save。 */
    private Long saveCustomTemplate(String code, String version, Long scopeId) {
        DisclosureFormTemplateEntity entity = DisclosureFormTemplateEntity.builder()
                .code(code)
                .name("カスタム " + code)
                .prefectureCode(null)
                .version(version)
                .isStandard(false)
                .isSystemTemplate(false)
                .scopeType("ORGANIZATION")
                .scopeId(scopeId)
                .formSchema("{\"sections\":[]}")
                .effectiveFrom(LocalDate.of(2024, 4, 1))
                .isActive(true)
                .build();
        return templateRepository.save(entity).getId();
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
                        + "VALUES (:email, :ln, :fn, '編集 太郎', 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", encryptForTest("編集"))
                .setParameter("fn", encryptForTest("太郎"))
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }
}
