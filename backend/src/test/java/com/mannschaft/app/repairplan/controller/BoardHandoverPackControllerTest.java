package com.mannschaft.app.repairplan.controller;

import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.repairplan.RepairPlanErrorCode;
import com.mannschaft.app.repairplan.dto.GenerateHandoverPackRequest;
import com.mannschaft.app.repairplan.dto.HandoverPackDownloadResponse;
import com.mannschaft.app.repairplan.dto.HandoverPackDto;
import com.mannschaft.app.repairplan.entity.BoardHandoverPack;
import com.mannschaft.app.repairplan.entity.TeamMemberTerm;
import com.mannschaft.app.repairplan.repository.BoardHandoverPackRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BoardHandoverPackController} 統合テスト（F08.8 Phase 5）。
 *
 * <h3>検証観点（12件以上）</h3>
 * <ol>
 *   <li>generatePack_asAdmin_returns201</li>
 *   <li>generatePack_asMember_returns403</li>
 *   <li>generatePack_noActiveTerm_returns404</li>
 *   <li>generatePack_anonymizedPii_returns201</li>
 *   <li>listPacks_asMember_returnsOkWithPacks</li>
 *   <li>listPacks_outsideOrg_returns403</li>
 *   <li>getDownloadUrl_asAdmin_returnsSignedUrl</li>
 *   <li>getDownloadUrl_outsideOrg_returns403</li>
 *   <li>getDownloadUrl_packNotFound_returns404</li>
 *   <li>deletePack_asAdmin_returns204</li>
 *   <li>deletePack_asMember_returns403</li>
 *   <li>generatePack_logsAuditEvent（PACK_GENERATED）</li>
 * </ol>
 *
 * <p>R2 アップロード / PDF 生成は {@link AbstractRepairPlanPhase5IntegrationTest} で mock 化済み。</p>
 */
