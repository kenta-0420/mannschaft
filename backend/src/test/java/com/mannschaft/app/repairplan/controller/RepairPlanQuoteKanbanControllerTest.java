package com.mannschaft.app.repairplan.controller;

import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.repairplan.RepairPlanErrorCode;
import com.mannschaft.app.repairplan.dto.AddCardRequest;
import com.mannschaft.app.repairplan.dto.CreateKanbanRequest;
import com.mannschaft.app.repairplan.dto.MoveCardRequest;
import com.mannschaft.app.repairplan.dto.QuoteCardDto;
import com.mannschaft.app.repairplan.dto.QuoteKanbanDto;
import com.mannschaft.app.repairplan.dto.UpdateKanbanRequest;
import com.mannschaft.app.repairplan.entity.RepairQuoteCard;
import com.mannschaft.app.repairplan.entity.RepairQuoteKanban;
import com.mannschaft.app.repairplan.repository.RepairQuoteCardRepository;
import com.mannschaft.app.repairplan.repository.RepairQuoteKanbanRepository;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RepairPlanQuoteKanbanController} 統合テスト（F08.8 Phase 4）。
 *
 * <h3>検証観点（12件以上）</h3>
 * <ol>
 *   <li>カンバン作成 → 201 + DTO 返却</li>
 *   <li>カンバン一覧取得 → 200 + 一覧</li>
 *   <li>カード追加（正常: compliance=PASSED）→ 201 + stage=REQUESTED</li>
 *   <li>カード追加（compliance_check_status=EXPIRED）→ COMPLIANCE_EXPIRED(403相当)</li>
 *   <li>stage 遷移（REQUESTED → RECEIVED）→ 正常</li>
 *   <li>stage 遷移（後戻り: RECEIVED → REQUESTED）→ INVALID_STAGE_TRANSITION</li>
 *   <li>stage 遷移（SHORTLISTED → SELECTED）→ 正常 + 監査ログ BID_VENDOR_SELECTED</li>
 *   <li>IDOR: 非メンバーの別組織は COMMON_002 / メンバーの別組織カンバンは KANBAN_NOT_FOUND</li>
 *   <li>visibility=HIDDEN: 管理者はフル表示・一般メンバーは amount/vendorName=null</li>
 *   <li>visibility=ANONYMIZED: 一般メンバーは「業者A」+ レンジ表示</li>
 *   <li>入札締切前: FULL でも一般メンバーは業者名・金額がマスクされる</li>
 *   <li>非メンバー: listKanbans / getKanban が COMMON_002（漏洩遮断）</li>
 *   <li>終端ステージ（SELECTED）からの遷移 → INVALID_STAGE_TRANSITION</li>
 *   <li>監査ログ: BID_CARD_CREATED が記録される</li>
 * </ol>
 *
 * <p>AOP モジュールガードは基底クラス {@link AbstractRepairPlanKanbanIntegrationTest} で no-op 化済み。</p>
 *
 * <p>本クラスは MySQL Testcontainers を要する統合テスト（{@code @EnabledIf isDockerAvailable}）。
 * ロール別マスキングの Docker 非依存検証は {@code RepairPlanQuoteKanbanServiceAuthMaskingTest}
 * （Mockito ユニット）で別途固定している。</p>
 */
