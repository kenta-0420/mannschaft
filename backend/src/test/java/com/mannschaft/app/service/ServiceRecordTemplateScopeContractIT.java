package com.mannschaft.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.service.entity.ServiceRecordTemplateEntity;
import com.mannschaft.app.service.repository.ServiceRecordTemplateRepository;
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
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave7 — service ドメイン（F07.1 テンプレート CRUD・チーム/組織）API 契約テスト。
 *
 * <p>{@code ServiceRecordTemplateController} が委譲する {@code ServiceRecordTemplateService} は
 * {@code AccessControlService} を保持しておらず、9 エンドポイントすべてでスコープ認可が
 * 未回収のまま残っていた構造だった。本テストは是正した認可の契約を固定する。</p>
 *
 * <p>金型: {@code ServiceRecordFieldScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 *
 * <p><b>象限</b>: 非メンバー（outsider）/ 別 scope ADMIN（BOLA: teamB/orgB の ADMIN が
 * teamA/orgA のテンプレートへアクセス）/ 非 ADMIN メンバー / 正当 ADMIN。加えて
 * <b>path の scopeId と entity の scopeId の不一致（BOLA）は 404 で存在秘匿</b>することを検証する。</p>
 *
 * <p>権限粒度は兄弟 {@code ServiceRecordFieldService} に揃えた:
 * 参照（list / get）= {@code checkMembership}、変更（create / update / delete）
 * = {@code checkAdminOrAbove}。チーム／組織は対称構造のため同一粒度で両方を検証する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("service ドメイン（テンプレート）認可契約テスト")
class ServiceRecordTemplateScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ServiceRecordTemplateRepository templateRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgAId;
    private Long orgBId;

    private Long adminTeamAId;   // TEAM A の ADMIN（正当）
    private Long adminTeamBId;   // TEAM B の ADMIN（別 scope の越境検証用）
    private Long memberTeamAId;  // TEAM A の非 ADMIN メンバー
    private Long outsiderId;     // どこにも所属しない非メンバー

    private Long adminOrgAId;    // ORG A の ADMIN（正当）
    private Long adminOrgBId;    // ORG B の ADMIN（別 scope の越境検証用）
    private Long memberOrgAId;   // ORG A の非 ADMIN メンバー

    private Long templateTeamAId;  // TEAM A のテンプレート
    private Long templateTeamBId;  // TEAM B のテンプレート（BOLA 検証用）
    private Long templateOrgAId;   // ORG A のテンプレート
    private Long templateOrgBId;   // ORG B のテンプレート（BOLA 検証用）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("SRTAUTHZ チームA");
        teamBId = insertTeam("SRTAUTHZ チームB");
        orgAId = insertOrganization("SRTAUTHZ 組織A");
        orgBId = insertOrganization("SRTAUTHZ 組織B");

        adminTeamAId = insertUser("srtauthz-admin-team-a@example.com");
        adminTeamBId = insertUser("srtauthz-admin-team-b@example.com");
        memberTeamAId = insertUser("srtauthz-member-team-a@example.com");
        outsiderId = insertUser("srtauthz-outsider@example.com");
        adminOrgAId = insertUser("srtauthz-admin-org-a@example.com");
        adminOrgBId = insertUser("srtauthz-admin-org-b@example.com");
        memberOrgAId = insertUser("srtauthz-member-org-a@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        MembershipTestHelper.insertMembership(em, adminOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminOrgBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, memberOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);

        ServiceRecordTemplateEntity templateTeamA = templateRepository.save(ServiceRecordTemplateEntity.builder()
                .teamId(teamAId).name("SRTAUTHZ チームAテンプレート")
                .build());
        templateTeamAId = templateTeamA.getId();

        ServiceRecordTemplateEntity templateTeamB = templateRepository.save(ServiceRecordTemplateEntity.builder()
                .teamId(teamBId).name("SRTAUTHZ チームBテンプレート")
                .build());
        templateTeamBId = templateTeamB.getId();

        ServiceRecordTemplateEntity templateOrgA = templateRepository.save(ServiceRecordTemplateEntity.builder()
                .organizationId(orgAId).name("SRTAUTHZ 組織Aテンプレート")
                .build());
        templateOrgAId = templateOrgA.getId();

        ServiceRecordTemplateEntity templateOrgB = templateRepository.save(ServiceRecordTemplateEntity.builder()
                .organizationId(orgBId).name("SRTAUTHZ 組織Bテンプレート")
                .build());
        templateOrgBId = templateOrgB.getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /teams/{teamId}/service-records/templates（一覧: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/service-records/templates（一覧）")
    class ListTeamTemplates {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/templates", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/templates", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200（参照はmembershipで足りる）")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/templates", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/templates", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("organizationId指定時、当該組織の非メンバーは403（越境閲覧防止）")
        void 組織非メンバーのorganizationId指定は403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/templates", teamAId)
                            .param("organizationId", orgAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("organizationId指定時、team/org双方のメンバーなら200")
        void team_org双方のメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/templates", teamAId)
                            .param("organizationId", orgAId.toString()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /teams/{teamId}/service-records/templates（作成: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /teams/{teamId}/service-records/templates（作成）")
    class CreateTeamTemplate {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-records/templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-records/templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-records/templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-records/templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "新規テンプレート");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. GET /teams/{teamId}/service-records/templates/{id}（詳細: entity 由来 scope）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET /teams/{teamId}/service-records/templates/{id}（詳細）")
    class GetTeamTemplate {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/templates/{id}", teamAId, templateTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/templates/{id}", teamAId, templateTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他チームのテンプレートIDを自チームのteamIdで叩くと404（存在秘匿・BOLA）")
        void 越境テンプレートIDは404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/templates/{id}", teamAId, templateTeamBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバーは200（参照はmembershipで足りる）")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/templates/{id}", teamAId, templateTeamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/templates/{id}", teamAId, templateTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. PUT /teams/{teamId}/service-records/templates/{id}（更新: entity 由来 scope）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PUT /teams/{teamId}/service-records/templates/{id}（更新）")
    class UpdateTeamTemplate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/service-records/templates/{id}", teamAId, templateTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/service-records/templates/{id}", teamAId, templateTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他チームのテンプレートIDを自チームのteamIdで叩くと404（存在秘匿・BOLA）")
        void 越境テンプレートIDは404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/service-records/templates/{id}", teamAId, templateTeamBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/service-records/templates/{id}", teamAId, templateTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "更新後テンプレート");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. DELETE /teams/{teamId}/service-records/templates/{id}（削除: entity 由来 scope）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. DELETE /teams/{teamId}/service-records/templates/{id}（削除）")
    class DeleteTeamTemplate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/service-records/templates/{id}", teamAId, templateTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/service-records/templates/{id}", teamAId, templateTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他チームのテンプレートIDを自チームのteamIdで叩くと404（存在秘匿・BOLA）")
        void 越境テンプレートIDは404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/service-records/templates/{id}", teamAId, templateTeamBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/service-records/templates/{id}", teamAId, templateTeamAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. GET /organizations/{orgId}/service-records/templates（一覧: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. GET /organizations/{orgId}/service-records/templates（一覧）")
    class ListOrgTemplates {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/service-records/templates", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（orgBのADMIN）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/service-records/templates", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200（参照はmembershipで足りる）")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/service-records/templates", orgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/service-records/templates", orgAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. POST /organizations/{orgId}/service-records/templates（作成: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. POST /organizations/{orgId}/service-records/templates（作成）")
    class CreateOrgTemplate {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/service-records/templates", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/service-records/templates", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/service-records/templates", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/service-records/templates", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "新規組織テンプレート");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. PUT /organizations/{orgId}/service-records/templates/{id}（更新: entity 由来 scope）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. PUT /organizations/{orgId}/service-records/templates/{id}（更新）")
    class UpdateOrgTemplate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/service-records/templates/{id}", orgAId, templateOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/service-records/templates/{id}", orgAId, templateOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他組織のテンプレートIDを自組織のorgIdで叩くと404（存在秘匿・BOLA）")
        void 越境テンプレートIDは404() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/service-records/templates/{id}", orgAId, templateOrgBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/service-records/templates/{id}", orgAId, templateOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "更新後組織テンプレート");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. DELETE /organizations/{orgId}/service-records/templates/{id}（削除: entity 由来 scope）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. DELETE /organizations/{orgId}/service-records/templates/{id}（削除）")
    class DeleteOrgTemplate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/service-records/templates/{id}", orgAId, templateOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/service-records/templates/{id}", orgAId, templateOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他組織のテンプレートIDを自組織のorgIdで叩くと404（存在秘匿・BOLA）")
        void 越境テンプレートIDは404() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/service-records/templates/{id}", orgAId, templateOrgBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/service-records/templates/{id}", orgAId, templateOrgAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, java.util.List.of()));
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
                                + "VALUES (:email, 'SRTAUTHZ', 'テスト', 'SRTAUTHZ テスト', 'ACTIVE', "
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
                                + "CONCAT('o-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
