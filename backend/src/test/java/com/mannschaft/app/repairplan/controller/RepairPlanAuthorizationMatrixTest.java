package com.mannschaft.app.repairplan.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.repairplan.dto.AddCardRequest;
import com.mannschaft.app.repairplan.dto.CreateKanbanRequest;
import com.mannschaft.app.repairplan.dto.CreateRepairPlanItemRequest;
import com.mannschaft.app.repairplan.dto.GenerateHandoverPackRequest;
import com.mannschaft.app.repairplan.dto.HandoverPackDto;
import com.mannschaft.app.repairplan.dto.MoveCardRequest;
import com.mannschaft.app.repairplan.dto.PublishAsAnnouncementRequest;
import com.mannschaft.app.repairplan.dto.QuoteCardDto;
import com.mannschaft.app.repairplan.dto.QuoteKanbanDto;
import com.mannschaft.app.repairplan.dto.RepairPlanItemDto;
import com.mannschaft.app.repairplan.dto.SaveScenarioRequest;
import com.mannschaft.app.repairplan.dto.ScenarioDto;
import com.mannschaft.app.repairplan.dto.SimulateRepairPlanRequest;
import com.mannschaft.app.repairplan.entity.TeamMemberTerm;
import com.mannschaft.app.repairplan.repository.TeamMemberTermRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F08.8 Phase 6 — 認可マトリクス統合テスト。
 *
 * <p>ADMIN / DEPUTY_ADMIN / MANAGER（未使用、MEMBERで代替） / MEMBER の 4 ロールに対して
 * 主要エンドポイントへのアクセス可否を HTTP ステータスコードで検証する。</p>
 *
 * <h3>認可マトリクス（テスト対象）</h3>
 * <table border="1">
 *   <tr><th>エンドポイント</th><th>ADMIN</th><th>DEPUTY_ADMIN</th><th>MEMBER</th></tr>
 *   <tr><td>POST /repair-plan/items</td><td>201</td><td>201</td><td>403</td></tr>
 *   <tr><td>GET /repair-plan/items</td><td>200</td><td>200</td><td>200</td></tr>
 *   <tr><td>DELETE /repair-plan/items/{id}</td><td>204</td><td>204</td><td>403</td></tr>
 *   <tr><td>POST /repair-plan/scenarios/simulate</td><td>200</td><td>200</td><td>200</td></tr>
 *   <tr><td>POST /repair-plan/scenarios</td><td>201</td><td>201</td><td>403</td></tr>
 *   <tr><td>GET /repair-plan/scenarios</td><td>200</td><td>200</td><td>200</td></tr>
 *   <tr><td>POST .../publish-as-announcement</td><td>200</td><td>200</td><td>403</td></tr>
 *   <tr><td>POST /repair-plan/quote-kanbans</td><td>201</td><td>201</td><td>403</td></tr>
 *   <tr><td>POST /quote-kanbans/{id}/cards/{cardId}/move</td><td>200</td><td>200</td><td>403</td></tr>
 *   <tr><td>POST /repair-plan/handover-packs</td><td>201</td><td>201</td><td>403</td></tr>
 *   <tr><td>GET /repair-plan/handover-packs</td><td>200</td><td>200</td><td>200</td></tr>
 * </table>
 *
 * <p>HTTP ステータスコードのみ検証（レスポンスボディの詳細は各コントローラテストに任せる）。</p>
 */