@DisplayName("RepairPlanQuoteKanbanController 統合テスト（F08.8 Phase 4）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RepairPlanQuoteKanbanControllerTest extends AbstractRepairPlanKanbanIntegrationTest {

    @Autowired
    private RepairPlanQuoteKanbanController controller;

    @Autowired
    private RepairQuoteKanbanRepository kanbanRepository;

    @Autowired
    private RepairQuoteCardRepository cardRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    /** テスト組織 ID（シードと衝突しない大きな値）。 */
    private static final Long ORG_ID = 988_001L;

    /** 別組織 ID（IDOR 検証用）。 */
    private static final Long ORG_OTHER_ID = 988_002L;

    private Long userId;
    private Long vendorId;

    @BeforeEach
    void setUp() {
        mockModuleGuardNoop();

        userId = insertUser("kanban-test-" + System.nanoTime() + "@example.jp");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));

        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, ORG_ID, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, userId, "ADMIN", null, ORG_ID);
        insertOrganization(ORG_ID, "カンバンテスト組合");

        // vendors テーブルに反社チェック済み業者を INSERT
        vendorId = insertVendor("テスト建設株式会社", "PASSED", ORG_ID);

        em.flush();
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 1: カンバン作成 → 201
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /quote-kanbans → 201 + DTO 返却")
    void createKanban_returns201() {
        CreateKanbanRequest req = new CreateKanbanRequest(
                "屋根防水工事 相見積もり",
                null,
                null,
                Instant.now().plus(30, ChronoUnit.DAYS),
                "ANONYMIZED"
        );

        ResponseEntity<ApiResponse<QuoteKanbanDto>> resp =
                controller.createKanban("ORGANIZATION", ORG_ID, ORG_ID, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        QuoteKanbanDto body = resp.getBody().getData();
        assertThat(body.id()).isNotNull();
        assertThat(body.title()).isEqualTo("屋根防水工事 相見積もり");
        assertThat(body.status()).isEqualTo("OPEN");
        assertThat(body.visibilityToMember()).isEqualTo("ANONYMIZED");
        assertThat(body.organizationId()).isEqualTo(ORG_ID);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 2: カンバン一覧取得 → 200
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /quote-kanbans → 200 + 一覧")
    void listKanbans_returns200() {
        UUID kanbanId = createKanban("外壁塗装相見積もり", ORG_ID);

        ResponseEntity<ApiResponse<List<QuoteKanbanDto>>> resp =
                controller.listKanbans("ORGANIZATION", ORG_ID, ORG_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<QuoteKanbanDto> list = resp.getBody().getData();
        assertThat(list).isNotEmpty();
        assertThat(list.stream().anyMatch(k -> k.id().equals(kanbanId))).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 3: カード追加（正常: compliance=PASSED）→ 201
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /quote-kanbans/{kanbanId}/cards → 201 + stage=REQUESTED")
    void addCard_compliancePassed_returns201() {
        UUID kanbanId = createKanban("給水管更新工事", ORG_ID);

        AddCardRequest req = new AddCardRequest(
                vendorId, "テスト建設株式会社", 15_000_000L, null
        );

        ResponseEntity<ApiResponse<QuoteCardDto>> resp =
                controller.addCard("ORGANIZATION", ORG_ID, kanbanId, ORG_ID, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        QuoteCardDto body = resp.getBody().getData();
        assertThat(body.id()).isNotNull();
        assertThat(body.stage()).isEqualTo("REQUESTED");
        assertThat(body.vendorId()).isEqualTo(vendorId);
        assertThat(body.complianceCheckStatus()).isEqualTo("PASSED");
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 4: カード追加（compliance=EXPIRED）→ COMPLIANCE_EXPIRED
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /cards（compliance=EXPIRED 業者）→ COMPLIANCE_EXPIRED 例外")
    void addCard_complianceExpired_throwsComplianceExpired() {
        UUID kanbanId = createKanban("昇降機保守工事", ORG_ID);
        Long expiredVendorId = insertVendor("期限切れ業者株式会社", "EXPIRED", ORG_ID);

        AddCardRequest req = new AddCardRequest(
                expiredVendorId, "期限切れ業者株式会社", null, null
        );

        assertThatThrownBy(() -> controller.addCard("ORGANIZATION", ORG_ID, kanbanId, ORG_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RepairPlanErrorCode.COMPLIANCE_EXPIRED);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 5: stage 遷移（REQUESTED → RECEIVED）→ 正常
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stage 遷移（REQUESTED → RECEIVED）→ 正常")
    void moveCard_requestedToReceived_succeeds() {
        UUID kanbanId = createKanban("電気設備更新工事", ORG_ID);
        UUID cardId = createCard(kanbanId, vendorId, ORG_ID);

        MoveCardRequest req = new MoveCardRequest("RECEIVED");
        ResponseEntity<ApiResponse<QuoteCardDto>> resp =
                controller.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getData().stage()).isEqualTo("RECEIVED");
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 6: stage 後戻り（RECEIVED → REQUESTED）→ INVALID_STAGE_TRANSITION
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stage 後戻り（RECEIVED → REQUESTED）→ INVALID_STAGE_TRANSITION")
    void moveCard_backwardTransition_throwsInvalidStageTransition() {
        UUID kanbanId = createKanban("消防設備点検", ORG_ID);
        UUID cardId = createCard(kanbanId, vendorId, ORG_ID);

        // REQUESTED → RECEIVED（前進）
        controller.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("RECEIVED"));
        em.flush();

        // RECEIVED → REQUESTED（後戻り: 不可）
        assertThatThrownBy(() ->
                controller.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID,
                        new MoveCardRequest("REQUESTED")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RepairPlanErrorCode.INVALID_STAGE_TRANSITION);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 7: SHORTLISTED → SELECTED + BID_VENDOR_SELECTED 監査ログ
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SHORTLISTED → SELECTED → 正常 + BID_VENDOR_SELECTED 監査ログ記録")
    void moveCard_shortlistedToSelected_logsAuditEvent() {
        UUID kanbanId = createKanban("駐車場整備工事", ORG_ID);
        UUID cardId = createCard(kanbanId, vendorId, ORG_ID);

        // REQUESTED → RECEIVED → UNDER_REVIEW → SHORTLISTED
        controller.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("RECEIVED"));
        controller.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("UNDER_REVIEW"));
        controller.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("SHORTLISTED"));
        em.flush();

        // SHORTLISTED → SELECTED
        ResponseEntity<ApiResponse<QuoteCardDto>> resp =
                controller.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("SELECTED"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getData().stage()).isEqualTo("SELECTED");

        em.flush();

        // 監査ログ: BID_VENDOR_SELECTED が非同期（@Async）で記録されるまで待機する。
        // @Transactional テストでは REPEATABLE_READ により @Async スレッドの commit が見えないため
        // 毎回 REQUIRES_NEW で新トランザクションを開いて検証する（Awaitility が条件成立まで複数回評価）。
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            TransactionTemplate newTx2 = new TransactionTemplate(txManager);
            newTx2.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Boolean auditRecorded2 = newTx2.execute(status ->
                    auditLogRepository.findAll().stream()
                            .anyMatch(log -> "BID_VENDOR_SELECTED".equals(log.getEventType())
                                    && ORG_ID.equals(log.getOrganizationId())));
            assertThat(auditRecorded2).isTrue();
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 8: IDOR — 非メンバーの別組織は COMMON_002（メンバーシップ guard 優先）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("IDOR: 非メンバーの別組織 ID でカンバン取得 → COMMON_002（メンバーシップ guard）")
    void getKanban_otherOrgNonMember_throwsForbidden() {
        UUID kanbanId = createKanban("管理委託更新相見積もり", ORG_ID);

        // setUp の userId は ORG_OTHER_ID に所属していないため、まずメンバーシップ guard で遮断される
        assertThatThrownBy(() -> controller.getKanban("ORGANIZATION", ORG_OTHER_ID, kanbanId, ORG_OTHER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 8b: IDOR — メンバーであっても別組織のカンバンは KANBAN_NOT_FOUND
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("IDOR: ORG_OTHER のメンバーが ORG_ID のカンバンを取得 → KANBAN_NOT_FOUND")
    void getKanban_memberOfOtherOrg_throwsKanbanNotFound() {
        UUID kanbanId = createKanban("給湯設備更新相見積もり", ORG_ID);

        // ORG_OTHER_ID 側を整備し、そこのメンバーに切り替え（メンバーシップ guard は通過させる）
        insertOrganization(ORG_OTHER_ID, "別組合");
        em.flush();
        switchToMember(ORG_OTHER_ID);

        // メンバーシップ guard は ORG_OTHER_ID で通過するが、kanban は ORG_ID 帰属のため NOT_FOUND
        assertThatThrownBy(() -> controller.getKanban("ORGANIZATION", ORG_OTHER_ID, kanbanId, ORG_OTHER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RepairPlanErrorCode.KANBAN_NOT_FOUND);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 9: visibility=HIDDEN → 非管理者は amount=null
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("visibility=HIDDEN: 管理者はフル表示（amount/vendorName が残る）")
    void getKanban_hiddenVisibility_adminSeesFull() {
        UUID kanbanId = createKanbanWithVisibility("清掃委託相見積もり", ORG_ID, "HIDDEN",
                Instant.now().minus(1, ChronoUnit.HOURS)); // 締切済み
        createCard(kanbanId, vendorId, ORG_ID);

        // 管理者（setUp で ADMIN 付与済の userId）でアクセス → マスクされない
        ResponseEntity<ApiResponse<QuoteKanbanDto>> resp =
                controller.getKanban("ORGANIZATION", ORG_ID, kanbanId, ORG_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        QuoteCardDto card = resp.getBody().getData().cards().get(0);
        // 管理者は HIDDEN でも業者名・金額を見られる
        assertThat(card.vendorNameSnapshot()).isEqualTo("テスト建設株式会社");
        assertThat(card.amount()).isEqualTo(10_000_000L);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 10: visibility=ANONYMIZED → rangeLabel 計算確認（Unit）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rangeLabel: 0円→100万未満, 1000万→1000-1100万台, 5000万→5000-5100万台")
    void rangeLabel_variousAmounts() {
        // Service の static メソッドを直接検証（内部ロジック確認）
        assertThat(com.mannschaft.app.repairplan.service.RepairPlanQuoteKanbanService
                .rangeLabel(null)).isNull();
        assertThat(com.mannschaft.app.repairplan.service.RepairPlanQuoteKanbanService
                .rangeLabel(0L)).isEqualTo("〜100万円未満");
        assertThat(com.mannschaft.app.repairplan.service.RepairPlanQuoteKanbanService
                .rangeLabel(500_000L)).isEqualTo("〜100万円未満"); // 50万円
        assertThat(com.mannschaft.app.repairplan.service.RepairPlanQuoteKanbanService
                .rangeLabel(10_000_000L)).isEqualTo("1000〜1100万円台"); // 1000万円
        assertThat(com.mannschaft.app.repairplan.service.RepairPlanQuoteKanbanService
                .rangeLabel(50_000_000L)).isEqualTo("5000〜5100万円台"); // 5000万円
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 11: 終端ステージ（SELECTED）からの遷移禁止
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("終端ステージ（SELECTED）から別ステージへの遷移 → INVALID_STAGE_TRANSITION")
    void moveCard_fromTerminalStage_throwsInvalidStageTransition() {
        UUID kanbanId = createKanban("バリアフリー改修工事", ORG_ID);
        UUID cardId = createCard(kanbanId, vendorId, ORG_ID);

        // 全遷移を経て SELECTED まで進める
        controller.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("RECEIVED"));
        controller.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("UNDER_REVIEW"));
        controller.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("SHORTLISTED"));
        controller.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID, new MoveCardRequest("SELECTED"));
        em.flush();

        // 終端ステージ SELECTED からさらに変更しようとすると拒否
        assertThatThrownBy(() ->
                controller.moveCard("ORGANIZATION", ORG_ID, cardId, ORG_ID,
                        new MoveCardRequest("UNDER_REVIEW")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RepairPlanErrorCode.INVALID_STAGE_TRANSITION);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 12: 監査ログ BID_CARD_CREATED が記録される
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("カード追加後 BID_CARD_CREATED 監査ログが記録される")
    void addCard_logsAuditEvent() {
        UUID kanbanId = createKanban("駐輪場改修工事", ORG_ID);

        AddCardRequest req = new AddCardRequest(vendorId, "テスト建設株式会社", 8_000_000L, null);
        controller.addCard("ORGANIZATION", ORG_ID, kanbanId, ORG_ID, req);
        em.flush();

        // 監査ログは非同期（@Async）のため記録されるまで待機する。
        // @Transactional テストでは REPEATABLE_READ により @Async スレッドの commit が見えないため
        // 毎回 REQUIRES_NEW で新トランザクションを開いて検証する。
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            TransactionTemplate newTx = new TransactionTemplate(txManager);
            newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Boolean auditRecorded = newTx.execute(status ->
                    auditLogRepository.findAll().stream()
                            .anyMatch(log -> "BID_CARD_CREATED".equals(log.getEventType())
                                    && ORG_ID.equals(log.getOrganizationId())));
            assertThat(auditRecorded).isTrue();
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 13: 監査ログ BID_DEADLINE_OPENED がカンバン作成時に記録される
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("カンバン作成（bidDeadlineAt あり）後 BID_DEADLINE_OPENED 監査ログが記録される")
    void createKanban_withBidDeadline_logsAuditEvent() {
        CreateKanbanRequest req = new CreateKanbanRequest(
                "入札締切ログテスト相見積もり",
                null,
                null,
                Instant.now().plus(14, ChronoUnit.DAYS), // bidDeadlineAt あり
                "FULL"
        );

        controller.createKanban("ORGANIZATION", ORG_ID, ORG_ID, req);
        em.flush();

        // 監査ログは非同期（@Async）のため記録されるまで待機する。
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            TransactionTemplate newTx = new TransactionTemplate(txManager);
            newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Boolean auditRecorded = newTx.execute(status ->
                    auditLogRepository.findAll().stream()
                            .anyMatch(log -> "BID_DEADLINE_OPENED".equals(log.getEventType())
                                    && ORG_ID.equals(log.getOrganizationId())));
            assertThat(auditRecorded).isTrue();
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 14: 監査ログ BID_DEADLINE_OPENED がカンバン更新時に記録される
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("カンバン更新（bidDeadlineAt 変更）後 BID_DEADLINE_OPENED 監査ログが記録される")
    void updateKanban_withBidDeadline_logsAuditEvent() {
        UUID kanbanId = createKanban("入札締切更新テスト", ORG_ID);
        em.flush();

        UpdateKanbanRequest updateReq = new UpdateKanbanRequest(
                null,
                Instant.now().plus(60, ChronoUnit.DAYS), // bidDeadlineAt を更新
                null,
                null
        );

        controller.updateKanban("ORGANIZATION", ORG_ID, kanbanId, ORG_ID, updateReq);
        em.flush();

        // 監査ログは非同期（@Async）のため記録されるまで待機する。
        // createKanban 時 + updateKanban 時の計 2 件以上の BID_DEADLINE_OPENED が記録される。
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            TransactionTemplate newTx = new TransactionTemplate(txManager);
            newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Boolean auditRecorded = newTx.execute(status ->
                    auditLogRepository.findAll().stream()
                            .filter(log -> "BID_DEADLINE_OPENED".equals(log.getEventType())
                                    && ORG_ID.equals(log.getOrganizationId()))
                            .count() >= 2);
            assertThat(auditRecorded).isTrue();
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 15: 一般メンバー（非管理者）は HIDDEN で業者名・金額がマスクされる
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("一般メンバー: visibility=HIDDEN → vendorName/amount が null にマスクされる")
    void getKanban_hiddenVisibility_memberSeesMasked() {
        // 管理者（setUp の userId）でカンバン＋カードを作成
        UUID kanbanId = createKanbanWithVisibility("管理員業務委託相見積もり", ORG_ID, "HIDDEN",
                Instant.now().minus(1, ChronoUnit.HOURS)); // 締切済み
        createCard(kanbanId, vendorId, ORG_ID);
        em.flush();

        // 一般メンバー（MEMBER ロールのみ）に切り替え
        switchToMember(ORG_ID);

        ResponseEntity<ApiResponse<QuoteKanbanDto>> resp =
                controller.getKanban("ORGANIZATION", ORG_ID, kanbanId, ORG_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        QuoteCardDto card = resp.getBody().getData().cards().get(0);
        // HIDDEN のため一般メンバーには業者名・金額が見えない
        assertThat(card.vendorNameSnapshot()).isNull();
        assertThat(card.amount()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 16: 一般メンバーは ANONYMIZED で匿名ラベル + レンジ表示になる
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("一般メンバー: visibility=ANONYMIZED → 業者A + 金額レンジ表示")
    void getKanban_anonymizedVisibility_memberSeesAnonymized() {
        // 締切済み（締切前マスクの影響を排除して ANONYMIZED 挙動を見る）
        UUID kanbanId = createKanbanWithVisibility("給排水設備更新相見積もり", ORG_ID, "ANONYMIZED",
                Instant.now().minus(1, ChronoUnit.HOURS));
        createCard(kanbanId, vendorId, ORG_ID); // amount = 10,000,000

        em.flush();
        switchToMember(ORG_ID);

        ResponseEntity<ApiResponse<QuoteKanbanDto>> resp =
                controller.getKanban("ORGANIZATION", ORG_ID, kanbanId, ORG_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        QuoteCardDto card = resp.getBody().getData().cards().get(0);
        // 匿名ラベルとレンジ表示、実金額は null
        assertThat(card.vendorNameSnapshot()).isEqualTo("業者A");
        assertThat(card.amount()).isNull();
        assertThat(card.amountRangeLabel()).isEqualTo("1000〜1100万円台");
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 17: 締切前は visibility=FULL でも一般メンバーには業者名がマスクされる
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("一般メンバー: 入札締切前は FULL でも業者名・金額がマスクされる")
    void getKanban_beforeDeadline_memberSeesMasked() {
        // FULL だが締切前（30日後）→ 締切前マスクが優先される
        UUID kanbanId = createKanbanWithVisibility("外構改修相見積もり", ORG_ID, "FULL",
                Instant.now().plus(30, ChronoUnit.DAYS));
        createCard(kanbanId, vendorId, ORG_ID);

        em.flush();
        switchToMember(ORG_ID);

        ResponseEntity<ApiResponse<QuoteKanbanDto>> resp =
                controller.getKanban("ORGANIZATION", ORG_ID, kanbanId, ORG_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        QuoteCardDto card = resp.getBody().getData().cards().get(0);
        assertThat(card.vendorNameSnapshot()).isNull();
        assertThat(card.amount()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 18: 非メンバーは getKanban で 403（COMMON_002）— 漏洩遮断の本丸
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("非メンバー: getKanban → COMMON_002（アクセス不可・漏洩遮断）")
    void getKanban_nonMember_throwsForbidden() {
        UUID kanbanId = createKanbanWithVisibility("機械式駐車場更新相見積もり", ORG_ID, "FULL",
                Instant.now().minus(1, ChronoUnit.HOURS));
        createCard(kanbanId, vendorId, ORG_ID);

        em.flush();
        switchToNonMember(); // 当該組織に所属しないユーザー

        assertThatThrownBy(() -> controller.getKanban("ORGANIZATION", ORG_ID, kanbanId, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 19: 非メンバーは listKanbans で 403（COMMON_002）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("非メンバー: listKanbans → COMMON_002（アクセス不可・漏洩遮断）")
    void listKanbans_nonMember_throwsForbidden() {
        createKanbanWithVisibility("防犯カメラ更新相見積もり", ORG_ID, "FULL",
                Instant.now().minus(1, ChronoUnit.HOURS));

        em.flush();
        switchToNonMember();

        assertThatThrownBy(() -> controller.listKanbans("ORGANIZATION", ORG_ID, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 20: 一般メンバーの listKanbans は 200 でマスク済み（締切前は全マスク）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("一般メンバー: listKanbans → 200 + マスク済み（FULL/締切前は業者名 null）")
    void listKanbans_member_returnsMasked() {
        UUID kanbanId = createKanbanWithVisibility("エレベーター更新相見積もり", ORG_ID, "FULL",
                Instant.now().plus(30, ChronoUnit.DAYS)); // 締切前
        createCard(kanbanId, vendorId, ORG_ID);

        em.flush();
        switchToMember(ORG_ID);

        ResponseEntity<ApiResponse<List<QuoteKanbanDto>>> resp =
                controller.listKanbans("ORGANIZATION", ORG_ID, ORG_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        QuoteKanbanDto kanban = resp.getBody().getData().stream()
                .filter(k -> k.id().equals(kanbanId))
                .findFirst()
                .orElseThrow();
        QuoteCardDto card = kanban.cards().get(0);
        // 締切前 FULL → 一般メンバーには業者名・金額が見えない
        assertThat(card.vendorNameSnapshot()).isNull();
        assertThat(card.amount()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────
    // ヘルパーメソッド
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 当該組織の一般メンバー（MEMBER ロールのみ・ADMIN なし）を新規作成し、
     * SecurityContext をそのユーザーに切り替える。
     */
    private void switchToMember(Long orgId) {
        Long memberUserId = insertUser("kanban-member-" + System.nanoTime() + "@example.jp");
        MembershipTestHelper.insertMembership(em, memberUserId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, memberUserId, "MEMBER", null, orgId);
        em.flush();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(memberUserId.toString(), null, List.of()));
    }

    /**
     * どの組織にも所属しないユーザーを新規作成し、SecurityContext をそのユーザーに切り替える。
     */
    private void switchToNonMember() {
        Long nonMemberUserId = insertUser("kanban-nonmember-" + System.nanoTime() + "@example.jp");
        em.flush();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(nonMemberUserId.toString(), null, List.of()));
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
                        + "VALUES (:email, :ln, :fn, 'カンバン 太郎', 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", encryptForTest("カンバン"))
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

    /**
     * vendors テーブルに業者を挿入し、ID を返す。
     */
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
                .setParameter("createdBy", userId)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM vendors WHERE name = :name AND scope_id = :scopeId ORDER BY id DESC LIMIT 1")
                .setParameter("name", name)
                .setParameter("scopeId", scopeId)
                .getSingleResult()).longValue();
    }

    /**
     * カンバンを作成してIDを返す（締切: 30日後・visibility=FULL）。
     */
    private UUID createKanban(String title, Long orgId) {
        return createKanbanWithVisibility(title, orgId, "FULL",
                Instant.now().plus(30, ChronoUnit.DAYS));
    }

    private UUID createKanbanWithVisibility(String title, Long orgId, String visibility,
                                             Instant deadline) {
        CreateKanbanRequest req = new CreateKanbanRequest(
                title, null, null, deadline, visibility
        );
        ResponseEntity<ApiResponse<QuoteKanbanDto>> resp =
                controller.createKanban("ORGANIZATION", orgId, orgId, req);
        return resp.getBody().getData().id();
    }

    /**
     * カードを作成してIDを返す。
     */
    private UUID createCard(UUID kanbanId, Long vendorId, Long orgId) {
        AddCardRequest req = new AddCardRequest(
                vendorId, "テスト建設株式会社", 10_000_000L, null
        );
        ResponseEntity<ApiResponse<QuoteCardDto>> resp =
                controller.addCard("ORGANIZATION", orgId, kanbanId, orgId, req);
        em.flush();
        return resp.getBody().getData().id();
    }
}
