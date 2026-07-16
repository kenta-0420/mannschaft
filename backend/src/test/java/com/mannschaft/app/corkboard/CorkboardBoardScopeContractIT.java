package com.mannschaft.app.corkboard;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B8: corkboard ドメイン（{@code TeamCorkboardController} の board CRUD）の
 * API 契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave3-B8 corkboard 節）・{@code CorkboardService}
 * （{@code listScopedBoards}/{@code getScopedBoard}/{@code createScopedBoard}/
 * {@code updateScopedBoard}/{@code deleteScopedBoard}）・{@code AccessControlService}
 * （{@code isMember}/{@code isAdminOrAbove}）。金型: {@code GalleryScopeContractIT}。</p>
 *
 * <p>corkboard は entity 由来 scope membership モデル:</p>
 * <ul>
 *   <li><b>読取</b>（list/get）: 当該スコープのメンバーのみ許可（{@code isMember}）。
 *       是正前は list/get いずれも scopeType/scopeId/boardId さえ知っていれば
 *       非所属者でもカード・セクション全内容を閲覧できる BOLA だった。</li>
 *   <li><b>書込</b>（create/update/delete）: 当該スコープの ADMIN/DEPUTY_ADMIN のみ許可
 *       （{@code isAdminOrAbove}）。update は {@code editPolicy} 改変を伴い得るため
 *       {@code checkEditPermission}（ALL_MEMBERS 許容）ではなく必ず ADMIN 水準。</li>
 * </ul>
 *
 * <p>越境（path scopeId ≠ board の実 scope）は {@code findByIdAndScopeTypeAndScopeId} が
 * 単一クエリで scope 整合性を担保しているため 404（{@code CORKBOARD_001}）で秘匿される
 * （403 ではなく存在秘匿）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("corkboard ドメイン board CRUD API 契約テスト（認可根治 Wave3-B8）")
class CorkboardBoardScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("CB認可契約チームA");
        teamBId = insertTeam("CB認可契約チームB");

        adminAId = insertUser("cb-authz-admin-a@example.com");
        adminBId = insertUser("cb-authz-admin-b@example.com");
        memberAId = insertUser("cb-authz-member-a@example.com");
        outsiderId = insertUser("cb-authz-outsider@example.com");

        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // ボード一覧(listScopedBoards) — BOLA根治
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ボード一覧(listScopedBoards)")
    class ListBoards {

        @Test
        @DisplayName("非メンバーの一覧取得は403")
        void 非メンバーの一覧取得は403() throws Exception {
            insertBoard("TEAM", teamAId, "一覧テストボード", "ADMIN_ONLY");

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/corkboards", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_009"));
        }

        @Test
        @DisplayName("メンバーの一覧取得は200")
        void メンバーの一覧取得は200() throws Exception {
            insertBoard("TEAM", teamAId, "一覧テストボード2", "ADMIN_ONLY");

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/corkboards", teamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ボード作成(createScopedBoard)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ボード作成(createScopedBoard)")
    class CreateBoard {

        @Test
        @DisplayName("非ADMINメンバーの作成は403")
        void 非ADMINメンバーの作成は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/corkboards", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBoardBody("新規ボード"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_009"));
        }

        @Test
        @DisplayName("非メンバーの作成は403")
        void 非メンバーの作成は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/corkboards", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBoardBody("新規ボード"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_009"));
        }

        @Test
        @DisplayName("正当ADMINの作成は201")
        void 正当ADMINの作成は201() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/corkboards", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBoardBody("新規ボード"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ボード詳細取得(getScopedBoard) — BOLA根治 + 越境404秘匿
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ボード詳細取得(getScopedBoard)")
    class GetBoard {

        @Test
        @DisplayName("非メンバーの取得は403（scopeId/boardIdを知るだけではカード・セクション全内容を閲覧できない）")
        void 非メンバーの取得は403() throws Exception {
            Long boardId = insertBoard("TEAM", teamAId, "詳細テストボード", "ADMIN_ONLY");

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/corkboards/{id}", teamAId, boardId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_009"));
        }

        @Test
        @DisplayName("越境（teamBパスでteamAのboardId指定）は404秘匿（scope不一致でクエリがヒットしない）")
        void 越境は404秘匿() throws Exception {
            Long boardId = insertBoard("TEAM", teamAId, "越境テストボード", "ADMIN_ONLY");

            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/corkboards/{id}", teamBId, boardId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_001"));
        }

        @Test
        @DisplayName("メンバーの取得は200")
        void メンバーの取得は200() throws Exception {
            Long boardId = insertBoard("TEAM", teamAId, "詳細テストボード2", "ADMIN_ONLY");

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/corkboards/{id}", teamAId, boardId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(boardId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ボード更新(updateScopedBoard)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ボード更新(updateScopedBoard)")
    class UpdateBoard {

        @Test
        @DisplayName("非ADMINメンバーの更新は403（editPolicy改変を伴い得るためALL_MEMBERSでも不可）")
        void 非ADMINメンバーの更新は403() throws Exception {
            Long boardId = insertBoard("TEAM", teamAId, "更新テストボード", "ALL_MEMBERS");

            setAuthentication(memberAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/corkboards/{id}", teamAId, boardId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBoardBody("改題"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_009"));
        }

        @Test
        @DisplayName("正当ADMINの更新は200")
        void 正当ADMINの更新は200() throws Exception {
            Long boardId = insertBoard("TEAM", teamAId, "更新テストボード2", "ADMIN_ONLY");

            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/corkboards/{id}", teamAId, boardId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBoardBody("改題済"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("改題済"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ボード削除(deleteScopedBoard)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ボード削除(deleteScopedBoard)")
    class DeleteBoard {

        @Test
        @DisplayName("非ADMINメンバーの削除は403")
        void 非ADMINメンバーの削除は403() throws Exception {
            Long boardId = insertBoard("TEAM", teamAId, "削除テストボード", "ADMIN_ONLY");

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/corkboards/{id}", teamAId, boardId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_009"));
        }

        @Test
        @DisplayName("正当ADMINの削除は204")
        void 正当ADMINの削除は204() throws Exception {
            Long boardId = insertBoard("TEAM", teamAId, "削除テストボード2", "ADMIN_ONLY");

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/corkboards/{id}", teamAId, boardId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Map<String, Object> createBoardBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name + " " + System.nanoTime());
        return body;
    }

    private Map<String, Object> updateBoardBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        return body;
    }

    /** corkboards へ直接 INSERT する。 */
    private Long insertBoard(String scopeType, Long scopeId, String name, String editPolicy) {
        String uniqueName = name + " " + System.nanoTime();
        em.createNativeQuery(
                        "INSERT INTO corkboards "
                                + "(scope_type, scope_id, name, background_style, edit_policy, is_default, "
                                + "version, created_at, updated_at) "
                                + "VALUES (:scopeType, :scopeId, :name, 'CORK', :editPolicy, 0, 0, NOW(), NOW())")
                .setParameter("scopeType", scopeType)
                .setParameter("scopeId", scopeId)
                .setParameter("name", uniqueName)
                .setParameter("editPolicy", editPolicy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM corkboards WHERE name = :name")
                .setParameter("name", uniqueName)
                .getSingleResult()).longValue();
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
                                + "VALUES (:email, 'CB契約', 'テスト', 'CB契約テスト', 'ACTIVE', "
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
                                + "CONCAT('cb-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
