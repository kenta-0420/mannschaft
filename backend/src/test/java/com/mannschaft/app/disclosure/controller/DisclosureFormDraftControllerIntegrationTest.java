package com.mannschaft.app.disclosure.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.DraftStatus;
import com.mannschaft.app.disclosure.dto.DisclosureFormDraftRequest;
import com.mannschaft.app.disclosure.dto.DisclosureFormDraftResponse;
import com.mannschaft.app.disclosure.entity.DisclosureFormDraftEntity;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DisclosureFormDraftController} 統合テスト（F09.14 Phase 2-ζ-A）。
 *
 * <h3>検証観点</h3>
 * <ul>
 *   <li>POST /organizations/{id}/disclosure-drafts → 201 + ドラフト作成 + version=0</li>
 *   <li>GET /disclosure-drafts/{id} → 200</li>
 *   <li>PUT /disclosure-drafts/{id} (version 必須) → 200 + version インクリメント</li>
 *   <li>PUT 楽観的ロック競合（古い version） → DISCLOSURE_003</li>
 *   <li>POST /{draftId}/refresh-auto-fill → autoFill 反映</li>
 *   <li>DELETE /{draftId} → 204 + 論理削除（再取得不可）</li>
 *   <li>IDOR: 他組織の draftId アクセス → DISCLOSURE_002</li>
 * </ul>
 */
