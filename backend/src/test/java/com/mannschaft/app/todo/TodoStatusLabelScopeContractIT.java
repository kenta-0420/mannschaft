package com.mannschaft.app.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.todo.entity.TodoStatusLabelEntity;
import com.mannschaft.app.todo.repository.TodoStatusLabelRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TODO ステータスラベル（個人／チーム／組織スコープ）の認可契約テスト
 * （認可根治戦役 第1波・個人領域 ロットA）。
 *
 * <p>本 IT が固定する保証:</p>
 * <ul>
 *   <li><b>参照（一覧）</b>: チーム・組織スコープのラベル一覧は<b>当該スコープのメンバーに限定</b>する。
 *       非メンバー・別スコープのメンバーは 403。これは本 PR で敷いた認可の回帰固定である
 *       （従来は参照経路に認可判定が無かった）。</li>
 *   <li><b>CRUD</b>: チーム・組織スコープの作成・更新・削除は<b>当該スコープの ADMIN に限定</b>する
 *       （設計書 §2。DEPUTY_ADMIN は不可）。</li>
 *   <li><b>スコープ束縛</b>: 更新・削除では path のスコープとラベル本体のスコープの一致を照合し、
 *       他スコープのラベル ID を自スコープの URL で指定しても 404 で存在を秘匿する（BOLA/IDOR）。</li>
 *   <li><b>個人スコープ</b>: スコープ ID は認証主体に固定され、他ユーザーのラベルには到達できない
 *       （更新・削除は 404、一覧には混入しない）。</li>
 * </ul>
 *
 * <p>金型: {@code TodoScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL +
 * 手動 SecurityContext + {@code @EnabledIf isDockerAvailable}）。seed 列は同 IT を写経して整合を保つ。
 * 越境 403/404 はアプリケーション層例外（{@code COMMON_002} → 403 /
 * {@code TODO_076 STATUS_LABEL_NOT_FOUND} → 404）として発生するためフィルタ無効でも検証できる。
 * 未認証は {@code SecurityUtils} の {@code COMMON_000} → 401。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("TODOステータスラベル スコープ認可契約テスト（第1波 ロットA）")
class TodoStatusLabelScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TodoStatusLabelRepository labelRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private String teamASlug;   // チームラベル API はスラッグ受け（resolveTeamId(slug)）
    private String teamBSlug;
    private Long orgAId;
    private Long orgBId;

    private Long adminTeamAId;    // team A の ADMIN（user_roles）— CRUD 可
    private Long memberTeamAId;   // team A の一般 MEMBER — 参照可・CRUD 不可
    private Long memberTeamBId;   // team B のメンバー（team A に対しては非メンバー＝越境）
    private Long adminOrgAId;     // org A の ADMIN
    private Long memberOrgAId;    // org A の一般 MEMBER
    private Long memberOrgBId;    // org B のメンバー（org A に対しては非メンバー＝越境）
    private Long outsiderId;      // どこにも所属しない非メンバー

    private Long ownerUserId;     // 個人ラベルの所有者
    private Long otherUserId;     // 別ユーザー（個人ラベルへの越境元）

    private Long labelTeamAId;    // team A のラベル
    private Long labelOrgAId;     // org A のラベル
    private Long labelPersonalOwnerId;  // ownerUser の個人ラベル

    @BeforeEach
    void setUp() {
        // slug は teams/organizations とも @Column(length = 30) のため 30 字以内に収める。
        String uniq = Long.toString(System.nanoTime(), 36);
        teamASlug = "lta-" + uniq;
        teamBSlug = "ltb-" + uniq;
        teamAId = insertTeam("LABELAUTHZ チームA", teamASlug);
        teamBId = insertTeam("LABELAUTHZ チームB", teamBSlug);
        orgAId = insertOrganization("LABELAUTHZ 組織A", "loa-" + uniq);
        orgBId = insertOrganization("LABELAUTHZ 組織B", "lob-" + uniq);

        adminTeamAId = insertUser("labelauthz-admin-team-a@example.com");
        memberTeamAId = insertUser("labelauthz-member-team-a@example.com");
        memberTeamBId = insertUser("labelauthz-member-team-b@example.com");
        adminOrgAId = insertUser("labelauthz-admin-org-a@example.com");
        memberOrgAId = insertUser("labelauthz-member-org-a@example.com");
        memberOrgBId = insertUser("labelauthz-member-org-b@example.com");
        outsiderId = insertUser("labelauthz-outsider@example.com");
        ownerUserId = insertUser("labelauthz-owner@example.com");
        otherUserId = insertUser("labelauthz-other@example.com");

        // memberships（checkMembership が参照する母集団）
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, adminOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberOrgBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        // ADMIN 判定は user_roles 由来（isAdmin）。memberships と両方 seed する。
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertUserRole(em, adminOrgAId, "ADMIN", null, orgAId);
        // outsiderId はどこにも所属させない。

        labelTeamAId = labelRepository.save(TodoStatusLabelEntity.builder()
                .scopeType(TodoStatusLabelScope.TEAM)
                .scopeId(teamAId)
                .name("LABELAUTHZ チームA ラベル")
                .bucket(TodoStatusBucket.IN_PROGRESS)
                .color("#123456")
                .sortOrder(1)
                .isSystemDefault(false)
                .createdBy(adminTeamAId)
                .build()).getId();

        labelOrgAId = labelRepository.save(TodoStatusLabelEntity.builder()
                .scopeType(TodoStatusLabelScope.ORGANIZATION)
                .scopeId(orgAId)
                .name("LABELAUTHZ 組織A ラベル")
                .bucket(TodoStatusBucket.IN_PROGRESS)
                .color("#123456")
                .sortOrder(1)
                .isSystemDefault(false)
                .createdBy(adminOrgAId)
                .build()).getId();

        labelPersonalOwnerId = labelRepository.save(TodoStatusLabelEntity.builder()
                .scopeType(TodoStatusLabelScope.PERSONAL)
                .scopeId(ownerUserId)
                .name("LABELAUTHZ 個人ラベル")
                .bucket(TodoStatusBucket.IN_PROGRESS)
                .color("#123456")
                .sortOrder(1)
                .isSystemDefault(false)
                .createdBy(ownerUserId)
                .build()).getId();

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 組織スコープ 一覧（本 PR で敷いた参照メンバー限定の回帰固定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /organizations/{orgId}/todo-status-labels（一覧・メンバー限定）")
    class OrgList {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/organizations/{orgId}/todo-status-labels", orgAId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("無関係な他ユーザー(outsider)は403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/todo-status-labels", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別組織のメンバーは403")
        void 別組織メンバーは403() throws Exception {
            setAuth(memberOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/todo-status-labels", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正常系: 当該組織の一般メンバーは200で当該組織のラベルが返る")
        void 正当メンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/todo-status-labels", orgAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(labelOrgAId.intValue())));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. チームスコープ 一覧（本 PR で敷いた参照メンバー限定の回帰固定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /teams/{teamId}/todo-status-labels（一覧・メンバー限定）")
    class TeamList {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/teams/{teamId}/todo-status-labels", teamASlug))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("無関係な他ユーザー(outsider)は403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todo-status-labels", teamASlug))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームのメンバーは403")
        void 別チームメンバーは403() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todo-status-labels", teamASlug))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正常系: 当該チームの一般メンバーは200で当該チームのラベルが返る")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todo-status-labels", teamASlug))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(labelTeamAId.intValue())));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 組織スコープ CRUD（ADMIN 限定 + スコープ束縛）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. 組織ラベル CRUD（ADMIN 限定）")
    class OrgCrud {

        private String createBody(String name) throws Exception {
            return objectMapper.writeValueAsString(
                    java.util.Map.of("name", name, "bucket", "IN_PROGRESS", "color", "#abcdef"));
        }

        @Test
        @DisplayName("作成: 無関係な他ユーザー(outsider)は403")
        void 作成_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/todo-status-labels", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("越境作成")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("作成: 別組織のADMINは403（自組織以外には作成できない）")
        void 作成_別組織メンバーは403() throws Exception {
            setAuth(memberOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/todo-status-labels", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("越境作成")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("作成: 一般メンバーは403（CRUDはADMINのみ）")
        void 作成_一般メンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/todo-status-labels", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("一般メンバー作成")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正常系 作成: 当該組織のADMINは201")
        void 作成_ADMINは201() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/todo-status-labels", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("ADMIN作成ラベル")))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("更新: 無関係な他ユーザー(outsider)は403")
        void 更新_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/todo-status-labels/{labelId}",
                            orgAId, labelOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("name", "越境更新"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("更新: 一般メンバーは403")
        void 更新_一般メンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/todo-status-labels/{labelId}",
                            orgAId, labelOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("name", "一般更新"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正常系 更新: 当該組織のADMINは200")
        void 更新_ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/todo-status-labels/{labelId}",
                            orgAId, labelOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("name", "ADMIN更新済み"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("ADMIN更新済み"));
        }

        @Test
        @DisplayName("更新: BOLA 別組織のラベルIDを自組織URLで指定→404秘匿")
        void 更新_BOLAは404秘匿() throws Exception {
            setAuth(memberOrgBId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/todo-status-labels/{labelId}",
                            orgBId, labelOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("name", "越境更新"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("更新: BOLA 個人ラベルIDを組織URLで指定→404秘匿")
        void 更新_個人ラベルIDは404秘匿() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/todo-status-labels/{labelId}",
                            orgAId, labelPersonalOwnerId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("name", "越境更新"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("削除: 無関係な他ユーザー(outsider)は403")
        void 削除_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/todo-status-labels/{labelId}",
                            orgAId, labelOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("削除: 一般メンバーは403")
        void 削除_一般メンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/todo-status-labels/{labelId}",
                            orgAId, labelOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("削除: BOLA 別組織のラベルIDを自組織URLで指定→404秘匿")
        void 削除_BOLAは404秘匿() throws Exception {
            setAuth(memberOrgBId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/todo-status-labels/{labelId}",
                            orgBId, labelOrgAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系 削除: 当該組織のADMINは204で論理削除される")
        void 削除_ADMINは204() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/todo-status-labels/{labelId}",
                            orgAId, labelOrgAId))
                    .andExpect(status().isNoContent());

            // @Transactional 内では findById が1次キャッシュに当たるため、entity の状態を見る。
            TodoStatusLabelEntity deleted = labelRepository.findById(labelOrgAId).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. チームスコープ CRUD（ADMIN 限定 + スコープ束縛）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. チームラベル CRUD（ADMIN 限定）")
    class TeamCrud {

        private String createBody(String name) throws Exception {
            return objectMapper.writeValueAsString(
                    java.util.Map.of("name", name, "bucket", "IN_PROGRESS", "color", "#abcdef"));
        }

        @Test
        @DisplayName("作成: 無関係な他ユーザー(outsider)は403")
        void 作成_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todo-status-labels", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("越境作成")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("作成: 別チームのメンバーは403")
        void 作成_別チームメンバーは403() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todo-status-labels", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("越境作成")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("作成: 一般メンバーは403（CRUDはADMINのみ）")
        void 作成_一般メンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todo-status-labels", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("一般メンバー作成")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正常系 作成: 当該チームのADMINは201")
        void 作成_ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todo-status-labels", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("ADMIN作成ラベル")))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("更新: 無関係な他ユーザー(outsider)は403")
        void 更新_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/todo-status-labels/{labelId}",
                            teamASlug, labelTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("name", "越境更新"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("更新: 一般メンバーは403")
        void 更新_一般メンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/todo-status-labels/{labelId}",
                            teamASlug, labelTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("name", "一般更新"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正常系 更新: 当該チームのADMINは200")
        void 更新_ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/todo-status-labels/{labelId}",
                            teamASlug, labelTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("name", "ADMIN更新済み"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("ADMIN更新済み"));
        }

        @Test
        @DisplayName("更新: BOLA 別チームのラベルIDを自チームURLで指定→404秘匿")
        void 更新_BOLAは404秘匿() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/todo-status-labels/{labelId}",
                            teamBSlug, labelTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("name", "越境更新"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("削除: 無関係な他ユーザー(outsider)は403")
        void 削除_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todo-status-labels/{labelId}",
                            teamASlug, labelTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("削除: 一般メンバーは403")
        void 削除_一般メンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todo-status-labels/{labelId}",
                            teamASlug, labelTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("削除: BOLA 別チームのラベルIDを自チームURLで指定→404秘匿")
        void 削除_BOLAは404秘匿() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todo-status-labels/{labelId}",
                            teamBSlug, labelTeamAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系 削除: 当該チームのADMINは204で論理削除される")
        void 削除_ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todo-status-labels/{labelId}",
                            teamASlug, labelTeamAId))
                    .andExpect(status().isNoContent());

            TodoStatusLabelEntity deleted = labelRepository.findById(labelTeamAId).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 個人スコープ（自己スコープ・他ユーザーのラベルへ到達できないこと）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. 個人ラベル（自己スコープ）")
    class PersonalLabels {

        @Test
        @DisplayName("一覧: 未認証は401")
        void 一覧_未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/users/me/todo-status-labels"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("一覧: 他ユーザーの個人ラベルは混入しない（自分のスコープのみ）")
        void 一覧_他人のラベルは混入しない() throws Exception {
            setAuth(otherUserId);
            mockMvc.perform(get("/api/v1/users/me/todo-status-labels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(labelPersonalOwnerId.intValue()))));
        }

        @Test
        @DisplayName("正常系 一覧: 所有者は自分の個人ラベルを取得できる")
        void 一覧_所有者は200() throws Exception {
            setAuth(ownerUserId);
            mockMvc.perform(get("/api/v1/users/me/todo-status-labels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(labelPersonalOwnerId.intValue())));
        }

        @Test
        @DisplayName("更新: 無関係な他ユーザーが他人の個人ラベルIDを指定→404秘匿")
        void 更新_他人のラベルは404秘匿() throws Exception {
            setAuth(otherUserId);
            mockMvc.perform(put("/api/v1/users/me/todo-status-labels/{labelId}", labelPersonalOwnerId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("name", "越境更新"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系 更新: 所有者は200")
        void 更新_所有者は200() throws Exception {
            setAuth(ownerUserId);
            mockMvc.perform(put("/api/v1/users/me/todo-status-labels/{labelId}", labelPersonalOwnerId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("name", "所有者更新済み"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("所有者更新済み"));
        }

        @Test
        @DisplayName("更新: チームラベルIDを個人URLで指定→404秘匿")
        void 更新_チームラベルIDは404秘匿() throws Exception {
            setAuth(ownerUserId);
            mockMvc.perform(put("/api/v1/users/me/todo-status-labels/{labelId}", labelTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("name", "越境更新"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("削除: 無関係な他ユーザーが他人の個人ラベルIDを指定→404秘匿")
        void 削除_他人のラベルは404秘匿() throws Exception {
            setAuth(otherUserId);
            mockMvc.perform(delete("/api/v1/users/me/todo-status-labels/{labelId}", labelPersonalOwnerId))
                    .andExpect(status().isNotFound());

            // 越境削除が成立していないこと（論理削除されていないこと）を entity 状態で確認する。
            TodoStatusLabelEntity intact = labelRepository.findById(labelPersonalOwnerId).orElseThrow();
            assertThat(intact.getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("正常系 削除: 所有者は204で論理削除される")
        void 削除_所有者は204() throws Exception {
            setAuth(ownerUserId);
            mockMvc.perform(delete("/api/v1/users/me/todo-status-labels/{labelId}", labelPersonalOwnerId))
                    .andExpect(status().isNoContent());

            TodoStatusLabelEntity deleted = labelRepository.findById(labelPersonalOwnerId).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("正常系 作成: 自分のスコープに作成できる（201）")
        void 作成_所有者は201() throws Exception {
            setAuth(otherUserId);
            mockMvc.perform(post("/api/v1/users/me/todo-status-labels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "name", "自分のラベル", "bucket", "IN_PROGRESS", "color", "#abcdef"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.scopeId").value(otherUserId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー（金型 TodoScopeContractIT より写経）
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
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
                                + "VALUES (:email, 'LABELAUTHZ', 'テスト', 'LABELAUTHZ テスト', 'ACTIVE', "
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

    private Long insertTeam(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
