package com.mannschaft.app.filesharing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave7 — filesharing ドメイン（共有フォルダ {@code SharedFolderService} 系）認可契約テスト。
 *
 * <p><b>検証範囲:</b> {@code SharedFolderService} が {@code AccessControlService} を用いて
 * TEAM / ORGANIZATION / PERSONAL の各スコープで per-scope 認可（メンバー / 管理者）を
 * 実効的に強制していることを、ルート一覧・子一覧・詳細・作成・更新・削除の全経路で検証する。</p>
 *
 * <p>是正の手本は同一ドメインで既に根治済みのファイル側
 * （{@code SharedFileService#listFiles} → {@code SharedFolderQueryService#authorizeFolderViewById}）。</p>
 *
 * <p><b>象限</b>: 非メンバー（outsider）/ 別 scope ADMIN（BOLA）/ 非 ADMIN メンバー / 正当 ADMIN、
 * および <b>TEAM と ORGANIZATION の両系統</b>を網羅する。加えて
 * {@code parentId} による<b>接ぎ木</b>（他スコープ配下へのぶら下げ）が封鎖されていることを検証する。</p>
 *
 * <p>金型: {@code EquipmentScopeContractIT}。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("filesharing ドメイン（共有フォルダ）認可契約テスト")
class SharedFolderScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SharedFolderRepository folderRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgAId;
    private Long orgBId;

    private Long adminTeamAId;   // TEAM A の ADMIN（正当）
    private Long adminTeamBId;   // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId;  // TEAM A の非 ADMIN メンバー
    private Long adminOrgAId;    // ORG A の ADMIN（正当）
    private Long adminOrgBId;    // ORG B の ADMIN（別 scope の越境攻撃者）
    private Long memberOrgAId;   // ORG A の非 ADMIN メンバー
    private Long ownerId;        // 個人フォルダの所有者
    private Long outsiderId;     // どこにも所属しない非メンバー

    private Long folderTeamAId;
    private Long folderTeamBId;
    private Long folderOrgAId;
    private Long folderPersonalId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("W7FS チームA");
        teamBId = insertTeam("W7FS チームB");
        orgAId = insertOrganization("W7FS 組織A");
        orgBId = insertOrganization("W7FS 組織B");

        adminTeamAId = insertUser("w7fs-admin-team-a@example.com");
        adminTeamBId = insertUser("w7fs-admin-team-b@example.com");
        memberTeamAId = insertUser("w7fs-member-team-a@example.com");
        adminOrgAId = insertUser("w7fs-admin-org-a@example.com");
        adminOrgBId = insertUser("w7fs-admin-org-b@example.com");
        memberOrgAId = insertUser("w7fs-member-org-a@example.com");
        ownerId = insertUser("w7fs-owner@example.com");
        outsiderId = insertUser("w7fs-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        MembershipTestHelper.insertMembership(em, adminOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminOrgBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, memberOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        // ownerId / outsiderId はどこにも所属させない。

        folderTeamAId = folderRepository.save(SharedFolderEntity.builder()
                .scopeType(FileScopeType.TEAM).teamId(teamAId)
                .name("W7FS チームAの資料").description("説明").createdBy(adminTeamAId)
                .build()).getId();
        folderTeamBId = folderRepository.save(SharedFolderEntity.builder()
                .scopeType(FileScopeType.TEAM).teamId(teamBId)
                .name("W7FS チームBの資料").createdBy(adminTeamBId)
                .build()).getId();
        folderOrgAId = folderRepository.save(SharedFolderEntity.builder()
                .scopeType(FileScopeType.ORGANIZATION).organizationId(orgAId)
                .name("W7FS 組織Aの資料").createdBy(adminOrgAId)
                .build()).getId();
        folderPersonalId = folderRepository.save(SharedFolderEntity.builder()
                .scopeType(FileScopeType.PERSONAL).userId(ownerId)
                .name("W7FS 個人フォルダ").createdBy(ownerId)
                .build()).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /teams/{teamId}/folders（ルート一覧の認可契約）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/folders（チームルート一覧）")
    class ListTeamRootFolders {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/folders", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/folders", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200（閲覧はメンバー粒度）")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/folders", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200（機能非回帰）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/folders", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /teams/{teamId}/folders（作成・接ぎ木封鎖）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /teams/{teamId}/folders（チームフォルダ作成）")
    class CreateTeamFolder {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/folders", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody("TEAM", null, "侵入フォルダ"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/folders", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody("TEAM", null, "越境フォルダ"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは201（機能非回帰）")
        void 正当メンバーは201() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/folders", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody("TEAM", null, "新規フォルダ"))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("★接ぎ木封鎖★ 他チームのfolderIdをparentIdに指定すると404")
        void 他チーム配下への接ぎ木は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/folders", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createBody("TEAM", folderTeamBId, "接ぎ木フォルダ"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("自チームのfolderIdをparentIdに指定すれば201（正常な階層作成）")
        void 自チーム配下への作成は201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/folders", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createBody("TEAM", folderTeamAId, "子フォルダ"))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. GET /teams/{teamId}/folders/{folderId}・/children（詳細・子一覧: 実体由来 scope）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET /teams/{teamId}/folders/{folderId}（詳細・子一覧）")
    class GetTeamFolder {

        @Test
        @DisplayName("非メンバーは詳細403")
        void 非メンバーは詳細403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/folders/{folderId}", teamAId, folderTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("★BOLA★ teamBのADMINが自チームURLに他チームfolderIdを混ぜても403（実体由来scope）")
        void パスのteamIdを詐称しても403() throws Exception {
            setAuth(adminTeamBId);
            // パスは自分が ADMIN の teamB。folderId だけ teamA のものを混ぜる。
            mockMvc.perform(get("/api/v1/teams/{teamId}/folders/{folderId}", teamBId, folderTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは詳細200")
        void 非ADMINメンバーは詳細200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/folders/{folderId}", teamAId, folderTeamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーは子フォルダ一覧403")
        void 非メンバーは子一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/folders/{folderId}/children", teamAId, folderTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは子フォルダ一覧200")
        void 非ADMINメンバーは子一覧200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/folders/{folderId}/children", teamAId, folderTeamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("他人の個人フォルダは404（存在秘匿）")
        void 他人の個人フォルダは404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/folders/{folderId}", teamAId, folderPersonalId))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. PATCH/DELETE /teams/{teamId}/folders/{folderId}（管理操作: ADMIN 粒度）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PATCH/DELETE /teams/{teamId}/folders/{folderId}")
    class UpdateDeleteTeamFolder {

        @Test
        @DisplayName("非ADMINメンバーは更新403（可視性設定の書換は管理者限定）")
        void 非ADMINメンバーは更新403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/folders/{folderId}", teamAId, folderTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("更新後"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは更新403（BOLA）")
        void 別scopeADMINは更新403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/folders/{folderId}", teamAId, folderTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("乗っ取り更新"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは更新200（機能非回帰）")
        void 正当ADMINは更新200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/folders/{folderId}", teamAId, folderTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("更新後"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("★接ぎ木封鎖★ 他チーム配下へのparentId移動は404")
        void 他チーム配下への移動は404() throws Exception {
            setAuth(adminTeamAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("parentId", folderTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/folders/{folderId}", teamAId, folderTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバーは削除403")
        void 非ADMINメンバーは削除403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/folders/{folderId}", teamAId, folderTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは削除403（BOLA）")
        void 別scopeADMINは削除403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/folders/{folderId}", teamAId, folderTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは削除204（機能非回帰）")
        void 正当ADMINは削除204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/folders/{folderId}", teamAId, folderTeamAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 組織スコープ（/organizations/{organizationId}/folders）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. 組織スコープ /organizations/{organizationId}/folders")
    class OrganizationScope {

        @Test
        @DisplayName("非メンバーは一覧403")
        void 非メンバーは一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/folders", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（orgBのADMIN）は一覧403（BOLA）")
        void 別scopeADMINは一覧403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/folders", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チームADMINは組織一覧403（スコープ系統違いのBOLA）")
        void 別系統ADMINは一覧403() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/folders", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは一覧200")
        void 非ADMINメンバーは一覧200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/folders", orgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは一覧200（機能非回帰）")
        void 正当ADMINは一覧200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/folders", orgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーは作成403")
        void 非メンバーは作成403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/folders", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createBody("ORGANIZATION", null, "侵入組織フォルダ"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは作成403（BOLA）")
        void 別scopeADMINは作成403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/folders", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createBody("ORGANIZATION", null, "越境組織フォルダ"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは作成201（機能非回帰）")
        void 正当メンバーは作成201() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/folders", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createBody("ORGANIZATION", null, "新規組織フォルダ"))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("★接ぎ木封鎖★ チームのfolderIdをparentIdに指定すると404")
        void チーム配下への接ぎ木は404() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/folders", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createBody("ORGANIZATION", folderTeamAId, "接ぎ木組織フォルダ"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("自組織のfolderIdをparentIdに指定すれば201")
        void 自組織配下への作成は201() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/folders", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createBody("ORGANIZATION", folderOrgAId, "組織子フォルダ"))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. 個人スコープ（/me/folders）— parentId 接ぎ木の封鎖
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. 個人スコープ /me/folders")
    class PersonalScope {

        @Test
        @DisplayName("★接ぎ木封鎖★ 他チームのfolderIdをparentIdに指定すると404")
        void チーム配下への個人フォルダ接ぎ木は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/me/folders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createBody("PERSONAL", folderTeamAId, "接ぎ木個人フォルダ"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("★接ぎ木封鎖★ 他人の個人フォルダをparentIdに指定すると404")
        void 他人の個人フォルダ配下への接ぎ木は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/me/folders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createBody("PERSONAL", folderPersonalId, "他人配下フォルダ"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("自分の個人フォルダ配下なら201（機能非回帰）")
        void 自分の個人フォルダ配下は201() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/me/folders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createBody("PERSONAL", folderPersonalId, "個人子フォルダ"))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("ルート直下の個人フォルダ作成は201（機能非回帰）")
        void ルート直下の個人フォルダ作成は201() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/me/folders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createBody("PERSONAL", null, "個人ルートフォルダ"))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> createBody(String scopeType, Long parentId, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("scopeType", scopeType);
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        return body;
    }

    private Map<String, Object> updateBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        return body;
    }

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
                                + "VALUES (:email, 'W7FS', 'テスト', 'W7FS テスト', 'ACTIVE', "
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
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
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
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
