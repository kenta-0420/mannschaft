package com.mannschaft.app.joinrequest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.joinrequest.entity.JoinRequestEntity;
import com.mannschaft.app.joinrequest.entity.JoinRequestStatus;
import com.mannschaft.app.joinrequest.repository.JoinRequestRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 柱③-A「MEMBER 参加申請（join request）」の HTTP 契約テスト（PR #3139 検分P2是正）。
 *
 * <p>既存の {@code JoinRequestServiceTest}（Mockito 単体）は Service ロジックのみを検証しており、
 * 実 HTTP 経由の二層認可・IDOR・存在秘匿・Flyway 実スキーマの UNIQUE 制約/照合順序は
 * 未検証だった（検分 P2 指摘）。本 IT はそれらを実 MySQL + 実 Flyway スキーマで固定する。</p>
 *
 * <p>金型: {@code TodoScopeContractIT} / {@code VillageSelfScopeContractIT}
 * （{@code AbstractMySqlIntegrationTest} + {@code @AutoConfigureMockMvc(addFilters=false)} +
 * {@code @Transactional} + 手動 {@code SecurityContext}）。越境 401/403/404 は Service 層の
 * アプリケーション例外（{@code SecurityUtils} の {@code COMMON_000} → 401 /
 * {@code AccessControlService} の {@code COMMON_002} → 403 /
 * {@code JoinRequestErrorCode.SCOPE_NOT_FOUND,REQUEST_NOT_FOUND} → 404）として発生するため、
 * Spring Security フィルタを無効化していても検証できる。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("join-request HTTP契約テスト（認可・IDOR・存在秘匿・Flywayスキーマ）")
class JoinRequestScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JoinRequestRepository joinRequestRepository;

    @PersistenceContext
    private EntityManager em;

    private Long publicTeamAId;
    private Long publicTeamBId;
    private Long privateTeamId;
    private Long provisionedTeamId;
    private Long archivedTeamId;

    private Long adminTeamAId;   // team A の ADMIN（user_roles）
    private Long adminTeamBId;   // team B の ADMIN（team A に対しては非管理者＝越境攻撃者）
    private Long applicantId;    // 参加申請者（どこにも所属しない）
    private Long memberTeamAId;  // team A の既存 MEMBER（申請不可）

    @BeforeEach
    void setUp() {
        String uniq = Long.toString(System.nanoTime(), 36);
        publicTeamAId = insertTeam("JRAUTHZ 公開チームA", "jr-ta-" + uniq, "PUBLIC", "ACTIVE", false);
        publicTeamBId = insertTeam("JRAUTHZ 公開チームB", "jr-tb-" + uniq, "PUBLIC", "ACTIVE", false);
        privateTeamId = insertTeam("JRAUTHZ 非公開チーム", "jr-tp-" + uniq, "MEMBERS_AND_ABOVE", "ACTIVE", false);
        provisionedTeamId = insertTeam("JRAUTHZ 事前作成チーム", "jr-tv-" + uniq, "PUBLIC", "PROVISIONED", false);
        archivedTeamId = insertTeam("JRAUTHZ 廃止チーム", "jr-tz-" + uniq, "PUBLIC", "ACTIVE", true);

        adminTeamAId = insertUser("jrauthz-admin-team-a-" + uniq + "@example.com");
        adminTeamBId = insertUser("jrauthz-admin-team-b-" + uniq + "@example.com");
        applicantId = insertUser("jrauthz-applicant-" + uniq + "@example.com");
        memberTeamAId = insertUser("jrauthz-member-team-a-" + uniq + "@example.com");

        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", publicTeamAId, null);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", publicTeamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, publicTeamAId, RoleKind.MEMBER);

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 認可の基本形（未認証401・無権限403）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 基本認可")
    class BasicAuthz {

        @Test
        @DisplayName("未認証で参加申請すると401")
        void create_未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/teams/{teamId}/join-requests", publicTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("未認証で一覧参照すると401")
        void list_未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/teams/{teamId}/join-requests", publicTeamAId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("ADMIN権限の無いユーザーが一覧参照すると403")
        void list_権限なしは403() throws Exception {
            setAuth(applicantId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/join-requests", publicTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("ADMIN権限の無いユーザーが承認しようとすると403")
        void approve_権限なしは403() throws Exception {
            JoinRequestEntity pending = persistPendingRequest(publicTeamAId, applicantId);
            setAuth(applicantId); // 自分自身にはADMIN権限が無い
            mockMvc.perform(post("/api/v1/teams/{teamId}/join-requests/{id}/approve",
                            publicTeamAId, pending.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of())))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. IDOR（別スコープのADMINによる越境操作）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. IDOR（別スコープ越境）")
    class Idor {

        @Test
        @DisplayName("チームBのADMINがチームAの申請IDをチームBのURLで承認しようとすると404（不在と同一コード）")
        void approve_別チームの申請IDは404() throws Exception {
            JoinRequestEntity requestOnTeamA = persistPendingRequest(publicTeamAId, applicantId);

            setAuth(adminTeamBId); // 自分のチームBに対してはADMINだが、対象はチームAの申請
            mockMvc.perform(post("/api/v1/teams/{teamId}/join-requests/{id}/approve",
                            publicTeamBId, requestOnTeamA.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of())))
                    .andExpect(status().isNotFound());

            // 越境操作がPENDINGを書き換えていないことも確認する
            assertThat(joinRequestRepository.findById(requestOnTeamA.getId()))
                    .get().extracting(JoinRequestEntity::getStatus).isEqualTo(JoinRequestStatus.PENDING);
        }

        @Test
        @DisplayName("チームAのADMINがチームAのURLへチームBの申請IDを指定すると404（scope不一致）")
        void approve_URLのteamIdと異なるscopeの申請は404() throws Exception {
            JoinRequestEntity requestOnTeamB = persistPendingRequest(publicTeamBId, applicantId);

            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/join-requests/{id}/approve",
                            publicTeamAId, requestOnTeamB.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of())))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 存在秘匿（不存在／PRIVATE／PROVISIONED／アーカイブは同一404）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. 存在秘匿（不存在/PRIVATE/PROVISIONED/アーカイブは全て同一404）")
    class ExistenceConcealment {

        @Test
        @DisplayName("不存在チームへの申請は404")
        void create_不存在チームは404() throws Exception {
            setAuth(applicantId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/join-requests", 999_999_999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("JOIN_REQUEST_001"));
        }

        @Test
        @DisplayName("PRIVATE（非PUBLIC）チームへの申請は不存在と同一の404コード")
        void create_非公開チームは同一404コード() throws Exception {
            setAuth(applicantId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/join-requests", privateTeamId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("JOIN_REQUEST_001"));
        }

        @Test
        @DisplayName("PROVISIONED（承諾前）チームへの申請は不存在と同一の404コード")
        void create_プロビジョニング中は同一404コード() throws Exception {
            setAuth(applicantId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/join-requests", provisionedTeamId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("JOIN_REQUEST_001"));
        }

        @Test
        @DisplayName("アーカイブ済みチームへの申請は不存在と同一の404コード")
        void create_アーカイブ済みは同一404コード() throws Exception {
            setAuth(applicantId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/join-requests", archivedTeamId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("JOIN_REQUEST_001"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 正常系・冪等性（実HTTP経由）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. 正常系・冪等性")
    class HappyPath {

        @Test
        @DisplayName("PUBLICなACTIVEチームへ申請できる（201）")
        void create_成功() throws Exception {
            setAuth(applicantId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/join-requests", publicTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("message", "よろしくお願いします"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("PENDING"));
        }

        @Test
        @DisplayName("既にメンバーであれば409")
        void create_既にメンバーは409() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/join-requests", publicTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("JOIN_REQUEST_002"));
        }

        @Test
        @DisplayName("PENDING中の重複申請は新規作成せず既存申請をHTTP経由でも冪等に返す")
        void create_PENDING重複はHTTP経由でも冪等() throws Exception {
            setAuth(applicantId);
            String firstBody = mockMvc.perform(post("/api/v1/teams/{teamId}/join-requests", publicTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of())))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            mockMvc.perform(post("/api/v1/teams/{teamId}/join-requests", publicTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of())))
                    .andExpect(status().isCreated());

            assertThat(joinRequestRepository.findByTeamIdAndRequesterUserIdAndStatus(
                    publicTeamAId, applicantId, JoinRequestStatus.PENDING)).isPresent();
            long pendingCount = joinRequestRepository
                    .findByTeamIdAndRequesterUserIdOrderByCreatedAtDesc(publicTeamAId, applicantId)
                    .stream().filter(r -> r.getStatus() == JoinRequestStatus.PENDING).count();
            assertThat(pendingCount).as("PENDING中の重複申請でDB行が増えていないこと").isEqualTo(1);
            assertThat(firstBody).contains("PENDING");
        }

        @Test
        @DisplayName("ADMINが承認するとAPPROVEDになる")
        void approve_成功() throws Exception {
            JoinRequestEntity pending = persistPendingRequest(publicTeamAId, applicantId);

            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/join-requests/{id}/approve",
                            publicTeamAId, pending.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("APPROVED"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4.5 自己スコープ性（SelfScopedEndpointMarkerGuardTest 対応契約テスト）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * {@code JoinRequestController#listMineForTeam} /
     * {@code JoinRequestController#listMineForOrganization} の自己スコープ性を固定する。
     *
     * <p>両エンドポイントはパス・クエリで対象ユーザーを一切受け取らず、認証済みユーザーIDのみで
     * 絞り込む（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} 宣言）。
     * 本テストは、他人の申請を作った状態で自分の一覧を取得しても他人の申請が混入しないこと、
     * および自分の申請だけが返ることを実 HTTP 経由で固定する。</p>
     */
    @Nested
    @DisplayName("4.5 自己スコープ性（JoinRequestController#listMineForTeam / #listMineForOrganization）")
    class SelfScope {

        @Test
        @DisplayName("JoinRequestController#listMineForTeam は認証ユーザー自身の申請のみを返し、他人の申請は混入しない")
        void listMineForTeam_自分の申請のみ返る() throws Exception {
            JoinRequestEntity mine = persistPendingRequest(publicTeamAId, applicantId);
            // 他人（adminTeamBId）が同じチームAへ申請した行。自分の一覧に混入してはならない。
            persistPendingRequest(publicTeamAId, adminTeamBId);

            setAuth(applicantId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/join-requests/me", publicTeamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(mine.getId().toString()));
        }

        @Test
        @DisplayName("JoinRequestController#listMineForOrganization は認証ユーザー自身の申請のみを返す（他人の識別子を指定する余地が無い）")
        void listMineForOrganization_自分の申請のみ返る() throws Exception {
            Long orgId = insertOrganization("JRAUTHZ 公開組織-" + System.nanoTime());
            JoinRequestEntity mine = joinRequestRepository.saveAndFlush(JoinRequestEntity.builder()
                    .organizationId(orgId)
                    .requesterUserId(applicantId)
                    .status(JoinRequestStatus.PENDING)
                    .build());
            // 他人（adminTeamBId）が同じ組織へ申請した行。自分の一覧に混入してはならない。
            joinRequestRepository.saveAndFlush(JoinRequestEntity.builder()
                    .organizationId(orgId)
                    .requesterUserId(adminTeamBId)
                    .status(JoinRequestStatus.PENDING)
                    .build());

            setAuth(applicantId);
            mockMvc.perform(get("/api/v1/organizations/{organizationId}/join-requests/me", orgId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(mine.getId().toString()));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. Flyway 実スキーマ検証（UNIQUE制約・照合順序）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. Flyway実スキーマ（UNIQUE制約・照合順序）")
    class SchemaContract {

        @Test
        @DisplayName("join_requestsテーブルはTEAM/ORGANIZATION双方でUNIQUE(scope,requester,status)を持つ"
                + "（検分P1-1: 行内コメントに巻き込まれた無効化の再発防止）")
        void uniqueConstraints_TEAMとORGANIZATION双方が有効() {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                            "SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) "
                                    + "FROM information_schema.STATISTICS "
                                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'join_requests' "
                                    + "  AND INDEX_NAME IN ('uk_jr_team_pending', 'uk_jr_org_pending') "
                                    + "GROUP BY INDEX_NAME, NON_UNIQUE")
                    .getResultList();

            assertThat(rows).as("uk_jr_team_pending / uk_jr_org_pending の両方が実スキーマに存在すること")
                    .hasSize(2);
            for (Object[] row : rows) {
                String indexName = (String) row[0];
                Number nonUnique = (Number) row[1];
                String columns = (String) row[2];
                assertThat(nonUnique.intValue())
                        .as(indexName + " が UNIQUE として有効であること（行内コメント巻き込みで無効化されていないか）")
                        .isEqualTo(0);
                assertThat(columns).contains("requester_user_id").contains("status");
            }
        }

        @Test
        @DisplayName("TEAM側UNIQUE制約はDB層でも同時PENDING重複を1件だけ許容する")
        void uniqueConstraint_TEAM側は同時PENDING重複を拒否する() {
            joinRequestRepository.saveAndFlush(JoinRequestEntity.builder()
                    .teamId(publicTeamAId)
                    .requesterUserId(applicantId)
                    .status(JoinRequestStatus.PENDING)
                    .build());

            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () -> joinRequestRepository.saveAndFlush(JoinRequestEntity.builder()
                            .teamId(publicTeamAId)
                            .requesterUserId(applicantId)
                            .status(JoinRequestStatus.PENDING)
                            .build()));
        }

        @Test
        @DisplayName("join_requestsテーブルの照合順序は既存標準（utf8mb4_0900_ai_ci）に統一されている")
        void collation_標準へ統一済み() {
            String collation = (String) em.createNativeQuery(
                            "SELECT TABLE_COLLATION FROM information_schema.TABLES "
                                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'join_requests'")
                    .getSingleResult();
            assertThat(collation).isEqualTo("utf8mb4_0900_ai_ci");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JoinRequestEntity persistPendingRequest(Long teamId, Long requesterUserId) {
        return joinRequestRepository.saveAndFlush(JoinRequestEntity.builder()
                .teamId(teamId)
                .requesterUserId(requesterUserId)
                .status(JoinRequestStatus.PENDING)
                .build());
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
                                + "VALUES (:email, 'JRAUTHZ', 'テスト', 'JRAUTHZ テスト', 'ACTIVE', "
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

    private Long insertTeam(String name, String slug, String visibility, String lifecycleStatus, boolean archived) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, lifecycle_status, supporter_enabled, version, "
                                + "member_count, slug, archived_at, created_at, updated_at) "
                                + "VALUES (:name, :visibility, :lifecycleStatus, 1, 0, 0, :slug, "
                                + (archived ? "NOW()" : "NULL") + ", NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("visibility", visibility)
                .setParameter("lifecycleStatus", lifecycleStatus)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, lifecycle_status, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), 'ACTIVE', NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