@DisplayName("DisclosureFormDraftController 統合テスト（F09.14 Phase 2-ζ-A）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DisclosureFormDraftControllerIntegrationTest extends AbstractDisclosureIntegrationTest {

    @Autowired
    private DisclosureFormDraftController controller;

    @Autowired
    private DisclosureFormTemplateRepository templateRepository;

    @Autowired
    private DisclosureFormDraftRepository draftRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_ID = 921_001L;
    private static final Long ORG_OTHER_ID = 921_002L;

    private Long userId;
    private Long templateId;

    @BeforeEach
    void setUp() {
        userId = insertUser("dfd-test-" + System.nanoTime() + "@example.jp");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, ORG_ID, RoleKind.MEMBER);

        templateId = saveSystemTemplate(
                "DFD_TPL_" + System.nanoTime(),
                "1.0",
                // セクション 1 つ + 必須項目「property_name」のみ。autoFillFrom も付け
                "{\"sections\":[{\"id\":\"basic\",\"title\":\"基本\",\"fields\":["
                        + "{\"id\":\"property_name\",\"label\":\"物件名\",\"type\":\"TEXT\","
                        + "\"required\":true,\"autoFillFrom\":\"organization.name\"}"
                        + "]}]}");
        em.flush();
    }

    @Test
    @DisplayName("POST /organizations/{id}/disclosure-drafts → 201 + 新規ドラフトが永続化される")
    void create_returns201() {
        DisclosureFormDraftRequest req = new DisclosureFormDraftRequest(
                templateId, "テストドラフト", null, null, null);

        ResponseEntity<ApiResponse<DisclosureFormDraftResponse>> resp =
                controller.create(ORG_ID, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        DisclosureFormDraftResponse body = resp.getBody().getData();
        assertThat(body.id()).isNotNull();
        assertThat(body.title()).isEqualTo("テストドラフト");
        assertThat(body.status()).isEqualTo(DraftStatus.DRAFT);
        assertThat(body.scopeType()).isEqualTo("ORGANIZATION");
        assertThat(body.scopeId()).isEqualTo(ORG_ID);
        assertThat(body.version()).isEqualTo(0L);
        assertThat(body.createdBy()).isEqualTo(userId);
    }

    @Test
    @DisplayName("GET /disclosure-drafts/{id} → 200 で詳細を取得できる")
    void get_returns200() {
        Long draftId = createDraft("取得確認");

        ApiResponse<DisclosureFormDraftResponse> resp = controller.get(ORG_ID, draftId);
        assertThat(resp.getData().id()).isEqualTo(draftId);
        assertThat(resp.getData().title()).isEqualTo("取得確認");
    }

    @Test
    @DisplayName("PUT /disclosure-drafts/{id} (version 必須) → 200 + version インクリメント")
    void update_returns200_withVersionIncrement() {
        Long draftId = createDraft("更新前");
        Long currentVersion = draftRepository.findById(draftId).orElseThrow().getVersion();

        ObjectNode formData = objectMapper.createObjectNode();
        formData.put("property_name", "更新先マンション");

        DisclosureFormDraftRequest updateReq = new DisclosureFormDraftRequest(
                null, "更新後", null, formData, currentVersion);
        ApiResponse<DisclosureFormDraftResponse> resp =
                controller.update(ORG_ID, draftId, updateReq);

        assertThat(resp.getData().title()).isEqualTo("更新後");
        assertThat(resp.getData().version()).isEqualTo(currentVersion + 1);
        assertThat(resp.getData().formData().get("property_name").asText()).isEqualTo("更新先マンション");
    }

    @Test
    @DisplayName("PUT /disclosure-drafts/{id} 楽観的ロック競合 → DISCLOSURE_003")
    void update_optimisticLockConflict_throwsDisclosure003() {
        Long draftId = createDraft("競合元");
        // 古い（誤った）version で更新を試みる
        DisclosureFormDraftRequest staleReq = new DisclosureFormDraftRequest(
                null, "競合更新", null, null, 999L);

        assertThatThrownBy(() -> controller.update(ORG_ID, draftId, staleReq))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_003);
    }

    @Test
    @DisplayName("PUT /disclosure-drafts/{id} version 未指定 → DISCLOSURE_004")
    void update_versionMissing_throwsDisclosure004() {
        Long draftId = createDraft("version 未指定");
        DisclosureFormDraftRequest noVersionReq = new DisclosureFormDraftRequest(
                null, "x", null, null, null);

        assertThatThrownBy(() -> controller.update(ORG_ID, draftId, noVersionReq))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_004);
    }

    @Test
    @DisplayName("POST /{draftId}/refresh-auto-fill?allowPersonalInfo=true → autoFill が formData にマージされる")
    void refreshAutoFill_mergesIntoFormData() {
        // organization レコードが必要（autoFillFrom='organization.name' を解決するため）
        insertOrganization(ORG_ID, "サンプル管理組合");
        Long draftId = createDraft("自動引用テスト");

        ApiResponse<DisclosureFormDraftResponse> resp =
                controller.refreshAutoFill(ORG_ID, draftId, true);

        // autoFill 完了後、property_name が organization.name で埋まっている想定
        assertThat(resp.getData().formData()).isNotNull();
        assertThat(resp.getData().formData().get("property_name").asText())
                .isEqualTo("サンプル管理組合");
    }

    @Test
    @DisplayName("DELETE /disclosure-drafts/{id} → 204 + 以後 GET は DISCLOSURE_001")
    void delete_returns204_thenGetThrows001() {
        Long draftId = createDraft("削除対象");

        ResponseEntity<Void> resp = controller.delete(ORG_ID, draftId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        em.flush();
        em.clear();

        assertThatThrownBy(() -> controller.get(ORG_ID, draftId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_001);
    }

    @Test
    @DisplayName("IDOR: 他組織から draftId アクセス → DISCLOSURE_002")
    void get_otherOrgDraft_throwsDisclosure002() {
        Long draftId = createDraft("自組織のドラフト");

        assertThatThrownBy(() -> controller.get(ORG_OTHER_ID, draftId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_002);
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    private Long createDraft(String title) {
        DisclosureFormDraftRequest req = new DisclosureFormDraftRequest(
                templateId, title, null, null, null);
        ResponseEntity<ApiResponse<DisclosureFormDraftResponse>> resp =
                controller.create(ORG_ID, req);
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
                        + "VALUES (:email, :ln, :fn, '草案 次郎', 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", encryptForTest("草案"))
                .setParameter("fn", encryptForTest("次郎"))
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private void insertOrganization(Long id, String name) {
        // BIGINT UNSIGNED PK で id を明示的に指定
        em.createNativeQuery(
                "INSERT INTO organizations (id, name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, created_at, updated_at, slug) "
                        + "VALUES (:id, :name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, NOW(), NOW(), LEFT(REPLACE(UUID(), '-', ''), 22))")
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
}
