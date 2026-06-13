package com.mannschaft.app.repairplan.controller;

import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.common.ApiResponse;
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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F08.8 Phase 6 — 監査ログ統合確認テスト。
 *
 * <p>各 API を呼び出して {@code audit_logs} テーブルに対応するイベントが記録されているかを検証する。</p>
 *
 * <h3>検証対象イベント</h3>
 * <ul>
 *   <li>Phase 1: PLAN_ITEM_CREATED, PLAN_ITEM_UPDATED, PLAN_ITEM_DELETED</li>
 *   <li>Phase 2: SCENARIO_CREATED, SCENARIO_LOCKED_FOR_PROPOSAL, SCENARIO_PROPOSAL_CONVERTED</li>
 *   <li>Phase 4: BID_CARD_CREATED, BID_CARD_MOVED, BID_VENDOR_SELECTED</li>
 *   <li>Phase 5: PACK_GENERATED</li>
 * </ul>
 *
 * <p>{@code @Async} で記録される監査ログは {@link RepairPlanQuoteKanbanControllerTest} で
 * 確立済みの {@code Awaitility + REQUIRES_NEW TransactionTemplate} パターンで検証する。</p>
 */
@DisplayName("RepairPlan 監査ログ統合確認テスト（F08.8 Phase 6）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RepairPlanAuditLogIntegrationTest extends AbstractRepairPlanPhase5IntegrationTest {

    @Autowired
    private RepairPlanItemController itemController;

    @Autowired
    private RepairPlanScenarioController scenarioController;

    @Autowired
    private RepairPlanQuoteKanbanController kanbanController;

    @Autowired
    private BoardHandoverPackController packController;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TeamMemberTermRepository termRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    /** テスト組織 ID（シードと衝突しない大きな値）。 */
    private static final Long ORG_ID = 991_001L;

    private Long adminUserId;
    private Long vendorId;

    @BeforeEach
    void setUp() {
        mockDependenciesNoop();

        adminUserId = insertUser("audit-admin-" + System.nanoTime() + "@example.jp");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminUserId.toString(), null, List.of()));

        MembershipTestHelper.insertMembership(em, adminUserId, ScopeType.ORGANIZATION, ORG_ID, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminUserId, "ADMIN", null, ORG_ID);
        insertOrganization(ORG_ID, "監査ログテスト組合");

        vendorId = insertVendor("テスト建設株式会社", "PASSED", ORG_ID);

        em.flush();
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 1: PLAN_ITEM_CREATED が記録される
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("計画項目作成 → PLAN_ITEM_CREATED 監査ログが記録される")
    void createPlanItem_logsAuditEvent_PLAN_ITEM_CREATED() throws InterruptedException {
        CreateRepairPlanItemRequest req = buildCreateItemRequest(
                "外壁塗装工事", "外壁", 2030, 5_000_000L);

        ResponseEntity<ApiResponse<RepairPlanItemDto>> resp =
                itemController.create("ORGANIZATION", ORG_ID, ORG_ID, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        em.flush();

        assertAuditEventRecorded("PLAN_ITEM_CREATED", ORG_ID);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 2: PLAN_ITEM_UPDATED が記録される
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("計画項目更新 → PLAN_ITEM_UPDATED 監査ログが記録される")
    void updatePlanItem_logsAuditEvent_PLAN_ITEM_UPDATED() throws InterruptedException {
        // まず項目を作成
        CreateRepairPlanItemRequest createReq = buildCreateItemRequest(
                "給水管更新工事", "給排水", 2028, 8_000_000L);
        ResponseEntity<ApiResponse<RepairPlanItemDto>> createResp =
                itemController.create("ORGANIZATION", ORG_ID, ORG_ID, createReq);
        UUID itemId = UUID.fromString(createResp.getBody().getData().getId());
        em.flush();

        // 更新を実行
        com.mannschaft.app.repairplan.dto.UpdateRepairPlanItemRequest updateReq =
                new com.mannschaft.app.repairplan.dto.UpdateRepairPlanItemRequest(
                        null, null, "給水管更新工事（修正済）", null, null, null,
                        9_000_000L, null, "IN_PROGRESS", null, null
                );
        itemController.update("ORGANIZATION", ORG_ID, itemId, ORG_ID, null, updateReq);
        em.flush();

        assertAuditEventRecorded("PLAN_ITEM_UPDATED", ORG_ID);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 3: PLAN_ITEM_DELETED が記録される
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("計画項目削除 → PLAN_ITEM_DELETED 監査ログが記録される")
    void deletePlanItem_logsAuditEvent_PLAN_ITEM_DELETED() throws InterruptedException {
        CreateRepairPlanItemRequest createReq = buildCreateItemRequest(
                "屋根防水工事", "屋根", 2025, 3_000_000L);
        ResponseEntity<ApiResponse<RepairPlanItemDto>> createResp =
                itemController.create("ORGANIZATION", ORG_ID, ORG_ID, createReq);
        UUID itemId = UUID.fromString(createResp.getBody().getData().getId());
        em.flush();

        itemController.delete("ORGANIZATION", ORG_ID, itemId, ORG_ID, null);
        em.flush();

        assertAuditEventRecorded("PLAN_ITEM_DELETED", ORG_ID);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 4: SCENARIO_CREATED が記録される
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("シナリオ保存 → SCENARIO_CREATED 監査ログが記録される")
    void saveScenario_logsAuditEvent_SCENARIO_CREATED() throws InterruptedException {
        SaveScenarioRequest req = buildSaveScenarioRequest("監査ログ確認シナリオ");

        ResponseEntity<ApiResponse<ScenarioDto>> resp =
                scenarioController.saveScenario("ORGANIZATION", ORG_ID, ORG_ID, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        em.flush();

        assertAuditEventRecorded("SCENARIO_CREATED", ORG_ID);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 5: SCENARIO_LOCKED_FOR_PROPOSAL が記録される
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("シナリオ公開 → SCENARIO_LOCKED_FOR_PROPOSAL 監査ログが記録される")
    void publishScenario_logsAuditEvent_SCENARIO_LOCKED_FOR_PROPOSAL() throws InterruptedException {
        // シナリオ保存
        SaveScenarioRequest saveReq = buildSaveScenarioRequest("ロックシナリオ");
        ResponseEntity<ApiResponse<ScenarioDto>> saveResp =
                scenarioController.saveScenario("ORGANIZATION", ORG_ID, ORG_ID, saveReq);
        UUID scenarioId = saveResp.getBody().getData().id();
        em.flush();

        // 議案として公開
        PublishAsAnnouncementRequest publishReq = new PublishAsAnnouncementRequest(
                "第1回修繕積立金値上げ提案", "決議第1号", 0L
        );
        scenarioController.publishAsAnnouncement("ORGANIZATION", ORG_ID, scenarioId, ORG_ID, publishReq);
        em.flush();

        assertAuditEventRecorded("SCENARIO_LOCKED_FOR_PROPOSAL", ORG_ID);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 6: SCENARIO_PROPOSAL_CONVERTED が記録される
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("シナリオ公開 → SCENARIO_PROPOSAL_CONVERTED 監査ログが記録される")
    void publishScenario_logsAuditEvent_SCENARIO_PROPOSAL_CONVERTED() throws InterruptedException {
        // シナリオ保存
        SaveScenarioRequest saveReq = buildSaveScenarioRequest("コンバートシナリオ");
        ResponseEntity<ApiResponse<ScenarioDto>> saveResp =
                scenarioController.saveScenario("ORGANIZATION", ORG_ID, ORG_ID, saveReq);
        UUID scenarioId = saveResp.getBody().getData().id();
        em.flush();

        // 議案として公開（LOCKED + CONVERTED の 2 イベントが同時記録される）
        PublishAsAnnouncementRequest publishReq = new PublishAsAnnouncementRequest(
                "修繕計画議案タイトル", null, 0L
        );
        scenarioController.publishAsAnnouncement("ORGANIZATION", ORG_ID, scenarioId, ORG_ID, publishReq);
        em.flush();

        assertAuditEventRecorded("SCENARIO_PROPOSAL_CONVERTED", ORG_ID);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 7: BID_CARD_CREATED が記録される
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("相見積もりカード追加 → BID_CARD_CREATED 監査ログが記録される")
    void addCard_logsAuditEvent_BID_CARD_CREATED() throws InterruptedException {
        UUID kanbanId = createKanban("外壁塗装相見積もり");

        AddCardRequest req = new AddCardRequest(vendorId, "テスト建設株式会社", 12_000_000L, null);
        kanbanController.addCard("ORGANIZATION", ORG_ID, kanbanId, ORG_ID, req);
        em.flush();

        assertAuditEventRecorded("BID_CARD_CREATED", ORG_ID);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 8: BID_CARD_MOVED が記録される
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("相見積もりカード移動（REQUESTED→RECEIVED）→ BID_CARD_MOVED 監査ログが記録される")
    void moveCard_logsAuditEvent_BID_CARD_MOVED() throws InterruptedException {
        UUID kanbanId = createKanban("給水管更新相見積もり");
        UUID cardId = createCard(kanbanId);

        // REQUESTED → RECEIVED（選定前の移動: BID_CARD_MOVED）
        kanbanController.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("RECEIVED"));
        em.flush();

        assertAuditEventRecorded("BID_CARD_MOVED", ORG_ID);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 9: BID_VENDOR_SELECTED が記録される
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("業者選定（→SELECTED）→ BID_VENDOR_SELECTED 監査ログが記録される")
    void moveCard_toSelected_logsAuditEvent_BID_VENDOR_SELECTED() throws InterruptedException {
        UUID kanbanId = createKanban("バリアフリー改修相見積もり");
        UUID cardId = createCard(kanbanId);

        // REQUESTED → RECEIVED → UNDER_REVIEW → SHORTLISTED → SELECTED
        kanbanController.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("RECEIVED"));
        kanbanController.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("UNDER_REVIEW"));
        kanbanController.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("SHORTLISTED"));
        kanbanController.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("SELECTED"));
        em.flush();

        assertAuditEventRecorded("BID_VENDOR_SELECTED", ORG_ID);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 10: PACK_GENERATED が記録される
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("申し送りパック生成 → PACK_GENERATED 監査ログが記録される")
    void generatePack_logsAuditEvent_PACK_GENERATED() throws InterruptedException {
        UUID termId = insertActiveTerm();
        em.flush();

        GenerateHandoverPackRequest req = new GenerateHandoverPackRequest(termId, "監査ログテスト引き継ぎ", "STANDARD");
        ResponseEntity<ApiResponse<HandoverPackDto>> resp =
                packController.generatePack("ORGANIZATION", ORG_ID, ORG_ID, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        em.flush();

        assertAuditEventRecorded("PACK_GENERATED", ORG_ID);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 11: TIMELINE_EXPORTED — 未実装
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Disabled("未実装: RepairPlanTimelineService に TIMELINE_EXPORTED 記録ロジックが未追加")
    @DisplayName("タイムラインエクスポート → TIMELINE_EXPORTED 監査ログが記録される")
    void exportTimeline_logsAuditEvent_TIMELINE_EXPORTED() {
        // TIMELINE_EXPORTED イベントの記録は今後の実装フェーズで対応
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 12: PLAN_ITEM_CSV_IMPORTED — 未実装（Valkey 依存のため統合テストでスキップ）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Disabled("未実装: RepairPlanItemCsvService の PLAN_ITEM_CSV_IMPORTED は Valkey 連携テストのため別途実施")
    @DisplayName("CSV インポート確定 → PLAN_ITEM_CSV_IMPORTED 監査ログが記録される")
    void confirmCsvImport_logsAuditEvent_PLAN_ITEM_CSV_IMPORTED() {
        // CSV インポートの統合テストは Valkey モック設定が必要なため別テストクラスで実施
    }

    // ─────────────────────────────────────────────────────────────────────
    // ヘルパーメソッド
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 非同期記録される監査ログのイベントタイプが ORG_ID に対して記録されているかを検証する。
     * {@code @Async} で記録されるため、Awaitility で最大 5 秒待機しつつ毎回 REQUIRES_NEW
     * トランザクションを開いて確認する（条件成立まで複数回評価）。
     */
    private void assertAuditEventRecorded(String eventType, Long organizationId) {
        // @Async で記録されるため、記録されるまで Awaitility で待機する（毎回 REQUIRES_NEW で再評価）。
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            TransactionTemplate newTx = new TransactionTemplate(txManager);
            newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Boolean recorded = newTx.execute(status ->
                    auditLogRepository.findAll().stream()
                            .anyMatch(log -> eventType.equals(log.getEventType())
                                    && organizationId.equals(log.getOrganizationId())));
            assertThat(recorded)
                    .as("監査ログに " + eventType + " が organizationId=" + organizationId + " で記録されていること")
                    .isTrue();
        });
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
                        + "VALUES (:email, :ln, :fn, '監査ログ テスト', 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", encryptForTest("監査ログ"))
                .setParameter("fn", encryptForTest("テスト"))
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

    private UUID insertActiveTerm() {
        TeamMemberTerm term = TeamMemberTerm.builder()
                .organizationId(ORG_ID)
                .scopeType("ORGANIZATION")
                .scopeId(ORG_ID)
                .userId(adminUserId)
                .roleLabel("理事長")
                .termStart(LocalDate.now().minusMonths(6))
                .termEnd(LocalDate.now().plusMonths(6))
                .isActive(true)
                .build();
        return termRepository.save(term).getId();
    }

    private CreateRepairPlanItemRequest buildCreateItemRequest(
            String title, String category, int year, long amount) {
        return new CreateRepairPlanItemRequest(
                null, category, title, null,
                year, null, amount, null, "PLANNED", null, null
        );
    }

    private SaveScenarioRequest buildSaveScenarioRequest(String name) {
        SimulateRepairPlanRequest simReq = new SimulateRepairPlanRequest(
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
        return new SaveScenarioRequest(name, "テスト説明", simReq);
    }

    private UUID createKanban(String title) {
        CreateKanbanRequest req = new CreateKanbanRequest(
                title, null, null, Instant.now().plus(30, ChronoUnit.DAYS), "FULL"
        );
        ResponseEntity<ApiResponse<QuoteKanbanDto>> resp =
                kanbanController.createKanban("ORGANIZATION", ORG_ID, ORG_ID, req);
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
