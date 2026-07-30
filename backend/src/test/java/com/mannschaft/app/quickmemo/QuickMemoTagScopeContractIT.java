package com.mannschaft.app.quickmemo;

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
 * 認可根治戦役 Wave6: quickmemo ドメイン {@code TagController} の
 * TEAM / ORGANIZATION スコープ 8 エンドポイントの API 契約テスト（試練）。
 *
 * <p><b>保証する内容</b>: TEAM / ORG 系 8 EP は、パス変数の {@code teamId} / {@code orgId}
 * を {@code TagService} の scopeId として用いる前に、当該スコープへの所属（一覧）または
 * ADMIN / DEPUTY_ADMIN 権限（作成・更新・削除）を要求する。
 * Javadoc / {@code @Operation} summary が宣言する権限要件と実装を一致させ、
 * 宣言が実体を伴うことを本テストで固定する。</p>
 *
 * <p><b>正本</b>: 設計書 {@code docs/features/F02.5_quick_memo.md} §8.1 認可マトリクス
 * （「チームタグ CRUD: チーム所属。作成・更新・削除は ADMIN / DEPUTY_ADMIN」
 * 「組織タグ CRUD: 組織所属。作成・更新・削除は ORGANIZATION_ADMIN」）。</p>
 *
 * <p><b>金型</b>: {@code CmsSeriesTagScopeContractIT}（Wave3-B7 の姉妹タグ CRUD）・
 * {@code FacilityOrgScopeContractIT}（Wave5 の ORG スコープ補完）。
 * {@code @AutoConfigureMockMvc(addFilters = false)} + 実 MySQL に実データ seed し、
 * {@code SecurityContextHolder} へ userId を直接投入してなりすます。</p>
 *
 * <p><b>検証する 8 EP と期待認可</b>:</p>
 * <ul>
 *   <li>GET    {@code /api/v1/teams/{teamId}/tags}                  → 所属メンバー以上</li>
 *   <li>POST   {@code /api/v1/teams/{teamId}/tags}                  → ADMIN / DEPUTY_ADMIN</li>
 *   <li>PUT    {@code /api/v1/teams/{teamId}/tags/{tagId}}          → ADMIN / DEPUTY_ADMIN</li>
 *   <li>DELETE {@code /api/v1/teams/{teamId}/tags/{tagId}}          → ADMIN / DEPUTY_ADMIN</li>
 *   <li>GET    {@code /api/v1/organizations/{orgId}/tags}           → 所属メンバー以上</li>
 *   <li>POST   {@code /api/v1/organizations/{orgId}/tags}           → ADMIN / DEPUTY_ADMIN</li>
 *   <li>PUT    {@code /api/v1/organizations/{orgId}/tags/{tagId}}   → ADMIN / DEPUTY_ADMIN</li>
 *   <li>DELETE {@code /api/v1/organizations/{orgId}/tags/{tagId}}   → ADMIN / DEPUTY_ADMIN</li>
 * </ul>
 *
 * <p><b>認可根治戦役 第1波（個人領域）での追補</b>: PERSONAL スコープ 4 EP
 * （{@code GET/POST /api/v1/me/tags}・{@code PUT/DELETE /api/v1/me/tags/{tagId}}）を
 * {@link PersonalTags} 節で追加検証する。PERSONAL 系は scopeId をクライアントから受け取らず
 * 常に認証主体を用いるため {@code @PreAuthorize} を持たないが、その構造的安全は
 * 実測で固定しておく必要がある（{@code TagController} の EP には監査済マーカー
 * {@code @AuthorizedInService} を付与しており、本節がその証跡となる）。
 * 併せて、他ユーザーのタグを指した越境が {@code QM_010} → <b>404</b> になることを固定する
 * （{@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} への {@code QM_010} 登録により成立。
 * 未登録時は Severity.WARN 既定の 400 が返り、Javadoc の宣言と実挙動が乖離していた）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("quickmemo タグ TEAM/ORG スコープ API 契約テスト（認可根治 Wave6）")
class QuickMemoTagScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgAId;
    private Long orgBId;

    /** teamA / orgA の ADMIN（正当な管理者）。 */
    private Long adminAId;
    /** teamB / orgB の ADMIN（別スコープの管理者＝越境してはならない）。 */
    private Long adminBId;
    /** teamA / orgA の非 ADMIN 一般メンバー。 */
    private Long memberAId;
    /** どこにも所属しない非メンバー。 */
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        // test profile は Flyway 無効（ddl-auto=create）のため roles マスタを手動 seed する。
        insertRoleIfAbsent("ADMIN", "管理者", 2);

        teamAId = insertTeam("QMTAG認可契約チームA");
        teamBId = insertTeam("QMTAG認可契約チームB");
        orgAId = insertOrganization("QMTAG認可契約組織A");
        orgBId = insertOrganization("QMTAG認可契約組織B");

        adminAId = insertUser("qmtag-authz-admin-a@example.com");
        adminBId = insertUser("qmtag-authz-admin-b@example.com");
        memberAId = insertUser("qmtag-authz-member-a@example.com");
        outsiderId = insertUser("qmtag-authz-outsider@example.com");

        // isScopeAdmin（user_roles 由来）と isScopeMember（memberships 由来）は別系統のため、
        // ADMIN ユーザーにも memberships 行を張る（本キャンペーン既知の地雷）。
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);

        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);

        // memberA は teamA / orgA の一般メンバー（ADMIN ではない）。
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);

        // outsiderId はどこにも所属させない。

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP1: GET /api/v1/teams/{teamId}/tags — isScopeMember
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("EP1 チームタグ一覧(listTeamTags)")
    class ListTeamTags {

        @Test
        @DisplayName("非メンバーの一覧取得は403（一覧は所属メンバーに限定する）")
        void 非メンバーの一覧取得は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/tags", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの一覧取得は403")
        void 他チームADMINの一覧取得は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/tags", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバーの一覧取得は200（読み取りはADMIN限定ではない）")
        void 一般メンバーの一覧取得は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/tags", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP2: POST /api/v1/teams/{teamId}/tags — isScopeAdmin
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("EP2 チームタグ作成(createTeamTag)")
    class CreateTeamTag {

        @Test
        @DisplayName("非メンバーの作成は403")
        void 非メンバーの作成は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/tags", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTagBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の作成は403")
        void 一般メンバーの作成は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/tags", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTagBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの作成は403（越境防止）")
        void 他チームADMINの作成は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/tags", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTagBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの作成は201")
        void 正当ADMINの作成は201() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/tags", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTagBody())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP3/EP4: PUT / DELETE /api/v1/teams/{teamId}/tags/{tagId} — isScopeAdmin
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("EP3/EP4 チームタグ更新(updateTeamTag)・削除(deleteTeamTag)")
    class UpdateAndDeleteTeamTag {

        @Test
        @DisplayName("非メンバーの更新は403")
        void 非メンバーの更新は403() throws Exception {
            Long tagId = createTeamTagAsAdminA();

            setAuthentication(outsiderId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/tags/{tagId}", teamAId, tagId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTagBody("改名"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の更新は403")
        void 一般メンバーの更新は403() throws Exception {
            Long tagId = createTeamTagAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/tags/{tagId}", teamAId, tagId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTagBody("改名"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの更新は403（越境防止）")
        void 他チームADMINの更新は403() throws Exception {
            Long tagId = createTeamTagAsAdminA();

            setAuthentication(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/tags/{tagId}", teamAId, tagId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTagBody("乗っ取り"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの更新は200")
        void 正当ADMINの更新は200() throws Exception {
            Long tagId = createTeamTagAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/tags/{tagId}", teamAId, tagId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTagBody("改名済"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("改名済"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の削除は403")
        void 一般メンバーの削除は403() throws Exception {
            Long tagId = createTeamTagAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/tags/{tagId}", teamAId, tagId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの削除は403（越境防止）")
        void 他チームADMINの削除は403() throws Exception {
            Long tagId = createTeamTagAsAdminA();

            setAuthentication(adminBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/tags/{tagId}", teamAId, tagId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの削除は204")
        void 正当ADMINの削除は204() throws Exception {
            Long tagId = createTeamTagAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/tags/{tagId}", teamAId, tagId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP5: GET /api/v1/organizations/{orgId}/tags — isScopeMember
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("EP5 組織タグ一覧(listOrgTags)")
    class ListOrgTags {

        @Test
        @DisplayName("非メンバーの一覧取得は403")
        void 非メンバーの一覧取得は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/tags", orgAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他組織ADMINの一覧取得は403")
        void 他組織ADMINの一覧取得は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/tags", orgAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバーの一覧取得は200")
        void 一般メンバーの一覧取得は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/tags", orgAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP6: POST /api/v1/organizations/{orgId}/tags — isScopeAdmin
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("EP6 組織タグ作成(createOrgTag)")
    class CreateOrgTag {

        @Test
        @DisplayName("非メンバーの作成は403")
        void 非メンバーの作成は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/tags", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTagBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の作成は403")
        void 一般メンバーの作成は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/tags", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTagBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他組織ADMINの作成は403（越境防止）")
        void 他組織ADMINの作成は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/tags", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTagBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの作成は201")
        void 正当ADMINの作成は201() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/tags", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTagBody())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP7/EP8: PUT / DELETE /api/v1/organizations/{orgId}/tags/{tagId} — isScopeAdmin
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("EP7/EP8 組織タグ更新(updateOrgTag)・削除(deleteOrgTag)")
    class UpdateAndDeleteOrgTag {

        @Test
        @DisplayName("非メンバーの更新は403")
        void 非メンバーの更新は403() throws Exception {
            Long tagId = createOrgTagAsAdminA();

            setAuthentication(outsiderId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/tags/{tagId}", orgAId, tagId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTagBody("改名"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の更新は403")
        void 一般メンバーの更新は403() throws Exception {
            Long tagId = createOrgTagAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/tags/{tagId}", orgAId, tagId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTagBody("改名"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他組織ADMINの更新は403（越境防止）")
        void 他組織ADMINの更新は403() throws Exception {
            Long tagId = createOrgTagAsAdminA();

            setAuthentication(adminBId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/tags/{tagId}", orgAId, tagId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTagBody("乗っ取り"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの更新は200")
        void 正当ADMINの更新は200() throws Exception {
            Long tagId = createOrgTagAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/tags/{tagId}", orgAId, tagId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTagBody("改名済"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("改名済"));
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の削除は403")
        void 一般メンバーの削除は403() throws Exception {
            Long tagId = createOrgTagAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/tags/{tagId}", orgAId, tagId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他組織ADMINの削除は403（越境防止）")
        void 他組織ADMINの削除は403() throws Exception {
            Long tagId = createOrgTagAsAdminA();

            setAuthentication(adminBId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/tags/{tagId}", orgAId, tagId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの削除は204")
        void 正当ADMINの削除は204() throws Exception {
            Long tagId = createOrgTagAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/tags/{tagId}", orgAId, tagId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP9-12: PERSONAL スコープ /api/v1/me/tags（認可根治 第1波で追補）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("EP9-12 個人タグ(listPersonalTags/createPersonalTag/updatePersonalTag/deletePersonalTag)")
    class PersonalTags {

        @Test
        @DisplayName("個人タグ一覧には他ユーザーのタグが混入しない")
        void 個人タグ一覧は自己スコープに閉じる() throws Exception {
            Long tagId = createPersonalTagAs(memberAId);

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/me/tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/me/tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(tagId));
        }

        @Test
        @DisplayName("作成した個人タグは作成者に帰属する（PERSONAL / scopeId = 認証主体）")
        void 作成した個人タグは作成者に帰属する() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/me/tags")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTagBody())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.scopeType").value("PERSONAL"))
                    .andExpect(jsonPath("$.data.scopeId").value(memberAId));
        }

        @Test
        @DisplayName("他ユーザーの個人タグの更新は404で秘匿される")
        void 他ユーザーの個人タグ更新は404() throws Exception {
            Long tagId = createPersonalTagAs(memberAId);

            setAuthentication(outsiderId);
            mockMvc.perform(put("/api/v1/me/tags/{tagId}", tagId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTagBody("乗っ取り"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QM_010"));
        }

        @Test
        @DisplayName("他ユーザーの個人タグの削除は404で秘匿される")
        void 他ユーザーの個人タグ削除は404() throws Exception {
            Long tagId = createPersonalTagAs(memberAId);

            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/me/tags/{tagId}", tagId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QM_010"));

            // 削除要求で実データが失われていないことも確認する。
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/me/tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("TEAM スコープのタグIDを /me/tags 経由で操作できない（スコープ越境防止）")
        void チームタグは個人タグ経路から操作できない() throws Exception {
            Long teamTagId = createTeamTagAsAdminA();

            // teamA の ADMIN 本人であっても、PERSONAL 経路では自分のタグとして引き当てられない。
            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/me/tags/{tagId}", teamTagId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTagBody("横取り"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QM_010"));

            mockMvc.perform(delete("/api/v1/me/tags/{tagId}", teamTagId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QM_010"));
        }

        @Test
        @DisplayName("所有者の個人タグ更新は200・削除は204（正常系）")
        void 所有者の個人タグ更新と削除は成功する() throws Exception {
            Long tagId = createPersonalTagAs(memberAId);

            setAuthentication(memberAId);
            mockMvc.perform(put("/api/v1/me/tags/{tagId}", tagId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTagBody("改名済"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("改名済"));

            mockMvc.perform(delete("/api/v1/me/tags/{tagId}", tagId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** 指定ユーザーの認証コンテキストで PERSONAL タグを1件作成し、その ID を返す。 */
    private Long createPersonalTagAs(Long userId) throws Exception {
        setAuthentication(userId);
        String resp = mockMvc.perform(post("/api/v1/me/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createTagBody())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** adminA の認証コンテキストで teamA のタグを1件作成し、その ID を返す。 */
    private Long createTeamTagAsAdminA() throws Exception {
        setAuthentication(adminAId);
        String resp = mockMvc.perform(post("/api/v1/teams/{teamId}/tags", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createTagBody())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /** adminA の認証コンテキストで orgA のタグを1件作成し、その ID を返す。 */
    private Long createOrgTagAsAdminA() throws Exception {
        setAuthentication(adminAId);
        String resp = mockMvc.perform(post("/api/v1/organizations/{orgId}/tags", orgAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createTagBody())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /**
     * タグ作成リクエストボディ。
     * {@code name} は {@code @NotBlank @Size(1,30)} のため必ず充足させる
     * （{@code @Valid} は認可ガードより先に走り、不備があると 400 になって 403 検証に到達しない）。
     * タグ名はスコープ内で一意制約があるため nanoTime で衝突を避ける。
     */
    private Map<String, Object> createTagBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "認可契約" + (System.nanoTime() % 100_000_000L));
        body.put("color", "#FF0000");
        return body;
    }

    private Map<String, Object> updateTagBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        return body;
    }

    /** roles を name で引く idempotent seed（グローバル参照テーブルのため deleteAll しない）。 */
    private void insertRoleIfAbsent(String name, String displayName, int priority) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (count.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, 0, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
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
                                + "VALUES (:email, 'QMTAG契約', 'テスト', 'QMTAG契約テスト', 'ACTIVE', "
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
                                + "CONCAT('qmtag-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('qmtag-o-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