@DisplayName("RepairPlan 認可マトリクス統合テスト（F08.8 Phase 6）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RepairPlanAuthorizationMatrixTest extends AbstractRepairPlanPhase5IntegrationTest {

    @Autowired
    private RepairPlanItemController itemController;

    @Autowired
    private RepairPlanScenarioController scenarioController;

    @Autowired
    private RepairPlanQuoteKanbanController kanbanController;

    @Autowired
    private BoardHandoverPackController packController;

    @Autowired
    private TeamMemberTermRepository termRepository;

    @PersistenceContext
    private EntityManager em;

    /** テスト組織 ID（シードと衝突しない大きな値）。 */
    private static final Long ORG_ID = 992_001L;

    /** 非メンバー組織 ID（IDOR/未所属検証用）。 */
    private static final Long ORG_OTHER_ID = 992_002L;

    private Long adminUserId;
    private Long deputyAdminUserId;
    private Long memberUserId;
    private Long vendorId;

    @BeforeEach
    void setUp() {
        mockDependenciesNoop();

        // 各ロールのユーザーを投入
        adminUserId = insertUser("authz-admin-" + System.nanoTime() + "@example.jp");
        deputyAdminUserId = insertUser("authz-deputy-" + System.nanoTime() + "@example.jp");
        memberUserId = insertUser("authz-member-" + System.nanoTime() + "@example.jp");

        // ADMIN ユーザー設定
        MembershipTestHelper.insertMembership(em, adminUserId, ScopeType.ORGANIZATION, ORG_ID, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminUserId, "ADMIN", null, ORG_ID);

        // DEPUTY_ADMIN ユーザー設定
        MembershipTestHelper.insertMembership(em, deputyAdminUserId, ScopeType.ORGANIZATION, ORG_ID, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, deputyAdminUserId, "DEPUTY_ADMIN", null, ORG_ID);

        // MEMBER ユーザー設定
        MembershipTestHelper.insertMembership(em, memberUserId, ScopeType.ORGANIZATION, ORG_ID, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, memberUserId, "MEMBER", null, ORG_ID);

        insertOrganization(ORG_ID, "認可マトリクステスト組合");

        vendorId = insertVendor("テスト建設株式会社", "PASSED", ORG_ID);

        // デフォルトは ADMIN として認証
        authAs(adminUserId);
        em.flush();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 1. POST /repair-plan/items — ADMIN: 201, DEPUTY_ADMIN: 201, MEMBER: 403
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /repair-plan/items: ADMIN → 201")
    void createPlanItem_asAdmin_returns201() {
        authAs(adminUserId);
        ResponseEntity<ApiResponse<RepairPlanItemDto>> resp =
                itemController.create("ORGANIZATION", ORG_ID, ORG_ID, buildCreateItemRequest());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("POST /repair-plan/items: DEPUTY_ADMIN → 201")
    void createPlanItem_asDeputyAdmin_returns201() {
        authAs(deputyAdminUserId);
        ResponseEntity<ApiResponse<RepairPlanItemDto>> resp =
                itemController.create("ORGANIZATION", ORG_ID, ORG_ID, buildCreateItemRequest());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("POST /repair-plan/items: MEMBER → 403（BusinessException）")
    void createPlanItem_asMember_returns403() {
        authAs(memberUserId);
        assertThatThrownBy(() -> itemController.create("ORGANIZATION", ORG_ID, ORG_ID, buildCreateItemRequest()))
                .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. GET /repair-plan/items — 全ロール: 200
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /repair-plan/items: ADMIN → 200")
    void listPlanItems_asAdmin_returns200() {
        authAs(adminUserId);
        var resp = itemController.list("ORGANIZATION", ORG_ID, ORG_ID, null, null, null, 0, 20);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /repair-plan/items: DEPUTY_ADMIN → 200")
    void listPlanItems_asDeputyAdmin_returns200() {
        authAs(deputyAdminUserId);
        var resp = itemController.list("ORGANIZATION", ORG_ID, ORG_ID, null, null, null, 0, 20);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /repair-plan/items: MEMBER → 200")
    void listPlanItems_asMember_returns200() {
        authAs(memberUserId);
        var resp = itemController.list("ORGANIZATION", ORG_ID, ORG_ID, null, null, null, 0, 20);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. DELETE /repair-plan/items/{id} — ADMIN: 204, DEPUTY_ADMIN: 204, MEMBER: 403
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /repair-plan/items/{id}: ADMIN → 204")
    void deletePlanItem_asAdmin_returns204() {
        authAs(adminUserId);
        UUID itemId = createPlanItem();
        em.flush();
        ResponseEntity<Void> resp = itemController.delete("ORGANIZATION", ORG_ID, itemId, ORG_ID, null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("DELETE /repair-plan/items/{id}: DEPUTY_ADMIN → 204")
    void deletePlanItem_asDeputyAdmin_returns204() {
        authAs(adminUserId); // アイテム作成は ADMIN で
        UUID itemId = createPlanItem();
        em.flush();

        authAs(deputyAdminUserId); // 削除は DEPUTY_ADMIN で
        ResponseEntity<Void> resp = itemController.delete("ORGANIZATION", ORG_ID, itemId, ORG_ID, null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("DELETE /repair-plan/items/{id}: MEMBER → 403（BusinessException）")
    void deletePlanItem_asMember_returns403() {
        authAs(adminUserId);
        UUID itemId = createPlanItem();
        em.flush();

        authAs(memberUserId);
        assertThatThrownBy(() -> itemController.delete("ORGANIZATION", ORG_ID, itemId, ORG_ID, null))
                .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 4. POST /repair-plan/scenarios/simulate — 全ロール: 200
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /repair-plan/scenarios/simulate: ADMIN → 200")
    void simulate_asAdmin_returns200() {
        authAs(adminUserId);
        var resp = scenarioController.simulate("ORGANIZATION", ORG_ID, ORG_ID, buildSimulateRequest());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("POST /repair-plan/scenarios/simulate: DEPUTY_ADMIN → 200")
    void simulate_asDeputyAdmin_returns200() {
        authAs(deputyAdminUserId);
        var resp = scenarioController.simulate("ORGANIZATION", ORG_ID, ORG_ID, buildSimulateRequest());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("POST /repair-plan/scenarios/simulate: MEMBER → 200")
    void simulate_asMember_returns200() {
        authAs(memberUserId);
        var resp = scenarioController.simulate("ORGANIZATION", ORG_ID, ORG_ID, buildSimulateRequest());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 5. POST /repair-plan/scenarios — ADMIN: 201, DEPUTY_ADMIN: 201, MEMBER: 403
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /repair-plan/scenarios: ADMIN → 201")
    void saveScenario_asAdmin_returns201() {
        authAs(adminUserId);
        var resp = scenarioController.saveScenario("ORGANIZATION", ORG_ID, ORG_ID, buildSaveScenarioRequest("ADMIN"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("POST /repair-plan/scenarios: DEPUTY_ADMIN → 201")
    void saveScenario_asDeputyAdmin_returns201() {
        authAs(deputyAdminUserId);
        var resp = scenarioController.saveScenario("ORGANIZATION", ORG_ID, ORG_ID, buildSaveScenarioRequest("DEPUTY"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("POST /repair-plan/scenarios: MEMBER → 403（BusinessException）")
    void saveScenario_asMember_returns403() {
        authAs(memberUserId);
        assertThatThrownBy(() ->
                scenarioController.saveScenario("ORGANIZATION", ORG_ID, ORG_ID, buildSaveScenarioRequest("MEMBER")))
                .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 6. GET /repair-plan/scenarios — 全ロール: 200
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /repair-plan/scenarios: ADMIN → 200")
    void listScenarios_asAdmin_returns200() {
        authAs(adminUserId);
        var resp = scenarioController.listScenarios("ORGANIZATION", ORG_ID, ORG_ID);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /repair-plan/scenarios: MEMBER → 200")
    void listScenarios_asMember_returns200() {
        authAs(memberUserId);
        var resp = scenarioController.listScenarios("ORGANIZATION", ORG_ID, ORG_ID);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 7. POST /scenarios/{id}/publish-as-announcement — ADMIN: 200, DEPUTY_ADMIN: 200, MEMBER: 403
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /scenarios/{id}/publish-as-announcement: ADMIN → 200")
    void publishAsAnnouncement_asAdmin_returns200() {
        authAs(adminUserId);
        UUID scenarioId = createScenario("管理者公開シナリオ");
        em.flush();

        PublishAsAnnouncementRequest req = new PublishAsAnnouncementRequest(
                "管理者 告知タイトル", null, 0L
        );
        var resp = scenarioController.publishAsAnnouncement("ORGANIZATION", ORG_ID, scenarioId, ORG_ID, req);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("POST /scenarios/{id}/publish-as-announcement: DEPUTY_ADMIN → 200")
    void publishAsAnnouncement_asDeputyAdmin_returns200() {
        authAs(adminUserId);
        UUID scenarioId = createScenario("副管理者公開シナリオ");
        em.flush();

        authAs(deputyAdminUserId);
        PublishAsAnnouncementRequest req = new PublishAsAnnouncementRequest(
                "副管理者 告知タイトル", null, 0L
        );
        var resp = scenarioController.publishAsAnnouncement("ORGANIZATION", ORG_ID, scenarioId, ORG_ID, req);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("POST /scenarios/{id}/publish-as-announcement: MEMBER → 403（BusinessException）")
    void publishAsAnnouncement_asMember_returns403() {
        authAs(adminUserId);
        UUID scenarioId = createScenario("一般メンバー試行シナリオ");
        em.flush();

        authAs(memberUserId);
        PublishAsAnnouncementRequest req = new PublishAsAnnouncementRequest(
                "一般メンバー 告知タイトル", null, 0L
        );
        assertThatThrownBy(() ->
                scenarioController.publishAsAnnouncement("ORGANIZATION", ORG_ID, scenarioId, ORG_ID, req))
                .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 8. POST /repair-plan/quote-kanbans — ADMIN: 201, DEPUTY_ADMIN: 201, MEMBER: 403
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /repair-plan/quote-kanbans: ADMIN → 201")
    void createKanban_asAdmin_returns201() {
        authAs(adminUserId);
        var resp = kanbanController.createKanban("ORGANIZATION", ORG_ID, ORG_ID, buildCreateKanbanRequest("ADMIN"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("POST /repair-plan/quote-kanbans: DEPUTY_ADMIN → 201")
    void createKanban_asDeputyAdmin_returns201() {
        authAs(deputyAdminUserId);
        var resp = kanbanController.createKanban("ORGANIZATION", ORG_ID, ORG_ID, buildCreateKanbanRequest("DEPUTY"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("POST /repair-plan/quote-kanbans: MEMBER → 403（BusinessException）")
    void createKanban_asMember_returns403() {
        authAs(memberUserId);
        assertThatThrownBy(() ->
                kanbanController.createKanban("ORGANIZATION", ORG_ID, ORG_ID, buildCreateKanbanRequest("MEMBER")))
                .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 9. POST /quote-kanbans/{id}/cards/{cardId}/move — ADMIN: 200, DEPUTY_ADMIN: 200, MEMBER: 403
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /quote-kanbans/{id}/cards/{cardId}/move: ADMIN → 200")
    void moveCard_asAdmin_returns200() {
        authAs(adminUserId);
        UUID kanbanId = createKanban("管理者カード移動テスト");
        UUID cardId = createCard(kanbanId);

        var resp = kanbanController.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("RECEIVED"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("POST /quote-kanbans/{id}/cards/{cardId}/move: DEPUTY_ADMIN → 200")
    void moveCard_asDeputyAdmin_returns200() {
        authAs(adminUserId);
        UUID kanbanId = createKanban("副管理者カード移動テスト");
        UUID cardId = createCard(kanbanId);

        authAs(deputyAdminUserId);
        var resp = kanbanController.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("RECEIVED"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("POST /quote-kanbans/{id}/cards/{cardId}/move: MEMBER → 403（BusinessException）")
    void moveCard_asMember_returns403() {
        authAs(adminUserId);
        UUID kanbanId = createKanban("一般メンバー移動試行テスト");
        UUID cardId = createCard(kanbanId);

        authAs(memberUserId);
        assertThatThrownBy(() ->
                kanbanController.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("RECEIVED")))
                .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 10. POST /repair-plan/handover-packs — ADMIN: 201, DEPUTY_ADMIN: 201, MEMBER: 403
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /repair-plan/handover-packs: ADMIN → 201")
    void generatePack_asAdmin_returns201() {
        authAs(adminUserId);
        UUID termId = insertActiveTerm(adminUserId);
        em.flush();

        var resp = packController.generatePack("ORGANIZATION", ORG_ID, ORG_ID,
                new GenerateHandoverPackRequest(termId, null, "STANDARD"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("POST /repair-plan/handover-packs: DEPUTY_ADMIN → 201")
    void generatePack_asDeputyAdmin_returns201() {
        authAs(deputyAdminUserId);
        UUID termId = insertActiveTerm(deputyAdminUserId);
        em.flush();

        var resp = packController.generatePack("ORGANIZATION", ORG_ID, ORG_ID,
                new GenerateHandoverPackRequest(termId, null, "STANDARD"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("POST /repair-plan/handover-packs: MEMBER → 403（BusinessException）")
    void generatePack_asMember_returns403() {
        authAs(memberUserId);
        assertThatThrownBy(() ->
                packController.generatePack("ORGANIZATION", ORG_ID, ORG_ID,
                        new GenerateHandoverPackRequest(UUID.randomUUID(), null, "STANDARD")))
                .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 11. GET /repair-plan/handover-packs — 全ロール: 200
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /repair-plan/handover-packs: ADMIN → 200")
    void listPacks_asAdmin_returns200() {
        authAs(adminUserId);
        var resp = packController.listPacks("ORGANIZATION", ORG_ID, ORG_ID);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /repair-plan/handover-packs: DEPUTY_ADMIN → 200")
    void listPacks_asDeputyAdmin_returns200() {
        authAs(deputyAdminUserId);
        var resp = packController.listPacks("ORGANIZATION", ORG_ID, ORG_ID);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /repair-plan/handover-packs: MEMBER → 200")
    void listPacks_asMember_returns200() {
        authAs(memberUserId);
        var resp = packController.listPacks("ORGANIZATION", ORG_ID, ORG_ID);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 12. 非メンバー（未所属）アクセス → 403
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /repair-plan/items: 非メンバー（ORG_OTHER_ID）→ 403（BusinessException）")
    void listPlanItems_asNonMember_returns403() {
        // memberUserId は ORG_ID のメンバーだが ORG_OTHER_ID のメンバーではない
        authAs(memberUserId);
        assertThatThrownBy(() ->
                itemController.list("ORGANIZATION", ORG_OTHER_ID, ORG_OTHER_ID, null, null, null, 0, 20))
                .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // ヘルパーメソッド
    // ─────────────────────────────────────────────────────────────────────

    private void authAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                        + "VALUES (:email, :ln, :fn, '認可テスト ユーザー', 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", encryptForTest("認可テスト"))
                .setParameter("fn", encryptForTest("ユーザー"))
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private void insertOrganization(Long id, String name) {
        em.createNativeQuery(
                "INSERT INTO organizations (id, name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, created_at, updated_at, public_id) "
                        + "VALUES (:id, :name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, NOW(), NOW(), UUID_TO_BIN(UUID(), 1))")
                .setParameter("id", id)
                .setParameter("name", name)
                .executeUpdate();
    }

    private Long insertVendor(String name, String complianceStatus, Long scopeId) {
        em.createNativeQuery(
                "INSERT INTO vendors ("
                        + "scope_type, scope_id, name, is_active, "
                        + "compliance_check_status, "
                        + "created_by, version, created_at, updated_at) "
                        + "VALUES ('ORGANIZATION', :scopeId, :name, 1, "
                        + ":complianceStatus, "
                        + ":createdBy, 0, NOW(), NOW())")
                .setParameter("scopeId", scopeId)
                .setParameter("name", name)
                .setParameter("complianceStatus", complianceStatus)
                .setParameter("createdBy", adminUserId)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM vendors WHERE name = :name AND scope_id = :scopeId ORDER BY id DESC LIMIT 1")
                .setParameter("name", name)
                .setParameter("scopeId", scopeId)
                .getSingleResult()).longValue();
    }

    private UUID insertActiveTerm(Long userId) {
        TeamMemberTerm term = TeamMemberTerm.builder()
                .organizationId(ORG_ID)
                .scopeType("ORGANIZATION")
                .scopeId(ORG_ID)
                .userId(userId)
                .roleLabel("理事長")
                .termStart(LocalDate.now().minusMonths(6))
                .termEnd(LocalDate.now().plusMonths(6))
                .isActive(true)
                .build();
        return termRepository.save(term).getId();
    }

    private CreateRepairPlanItemRequest buildCreateItemRequest() {
        return new CreateRepairPlanItemRequest(
                null, "外壁", "外壁塗装工事（認可テスト）", null,
                2030, null, 5_000_000L, null, "PLANNED", null, null
        );
    }

    private UUID createPlanItem() {
        ResponseEntity<ApiResponse<RepairPlanItemDto>> resp =
                itemController.create("ORGANIZATION", ORG_ID, ORG_ID, buildCreateItemRequest());
        return UUID.fromString(resp.getBody().getData().getId());
    }

    private SimulateRepairPlanRequest buildSimulateRequest() {
        return new SimulateRepairPlanRequest(
                new BigDecimal("15000"),
                100,
                new BigDecimal("0.01"),
                new BigDecimal("0.02"),
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                new BigDecimal("500000"),
                30,
                LocalDateTime.now()
        );
    }

    private SaveScenarioRequest buildSaveScenarioRequest(String suffix) {
        return new SaveScenarioRequest(
                "認可テストシナリオ_" + suffix + "_" + System.nanoTime(),
                "テスト説明",
                buildSimulateRequest()
        );
    }

    private UUID createScenario(String name) {
        SaveScenarioRequest req = new SaveScenarioRequest(
                name + "_" + System.nanoTime(), "テスト説明", buildSimulateRequest()
        );
        ResponseEntity<ApiResponse<ScenarioDto>> resp =
                scenarioController.saveScenario("ORGANIZATION", ORG_ID, ORG_ID, req);
        return resp.getBody().getData().id();
    }

    private CreateKanbanRequest buildCreateKanbanRequest(String titleSuffix) {
        return new CreateKanbanRequest(
                "認可テスト相見積もり_" + titleSuffix,
                null, null,
                Instant.now().plus(30, ChronoUnit.DAYS),
                "FULL"
        );
    }

    private UUID createKanban(String title) {
        ResponseEntity<ApiResponse<QuoteKanbanDto>> resp =
                kanbanController.createKanban("ORGANIZATION", ORG_ID, ORG_ID,
                        new CreateKanbanRequest(title, null, null,
                                Instant.now().plus(30, ChronoUnit.DAYS), "FULL"));
        return resp.getBody().getData().id();
    }

    private UUID createCard(UUID kanbanId) {
        AddCardRequest req = new AddCardRequest(vendorId, "テスト建設株式会社", 10_000_000L, null);
        ResponseEntity<ApiResponse<QuoteCardDto>> resp =
                kanbanController.addCard("ORGANIZATION", ORG_ID, kanbanId, ORG_ID, req);
        em.flush();
        return resp.getBody().getData().id();
    }
}
