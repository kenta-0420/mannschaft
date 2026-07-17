package com.mannschaft.app.circulation;

import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave4: circulation 押印済み証跡 PDF エクスポート（{@code CirculationExportController}/
 * {@code CirculationExportService}）の per-scope 認可化に関する API 契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave4 可視性の独自迂回是正②節）・{@code CirculationExportService}
 * （{@code assertCanAccessExport} / {@code isScopeAdmin}）・{@code CirculationService}
 * （{@code checkScopeAdminAccess} 金型）。</p>
 *
 * <p><b>是正前の脆弱性</b>: {@code CirculationExportController#currentUserHasAdminRole()} は
 * JWT の {@code ROLE_ADMIN}（スコープを問わない文字列一致。どこか 1 つのチーム/組織で ADMIN で
 * あれば付与される）保持有無を判定し、{@code CirculationExportService#assertCanAccessExport}
 * 冒頭の {@code if (isAdmin) return;} で全スコープ無条件通過させていた。これにより、
 * 自チームの ADMIN であるユーザーが、無関係な他団体の COMPLETED 回覧文書の押印済み証跡 PDF
 * （受信者氏名・押印を含む）を無認可 DL できる BOLA / 権限昇格だった。</p>
 *
 * <p><b>是正後</b>: グローバル {@code ROLE_ADMIN} 判定を Controller から完全に除去し、
 * Service 側で「作成者 OR 受信者 OR 当該文書スコープの ADMIN/DEPUTY_ADMIN（SystemAdmin 含む）」
 * を per-scope に判定する。本テストは「他チームの ADMIN（旧実装ならグローバル ROLE_ADMIN を保持）」
 * が対象外スコープの文書エクスポートにアクセスできないことを実証する 3 象限テストを行う:</p>
 * <ul>
 *   <li>当該スコープの ADMIN → 許可（正当な per-scope 管理者）</li>
 *   <li>受信者/作成者 → 許可（既存挙動の非回帰確認）</li>
 *   <li>他スコープの ADMIN・部外者 → 拒否（BOLA 根治の確認）</li>
 * </ul>
 *
 * <p>StorageService（R2/S3）は外部依存のため {@code @MockitoBean} でモックする。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("circulation 押印済み証跡PDFエクスポート per-scope認可 API 契約テスト（認可根治 Wave4）")
class CirculationExportScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    /**
     * R2/S3 は外部依存のため mock（generateDownloadUrl で使用）。
     *
     * <p>認可根治 Wave3-B9 と同じ理由（{@code BudgetFlatWriteScopeContractIT} で先行対処済）で、
     * interface 型 {@code @MockitoBean StorageService} ではなく具象型
     * {@code @MockitoBean R2StorageService}（bean 名 r2StorageService）で置換する。
     * interface 型で置換すると bean が {@code StorageService$MockitoMock} 型にすり替わり、
     * 同一 context 内で具象 {@code R2StorageService} を注入する消費者（{@code CirculationService}
     * 等）の DI が型不一致で壊れて ApplicationContext 起動が失敗するため。
     * 具象型でモックすれば interface 消費者・具象型消費者の双方を満たす。</p>
     */
    @MockitoBean
    private R2StorageService storageService;

    private Long teamAId;
    private Long teamBId;
    /** teamA の正当な ADMIN。 */
    private Long adminAId;
    /** teamB の ADMIN（旧実装ならグローバル ROLE_ADMIN 保持相当。teamA の文書に対しては部外者）。 */
    private Long adminBId;
    /** teamA 所属の文書作成者。 */
    private Long creatorId;
    /** 文書の受信者（teamA の scope membership は持たない）。 */
    private Long recipientId;
    /** どのスコープにも所属しない完全な部外者。 */
    private Long outsiderId;

    /** teamA スコープの COMPLETED 文書 + 生成済エクスポート（export_status=COMPLETED）。 */
    private Long documentId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("CIRCEXPORT認可契約チームA");
        teamBId = insertTeam("CIRCEXPORT認可契約チームB");

        adminAId = insertUser("circexport-authz-admin-a@example.com");
        adminBId = insertUser("circexport-authz-admin-b@example.com");
        creatorId = insertUser("circexport-authz-creator@example.com");
        recipientId = insertUser("circexport-authz-recipient@example.com");
        outsiderId = insertUser("circexport-authz-outsider@example.com");

        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, creatorId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        documentId = insertCompletedExportDocument(teamAId, creatorId);
        insertRecipient(documentId, recipientId);

        em.flush();
        em.clear();

        given(storageService.generateDownloadUrl(anyString(), any(Duration.class)))
                .willReturn("https://r2.example.com/circexport-signed-url");
    }

    // ═════════════════════════════════════════════════════════════════════
    // requestExport: GET /api/v1/circulations/{documentId}/export
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("エクスポート要求(requestExport)")
    class RequestExport {

        @Test
        @DisplayName("当該スコープADMIN(adminA)は302（正当な per-scope 管理者）")
        void 当該スコープADMINは302() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/circulations/{documentId}/export", documentId))
                    .andExpect(status().isFound());
        }

        @Test
        @DisplayName("作成者本人は302")
        void 作成者本人は302() throws Exception {
            setAuthentication(creatorId);
            mockMvc.perform(get("/api/v1/circulations/{documentId}/export", documentId))
                    .andExpect(status().isFound());
        }

        @Test
        @DisplayName("受信者は302")
        void 受信者は302() throws Exception {
            setAuthentication(recipientId);
            mockMvc.perform(get("/api/v1/circulations/{documentId}/export", documentId))
                    .andExpect(status().isFound());
        }

        @Test
        @DisplayName("他チームADMIN(adminB)は403（認可根治 Wave4: グローバルADMIN迂回封止の確認）")
        void 他チームADMINは403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/circulations/{documentId}/export", documentId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("部外者は403")
        void 部外者は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/circulations/{documentId}/export", documentId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // getStatus: GET /api/v1/circulations/{documentId}/export/status
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("エクスポート状況確認(getStatus)")
    class GetStatus {

        @Test
        @DisplayName("当該スコープADMIN(adminA)は200 + url入り")
        void 当該スコープADMINは200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/circulations/{documentId}/export/status", documentId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.data.url").value("https://r2.example.com/circexport-signed-url"));
        }

        @Test
        @DisplayName("受信者は200 + url入り")
        void 受信者は200() throws Exception {
            setAuthentication(recipientId);
            mockMvc.perform(get("/api/v1/circulations/{documentId}/export/status", documentId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.url").value("https://r2.example.com/circexport-signed-url"));
        }

        @Test
        @DisplayName("他チームADMIN(adminB)は403（認可根治 Wave4: グローバルADMIN迂回封止の確認）")
        void 他チームADMINは403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/circulations/{documentId}/export/status", documentId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("部外者は403")
        void 部外者は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/circulations/{documentId}/export/status", documentId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /**
     * circulation_documents へ COMPLETED 文書 + 生成済エクスポート(export_status=COMPLETED)を
     * 1 行 INSERT する（scopeType は TEAM 固定）。
     *
     * <p>test profile は ddl-auto=create（Flyway 無効）でスキーマを Entity から生成するため、
     * Hibernate は {@code @Column(nullable=false)} を SQL DEFAULT 無しの NOT NULL 列として作る。
     * 生 SQL INSERT では NOT NULL 列を全て明示的に埋める必要がある
     * （{@code CirculationWriteAclScopeContractIT#insertDocument} と同じ理由）。</p>
     */
    private Long insertCompletedExportDocument(Long scopeId, Long createdBy) {
        String title = "認可契約エクスポート文書 " + System.nanoTime();
        String fileKey = "circulation/exports/authz-contract/" + System.nanoTime() + ".pdf";
        em.createNativeQuery(
                        "INSERT INTO circulation_documents "
                                + "(scope_type, scope_id, created_by, title, body, "
                                + "circulation_mode, sequential_count, status, priority, "
                                + "reminder_enabled, reminder_interval_hours, stamp_display_style, "
                                + "total_recipient_count, stamped_count, attachment_count, comment_count, "
                                + "export_status, export_file_key, export_requested_at, export_completed_at, "
                                + "created_at, updated_at) "
                                + "VALUES ('TEAM', :scopeId, :createdBy, :title, '本文', "
                                + "'SIMULTANEOUS', 0, 'COMPLETED', 'NORMAL', "
                                + "0, 24, 'STANDARD', "
                                + "1, 1, 0, 0, "
                                + "'COMPLETED', :fileKey, NOW(), NOW(), "
                                + "NOW(), NOW())")
                .setParameter("scopeId", scopeId)
                .setParameter("createdBy", createdBy)
                .setParameter("title", title)
                .setParameter("fileKey", fileKey)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM circulation_documents WHERE title = :title")
                        .setParameter("title", title)
                        .getSingleResult()).longValue();
    }

    /** circulation_recipients へ受信者を 1 行 INSERT する。 */
    private void insertRecipient(Long documentId, Long userId) {
        em.createNativeQuery(
                        "INSERT INTO circulation_recipients "
                                + "(document_id, user_id, sort_order, status, tilt_angle, is_flipped, "
                                + "created_at, updated_at) "
                                + "VALUES (:docId, :userId, 0, 'STAMPED', 0, 0, NOW(), NOW())")
                .setParameter("docId", documentId)
                .setParameter("userId", userId)
                .executeUpdate();
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
                                + "VALUES (:email, 'CIRCEXPORT契約', 'テスト', 'CIRCEXPORT契約テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('circexport-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