@DisplayName("BoardHandoverPackController 統合テスト（F08.8 Phase 5）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BoardHandoverPackControllerTest extends AbstractRepairPlanPhase5IntegrationTest {

    @Autowired
    private BoardHandoverPackController controller;

    @Autowired
    private BoardHandoverPackRepository packRepository;

    @Autowired
    private TeamMemberTermRepository termRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    /** テスト組織 ID（シードと衝突しない大きな値）。 */
    private static final Long ORG_ID = 989_001L;

    /** 別組織 ID（IDOR 検証用）。 */
    private static final Long ORG_OTHER_ID = 989_002L;

    private Long adminUserId;
    private Long memberUserId;

    @BeforeEach
    void setUp() {
        mockDependenciesNoop();

        adminUserId = insertUser("pack-admin-" + System.nanoTime() + "@example.jp");
        memberUserId = insertUser("pack-member-" + System.nanoTime() + "@example.jp");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminUserId.toString(), null, List.of()));

        // adminUserId: メンバーシップ登録 + ADMIN 権限ロール付与
        MembershipTestHelper.insertMembership(em, adminUserId, ScopeType.ORGANIZATION, ORG_ID, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminUserId, "ADMIN", null, ORG_ID);
        // memberUserId: メンバーシップ登録のみ（MEMBER 権限）
        MembershipTestHelper.insertMembership(em, memberUserId, ScopeType.ORGANIZATION, ORG_ID, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, memberUserId, "MEMBER", null, ORG_ID);

        insertOrganization(ORG_ID, "申し送りテスト組合");

        em.flush();
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 1: ADMIN が generatePack → 201
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /handover-packs (ADMIN) → 201 + HandoverPackDto")
    void generatePack_asAdmin_returns201() {
        // アクティブな任期を準備
        UUID termId = insertActiveTerm(adminUserId, "ORGANIZATION", ORG_ID, ORG_ID);
        em.flush();

        GenerateHandoverPackRequest req = new GenerateHandoverPackRequest(termId, "テスト引き継ぎメモ", "STANDARD");

        ResponseEntity<ApiResponse<HandoverPackDto>> resp =
                controller.generatePack("ORGANIZATION", ORG_ID, ORG_ID, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        HandoverPackDto body = resp.getBody().getData();
        assertThat(body.id()).isNotNull();
        assertThat(body.status()).isEqualTo("READY");
        assertThat(body.piiLevel()).isEqualTo("STANDARD");
        assertThat(body.organizationId()).isEqualTo(ORG_ID);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 2: MEMBER が generatePack → 403
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /handover-packs (MEMBER) → 403")
    void generatePack_asMember_returns403() {
        // MEMBER として認証
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(memberUserId.toString(), null, List.of()));

        GenerateHandoverPackRequest req = new GenerateHandoverPackRequest(UUID.randomUUID(), null, "STANDARD");

        assertThatThrownBy(() -> controller.generatePack("ORGANIZATION", ORG_ID, ORG_ID, req))
                .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 3: アクティブな任期がない場合 → 404
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /handover-packs (アクティブ任期なし) → TERM_NOT_FOUND")
    void generatePack_noActiveTerm_returns404() {
        // 存在しない任期 UUID を指定する
        GenerateHandoverPackRequest req = new GenerateHandoverPackRequest(UUID.randomUUID(), null, "STANDARD");

        assertThatThrownBy(() -> controller.generatePack("ORGANIZATION", ORG_ID, ORG_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RepairPlanErrorCode.TERM_NOT_FOUND);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 4: piiLevel = ANONYMIZED で生成 → 201
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /handover-packs (piiLevel=ANONYMIZED) → 201 + piiLevel=ANONYMIZED")
    void generatePack_anonymizedPii_returns201() {
        UUID termId = insertActiveTerm(adminUserId, "ORGANIZATION", ORG_ID, ORG_ID);
        em.flush();

        GenerateHandoverPackRequest req = new GenerateHandoverPackRequest(termId, null, "ANONYMIZED");

        ResponseEntity<ApiResponse<HandoverPackDto>> resp =
                controller.generatePack("ORGANIZATION", ORG_ID, ORG_ID, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().getData().piiLevel()).isEqualTo("ANONYMIZED");
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 5: MEMBER がパック一覧取得 → 200 + 一覧
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /handover-packs (MEMBER) → 200 + 一覧")
    void listPacks_asMember_returnsOkWithPacks() {
        // パックを先に作成（ADMIN として）
        UUID termId = insertActiveTerm(adminUserId, "ORGANIZATION", ORG_ID, ORG_ID);
        em.flush();
        GenerateHandoverPackRequest req = new GenerateHandoverPackRequest(termId, null, "STANDARD");
        controller.generatePack("ORGANIZATION", ORG_ID, ORG_ID, req);
        em.flush();

        // MEMBER に切り替えてリスト取得
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(memberUserId.toString(), null, List.of()));

        ResponseEntity<ApiResponse<List<HandoverPackDto>>> resp =
                controller.listPacks("ORGANIZATION", ORG_ID, ORG_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getData()).isNotEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 6: 別組織からパック一覧取得 → 403
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /handover-packs (別組織) → 403")
    void listPacks_outsideOrg_returns403() {
        // ORG_OTHER_ID のメンバーシップは付与しない
        assertThatThrownBy(() -> controller.listPacks("ORGANIZATION", ORG_OTHER_ID, ORG_OTHER_ID))
                .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 7: ADMIN がダウンロード URL 取得 → 200 + 署名付き URL
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /handover-packs/{packId}/download (ADMIN) → 200 + 署名付き URL")
    void getDownloadUrl_asAdmin_returnsSignedUrl() {
        UUID termId = insertActiveTerm(adminUserId, "ORGANIZATION", ORG_ID, ORG_ID);
        em.flush();
        ResponseEntity<ApiResponse<HandoverPackDto>> genResp =
                controller.generatePack("ORGANIZATION", ORG_ID, ORG_ID,
                        new GenerateHandoverPackRequest(termId, null, "STANDARD"));
        UUID packId = genResp.getBody().getData().id();
        em.flush();

        ResponseEntity<ApiResponse<HandoverPackDownloadResponse>> resp =
                controller.getDownloadUrl("ORGANIZATION", ORG_ID, packId, ORG_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        HandoverPackDownloadResponse body = resp.getBody().getData();
        assertThat(body.downloadUrl()).isNotBlank();
        assertThat(body.expiresAt()).isAfter(LocalDateTime.now());
        assertThat(body.watermarkFor()).contains("requesterId=");
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 8: 別組織からダウンロード URL 取得 → 403 / 404
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /handover-packs/{packId}/download (別組織) → PACK_NOT_FOUND")
    void getDownloadUrl_outsideOrg_returns403() {
        // パックは ORG_ID で作成。ORG_OTHER_ID で取得しようとすると PACK_NOT_FOUND
        UUID nonExistentPackId = UUID.randomUUID();
        assertThatThrownBy(() ->
                controller.getDownloadUrl("ORGANIZATION", ORG_OTHER_ID, nonExistentPackId, ORG_OTHER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RepairPlanErrorCode.PACK_NOT_FOUND);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 9: 存在しないパックのダウンロード → PACK_NOT_FOUND
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /handover-packs/{packId}/download (存在しない) → PACK_NOT_FOUND")
    void getDownloadUrl_packNotFound_returns404() {
        UUID nonExistentPackId = UUID.randomUUID();
        assertThatThrownBy(() ->
                controller.getDownloadUrl("ORGANIZATION", ORG_ID, nonExistentPackId, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RepairPlanErrorCode.PACK_NOT_FOUND);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 10: ADMIN がパック削除 → 204
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /handover-packs/{packId} (ADMIN) → 204")
    void deletePack_asAdmin_returns204() {
        UUID termId = insertActiveTerm(adminUserId, "ORGANIZATION", ORG_ID, ORG_ID);
        em.flush();
        ResponseEntity<ApiResponse<HandoverPackDto>> genResp =
                controller.generatePack("ORGANIZATION", ORG_ID, ORG_ID,
                        new GenerateHandoverPackRequest(termId, null, "STANDARD"));
        UUID packId = genResp.getBody().getData().id();
        em.flush();

        ResponseEntity<Void> resp =
                controller.deletePack("ORGANIZATION", ORG_ID, packId, ORG_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 論理削除後は取得できない
        assertThatThrownBy(() ->
                controller.getDownloadUrl("ORGANIZATION", ORG_ID, packId, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RepairPlanErrorCode.PACK_NOT_FOUND);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 11: MEMBER がパック削除 → 403
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /handover-packs/{packId} (MEMBER) → 403")
    void deletePack_asMember_returns403() {
        // 先にパックを作成（ADMIN として）
        UUID termId = insertActiveTerm(adminUserId, "ORGANIZATION", ORG_ID, ORG_ID);
        em.flush();
        ResponseEntity<ApiResponse<HandoverPackDto>> genResp =
                controller.generatePack("ORGANIZATION", ORG_ID, ORG_ID,
                        new GenerateHandoverPackRequest(termId, null, "STANDARD"));
        UUID packId = genResp.getBody().getData().id();
        em.flush();

        // MEMBER に切り替えて削除試行 → 403
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(memberUserId.toString(), null, List.of()));

        assertThatThrownBy(() ->
                controller.deletePack("ORGANIZATION", ORG_ID, packId, ORG_ID))
                .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // テスト 12: generatePack で PACK_GENERATED 監査ログが記録される
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("generatePack 後 PACK_GENERATED 監査ログが記録される")
    void generatePack_logsAuditEvent() throws InterruptedException {
        UUID termId = insertActiveTerm(adminUserId, "ORGANIZATION", ORG_ID, ORG_ID);
        em.flush();

        controller.generatePack("ORGANIZATION", ORG_ID, ORG_ID,
                new GenerateHandoverPackRequest(termId, null, "STANDARD"));
        em.flush();

        // 監査ログは非同期（@Async）のため少し待つ
        Thread.sleep(500);
        TransactionTemplate newTx = new TransactionTemplate(txManager);
        newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Boolean auditRecorded = newTx.execute(status ->
                auditLogRepository.findAll().stream()
                        .anyMatch(log -> "PACK_GENERATED".equals(log.getEventType())
                                && ORG_ID.equals(log.getOrganizationId())));
        assertThat(auditRecorded).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────
    // ヘルパーメソッド
    // ─────────────────────────────────────────────────────────────────────

    private Long insertUser(String email) {
        em.createNativeQuery(
                "INSERT INTO users ("
                        + "email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, "
                        + "created_at, updated_at) "
                        + "VALUES (:email, :ln, :fn, 'パック テスト', 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", encryptForTest("パック"))
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

    /**
     * アクティブな理事任期を挿入し、生成した UUID を返す。
     */
    private UUID insertActiveTerm(Long userId, String scopeType, Long scopeId, Long organizationId) {
        TeamMemberTerm term = TeamMemberTerm.builder()
                .organizationId(organizationId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .userId(userId)
                .roleLabel("理事長")
                .termStart(LocalDate.now().minusMonths(6))
                .termEnd(LocalDate.now().plusMonths(6))
                .isActive(true)
                .build();
        TeamMemberTerm saved = termRepository.save(term);
        return saved.getId();
    }
}
