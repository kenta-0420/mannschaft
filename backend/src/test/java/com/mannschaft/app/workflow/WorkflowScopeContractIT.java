package com.mannschaft.app.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.workflow.entity.WorkflowRequestApproverEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestCommentEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestStepEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateStepEntity;
import com.mannschaft.app.workflow.repository.WorkflowRequestApproverRepository;
import com.mannschaft.app.workflow.repository.WorkflowRequestCommentRepository;
import com.mannschaft.app.workflow.repository.WorkflowRequestRepository;
import com.mannschaft.app.workflow.repository.WorkflowRequestStepRepository;
import com.mannschaft.app.workflow.repository.WorkflowTemplateRepository;
import com.mannschaft.app.workflow.repository.WorkflowTemplateStepRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 2 トランシェ2C — workflow ドメイン（稟議/申請ワークフロー・承認・
 * コメント・添付ファイル）API 契約テスト（試練）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md} トランシェ2C workflow 節。
 * workflow ドメインは {@code AccessControlService} が一切敷設されておらず、任意チーム/組織の
 * ワークフローテンプレート・申請・承認・コメント・添付ファイルを閲覧・操作できる状態だった。</p>
 *
 * <p>金型: {@code EquipmentScopeContractIT} / {@code ChartScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL + 手動 SecurityContext +
 * {@code MembershipTestHelper}）。</p>
 *
 * <p><b>象限</b>: 無認可（非メンバー/非所有者）403、BOLA 越境（entity 由来スコープ不一致）404、
 * 正当権限成功。承認/コメント/添付は URL にスコープを含まないため、非所属者へは
 * 404（{@code *_NOT_FOUND}）で存在秘匿する設計になっている点に注意（テンプレート/申請の
 * スコープ付き一覧・詳細エンドポイントは checkMembership/checkAdminOrAbove による 403）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("workflow ドメイン（稟議/申請ワークフロー）認可契約テスト（試練）")
class WorkflowScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkflowTemplateRepository templateRepository;

    @Autowired
    private WorkflowTemplateStepRepository templateStepRepository;

    @Autowired
    private WorkflowRequestRepository requestRepository;

    @Autowired
    private WorkflowRequestStepRepository requestStepRepository;

    @Autowired
    private WorkflowRequestApproverRepository requestApproverRepository;

    @Autowired
    private WorkflowRequestCommentRepository commentRepository;

    @PersistenceContext
    private EntityManager em;

    /** R2 は外部依存（テスト環境にエンドポイント未設定）のため mock（presign 成功パスの検証に使用）。 */
    @MockitoBean
    private R2StorageService r2StorageService;

    private Long teamAId;
    private Long teamBId;
    private Long orgAId;
    private Long orgBId;

    private Long adminTeamAId;    // TEAM A の ADMIN（正当）
    private Long adminTeamBId;    // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId;   // TEAM A の非 ADMIN メンバー（申請者本人）
    private Long otherMemberTeamAId; // TEAM A の別の非 ADMIN メンバー（非所有者・非承認者）
    private Long thirdMemberTeamAId; // TEAM A のさらに別の非 ADMIN メンバー（コメント/添付の完全な第三者）
    private Long adminOrgAId;     // ORG A の ADMIN（正当）
    private Long adminOrgBId;     // ORG B の ADMIN（別 scope の越境攻撃者）
    private Long memberOrgAId;    // ORG A の非 ADMIN メンバー
    private Long outsiderId;      // どこにも所属しない非メンバー

    private Long templateTeamAId;   // TEAM A の有効テンプレート（承認ステップ1つ・承認者=otherMemberTeamAId）
    private Long templateTeamBId;   // TEAM B のテンプレート（BOLA越境検証用）
    private Long templateOrgAId;    // ORG A のテンプレート

    private Long draftRequestId;       // TEAM A・DRAFT・requestedBy=memberTeamAId
    private Long inProgressRequestId;  // TEAM A・IN_PROGRESS・現在ステップ承認者=otherMemberTeamAId
    private Long requestTeamBId;       // TEAM B の申請（BOLA越境検証用）

    private Long commentByMemberId;    // inProgressRequestId 上の memberTeamAId 作成コメント

    @BeforeEach
    void setUp() {
        given(r2StorageService.generateUploadUrl(anyString(), anyString(), any(Duration.class)))
                .willReturn(new PresignedUploadResult("https://r2.example.com/signed-upload", "workflow-attachments/dummy.bin", 900L));

        teamAId = insertTeam("WFAUTHZ チームA");
        teamBId = insertTeam("WFAUTHZ チームB");
        orgAId = insertOrganization("WFAUTHZ 組織A");
        orgBId = insertOrganization("WFAUTHZ 組織B");

        adminTeamAId = insertUser("wfauthz-admin-team-a@example.com");
        adminTeamBId = insertUser("wfauthz-admin-team-b@example.com");
        memberTeamAId = insertUser("wfauthz-member-team-a@example.com");
        otherMemberTeamAId = insertUser("wfauthz-other-member-team-a@example.com");
        thirdMemberTeamAId = insertUser("wfauthz-third-member-team-a@example.com");
        adminOrgAId = insertUser("wfauthz-admin-org-a@example.com");
        adminOrgBId = insertUser("wfauthz-admin-org-b@example.com");
        memberOrgAId = insertUser("wfauthz-member-org-a@example.com");
        outsiderId = insertUser("wfauthz-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（EquipmentScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, otherMemberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, thirdMemberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        MembershipTestHelper.insertMembership(em, adminOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminOrgBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, memberOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        // --- テンプレート ---
        WorkflowTemplateEntity templateTeamA = templateRepository.save(WorkflowTemplateEntity.builder()
                .scopeType("teams").scopeId(teamAId).name("WFAUTHZ 稟議テンプレート")
                .isSealRequired(false).isActive(true).sortOrder(0).createdBy(adminTeamAId)
                .build());
        templateTeamAId = templateTeamA.getId();
        templateStepRepository.save(WorkflowTemplateStepEntity.builder()
                .templateId(templateTeamAId).stepOrder(1).name("一次承認")
                .approvalType(ApprovalType.ALL).approverType(ApproverType.USER)
                .approverUserIds("[" + otherMemberTeamAId + "]")
                .build());

        WorkflowTemplateEntity templateTeamB = templateRepository.save(WorkflowTemplateEntity.builder()
                .scopeType("teams").scopeId(teamBId).name("WFAUTHZ チームB テンプレート")
                .isSealRequired(false).isActive(true).sortOrder(0).createdBy(adminTeamBId)
                .build());
        templateTeamBId = templateTeamB.getId();

        WorkflowTemplateEntity templateOrgA = templateRepository.save(WorkflowTemplateEntity.builder()
                .scopeType("organizations").scopeId(orgAId).name("WFAUTHZ 組織A テンプレート")
                .isSealRequired(false).isActive(true).sortOrder(0).createdBy(adminOrgAId)
                .build());
        templateOrgAId = templateOrgA.getId();

        // --- 申請（DRAFT・memberTeamAId 所有） ---
        WorkflowRequestEntity draftRequest = requestRepository.save(WorkflowRequestEntity.builder()
                .templateId(templateTeamAId).scopeType("teams").scopeId(teamAId)
                .title("WFAUTHZ 下書き申請").requestedBy(memberTeamAId)
                .build());
        draftRequestId = draftRequest.getId();

        // --- 申請（IN_PROGRESS・現在ステップの承認者=otherMemberTeamAId） ---
        WorkflowRequestEntity inProgressRequest = requestRepository.save(WorkflowRequestEntity.builder()
                .templateId(templateTeamAId).scopeType("teams").scopeId(teamAId)
                .title("WFAUTHZ 進行中申請").requestedBy(memberTeamAId)
                .status(WorkflowStatus.IN_PROGRESS).currentStepOrder(1).requestedAt(LocalDateTime.now())
                .build());
        inProgressRequestId = inProgressRequest.getId();
        WorkflowRequestStepEntity step = requestStepRepository.save(WorkflowRequestStepEntity.builder()
                .requestId(inProgressRequestId).stepOrder(1).status(StepStatus.IN_PROGRESS)
                .build());
        requestApproverRepository.save(WorkflowRequestApproverEntity.builder()
                .requestStepId(step.getId()).approverUserId(otherMemberTeamAId)
                .build());

        WorkflowRequestCommentEntity comment = commentRepository.save(WorkflowRequestCommentEntity.builder()
                .requestId(inProgressRequestId).userId(memberTeamAId).body("WFAUTHZ コメント本文")
                .build());
        commentByMemberId = comment.getId();

        // --- 申請（TEAM B・BOLA越境検証用） ---
        WorkflowRequestEntity requestTeamB = requestRepository.save(WorkflowRequestEntity.builder()
                .templateId(templateTeamBId).scopeType("teams").scopeId(teamBId)
                .title("WFAUTHZ チームB申請").requestedBy(adminTeamBId)
                .build());
        requestTeamBId = requestTeamB.getId();

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /teams/{teamId}/workflow-templates（一覧・閲覧系: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/workflow-templates（テンプレート一覧）")
    class TemplateList {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/teams/{teamId}/workflow-templates", teamAId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/workflow-templates", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/workflow-templates", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/workflow-templates", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/workflow-templates", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /teams/{teamId}/workflow-templates（作成・変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /teams/{teamId}/workflow-templates（テンプレート作成）")
    class TemplateCreate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/workflow-templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTemplateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/workflow-templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTemplateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/workflow-templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTemplateBody())))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. GET/PUT/DELETE /teams/{teamId}/workflow-templates/{id}（entity由来BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET/PUT/DELETE /teams/{teamId}/workflow-templates/{id}")
    class TemplateDetailUpdateDelete {

        @Test
        @DisplayName("詳細: 非メンバーは403")
        void 詳細非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/workflow-templates/{id}", teamAId, templateTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("詳細: BOLA越境（teamBのテンプレIDをteamAパスで叩く）は404")
        void 詳細BOLA越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/workflow-templates/{id}", teamAId, templateTeamBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("詳細: 正当メンバーは200")
        void 詳細正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/workflow-templates/{id}", teamAId, templateTeamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("更新: 非ADMINメンバーは403")
        void 更新非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/workflow-templates/{id}", teamAId, templateTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTemplateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("更新: BOLA越境は404")
        void 更新BOLA越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/workflow-templates/{id}", teamAId, templateTeamBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTemplateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("更新: 正当ADMINは200")
        void 更新正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/workflow-templates/{id}", teamAId, templateTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTemplateBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("削除: 非ADMINメンバーは403")
        void 削除非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/workflow-templates/{id}", teamAId, templateTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("削除: BOLA越境は404")
        void 削除BOLA越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/workflow-templates/{id}", teamAId, templateTeamBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("削除: 正当ADMINは204")
        void 削除正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            WorkflowTemplateEntity deletable = templateRepository.save(WorkflowTemplateEntity.builder()
                    .scopeType("teams").scopeId(teamAId).name("WFAUTHZ 削除対象")
                    .isSealRequired(false).isActive(true).sortOrder(0).createdBy(adminTeamAId)
                    .build());
            em.flush();
            mockMvc.perform(delete("/api/v1/teams/{teamId}/workflow-templates/{id}", teamAId, deletable.getId()))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. POST .../activate・.../deactivate（変更系: checkAdminOrAbove、entity由来BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. POST activate/deactivate")
    class TemplateActivateDeactivate {

        @Test
        @DisplayName("非ADMINメンバーは有効化403")
        void 非ADMINメンバーは有効化403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/workflow-templates/{id}/activate", teamAId, templateTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA越境は有効化404")
        void BOLA越境は有効化404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/workflow-templates/{id}/activate", teamAId, templateTeamBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは無効化200")
        void 正当ADMINは無効化200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/workflow-templates/{id}/deactivate", teamAId, templateTeamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは有効化200")
        void 正当ADMINは有効化200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/workflow-templates/{id}/activate", teamAId, templateTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 組織スコープ /organizations/{orgId}/workflow-templates（ScopeType正規化検証）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. 組織スコープ /organizations/{orgId}/workflow-templates")
    class TemplateOrganizationScope {

        @Test
        @DisplayName("非メンバーは一覧403")
        void 非メンバーは一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/workflow-templates", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（orgBのADMIN）は一覧403（BOLA）")
        void 別scopeADMINは一覧403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/workflow-templates", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは一覧200")
        void 正当ADMINは一覧200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/workflow-templates", orgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは詳細取得200")
        void 非ADMINメンバーは詳細取得200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/workflow-templates/{id}", orgAId, templateOrgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは作成403")
        void 非ADMINメンバーは作成403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/workflow-templates", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTemplateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは作成201")
        void 正当ADMINは作成201() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/workflow-templates", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTemplateBody())))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. GET/POST /teams/{teamId}/workflow-requests（一覧・作成: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. GET/POST /teams/{teamId}/workflow-requests")
    class RequestListCreate {

        @Test
        @DisplayName("一覧: 未認証は401")
        void 一覧未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/teams/{teamId}/workflow-requests", teamAId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("一覧: 非メンバーは403")
        void 一覧非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/workflow-requests", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一覧: 正当メンバーは200")
        void 一覧正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/workflow-requests", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("作成: 非メンバーは403")
        void 作成非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/workflow-requests", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequestBody(templateTeamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("作成: 別scopeのtemplateIdを指定するとBOLA越境404")
        void 作成BOLA越境は404() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/workflow-requests", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequestBody(templateTeamBId))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("作成: 正当メンバーは201")
        void 作成正当メンバーは201() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/workflow-requests", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequestBody(templateTeamAId))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. GET /teams/{teamId}/workflow-requests/{id}（詳細: 所有者/メンバー可視、BOLA404）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. GET /teams/{teamId}/workflow-requests/{id}（申請詳細）")
    class RequestDetail {

        @Test
        @DisplayName("非メンバー（outsider）は404（存在秘匿）")
        void 非メンバーは404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/workflow-requests/{id}", teamAId, draftRequestId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("BOLA越境（teamBの申請IDをteamAパスで叩く）は404")
        void BOLA越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/workflow-requests/{id}", teamAId, requestTeamBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("所有者本人は200")
        void 所有者本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/workflow-requests/{id}", teamAId, draftRequestId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非所有者でもスコープメンバーは200（承認者候補のため可視）")
        void 非所有者スコープメンバーは200() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/workflow-requests/{id}", teamAId, draftRequestId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. PUT/submit/withdraw/DELETE 申請（所有者 or entity由来ADMIN: checkOwnerOrAdmin）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. PUT/submit/withdraw/DELETE 申請（本人 or ADMIN）")
    class RequestOwnerOrAdminOperations {

        @Test
        @DisplayName("更新: 非所有者・非ADMINメンバーは403")
        void 更新非所有者は403() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/workflow-requests/{id}", teamAId, draftRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequestBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("更新: 所有者本人は200")
        void 更新所有者本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/workflow-requests/{id}", teamAId, draftRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequestBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("更新: ADMINは200")
        void 更新ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/workflow-requests/{id}", teamAId, draftRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequestBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("提出: 非所有者・非ADMINメンバーは403")
        void 提出非所有者は403() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/workflow-requests/{id}/submit", teamAId, draftRequestId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("提出: 所有者本人は200")
        void 提出所有者本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/workflow-requests/{id}/submit", teamAId, draftRequestId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("取り下げ: 非所有者・非ADMINメンバーは403")
        void 取り下げ非所有者は403() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/workflow-requests/{id}/withdraw",
                            teamAId, inProgressRequestId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("取り下げ: 所有者本人は200")
        void 取り下げ所有者本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/workflow-requests/{id}/withdraw",
                            teamAId, inProgressRequestId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("削除: 非所有者・非ADMINメンバーは403")
        void 削除非所有者は403() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/workflow-requests/{id}", teamAId, draftRequestId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("削除: 所有者本人は204")
        void 削除所有者本人は204() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/workflow-requests/{id}", teamAId, draftRequestId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("削除: BOLA越境は404")
        void 削除BOLA越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/workflow-requests/{id}", teamAId, requestTeamBId))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. POST /workflow-requests/{id}/decide（承認判断: entity由来メンバー判定＋NOT_APPROVER）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. POST /workflow-requests/{id}/decide（承認判断）")
    class ApprovalDecide {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/workflow-requests/{id}/decide", inProgressRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(decideBody("APPROVED"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非所属者（outsider）は404（存在秘匿）")
        void 非所属者は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/workflow-requests/{id}/decide", inProgressRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(decideBody("APPROVED"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("スコープメンバーだが指定承認者でなければ403（NOT_APPROVER）")
        void 非承認者は403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/workflow-requests/{id}/decide", inProgressRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(decideBody("APPROVED"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("指定承認者本人は200")
        void 指定承認者本人は200() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(post("/api/v1/workflow-requests/{id}/decide", inProgressRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(decideBody("APPROVED"))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. GET/POST /workflow-requests/{id}/comments（コメント: entity由来メンバー判定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. GET/POST .../comments")
    class CommentListCreate {

        @Test
        @DisplayName("一覧: 非所属者は404（存在秘匿）")
        void 一覧非所属者は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/workflow-requests/{id}/comments", inProgressRequestId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("一覧: スコープメンバーは200")
        void 一覧スコープメンバーは200() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(get("/api/v1/workflow-requests/{id}/comments", inProgressRequestId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("作成: 非所属者は404")
        void 作成非所属者は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/workflow-requests/{id}/comments", inProgressRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(commentBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("作成: スコープメンバーは201")
        void 作成スコープメンバーは201() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(post("/api/v1/workflow-requests/{id}/comments", inProgressRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(commentBody())))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. PUT/DELETE /workflow-requests/{id}/comments/{commentId}
    //     （更新: 作成者本人のみ。削除: 作成者本人 or entity由来ADMIN）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. PUT/DELETE .../comments/{commentId}")
    class CommentUpdateDelete {

        @Test
        @DisplayName("更新: 非所属者は404")
        void 更新非所属者は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(put("/api/v1/workflow-requests/{id}/comments/{commentId}",
                            inProgressRequestId, commentByMemberId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(commentBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("更新: 作成者本人以外（ADMINでも）は403")
        void 更新非作成者は403() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/workflow-requests/{id}/comments/{commentId}",
                            inProgressRequestId, commentByMemberId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(commentBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("更新: 作成者本人は200")
        void 更新作成者本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/workflow-requests/{id}/comments/{commentId}",
                            inProgressRequestId, commentByMemberId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(commentBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("削除: 非所属者は404")
        void 削除非所属者は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/workflow-requests/{id}/comments/{commentId}",
                            inProgressRequestId, commentByMemberId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("削除: 作成者でも管理者でもない別メンバーは403")
        void 削除無関係メンバーは403() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(delete("/api/v1/workflow-requests/{id}/comments/{commentId}",
                            inProgressRequestId, commentByMemberId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("削除: 作成者本人は204")
        void 削除作成者本人は204() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/workflow-requests/{id}/comments/{commentId}",
                            inProgressRequestId, commentByMemberId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("削除: 作成者でなくてもentity由来ADMINは204")
        void 削除ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/workflow-requests/{id}/comments/{commentId}",
                            inProgressRequestId, commentByMemberId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 12. GET/POST 添付ファイル一覧・presign・登録（entity由来メンバー判定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. GET/POST .../attachments・upload-url")
    class AttachmentListPresignRegister {

        @Test
        @DisplayName("一覧: 非所属者は404")
        void 一覧非所属者は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/workflow-requests/{id}/attachments", inProgressRequestId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("一覧: スコープメンバーは200")
        void 一覧スコープメンバーは200() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(get("/api/v1/workflow-requests/{id}/attachments", inProgressRequestId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("presign: 非所属者は404")
        void presign非所属者は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/workflow-requests/{id}/upload-url", inProgressRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(presignBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("presign: スコープメンバーは200")
        void presignスコープメンバーは200() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(post("/api/v1/workflow-requests/{id}/upload-url", inProgressRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(presignBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("登録: 非所属者は404")
        void 登録非所属者は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/workflow-requests/{id}/attachments", inProgressRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerBody(inProgressRequestId))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("登録: スコープメンバーは201")
        void 登録スコープメンバーは201() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(post("/api/v1/workflow-requests/{id}/attachments", inProgressRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerBody(inProgressRequestId))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 13. DELETE 添付ファイル（アップロード者/申請者本人 or entity由来ADMIN）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("13. DELETE .../attachments/{attachmentId}")
    class AttachmentDelete {

        @Test
        @DisplayName("非所属者は404")
        void 非所属者は404() throws Exception {
            Long attachmentId = registerAttachment(otherMemberTeamAId);
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/workflow-requests/{id}/attachments/{attachmentId}",
                            inProgressRequestId, attachmentId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("アップロード者でも申請者でもADMINでもない別メンバーは403")
        void 無関係メンバーは403() throws Exception {
            // otherMemberTeamAId がアップロード者、memberTeamAId が申請者本人。
            // いずれでもない scope メンバー thirdMemberTeamAId は削除不可（403）。
            Long attachmentId = registerAttachment(otherMemberTeamAId);
            setAuth(thirdMemberTeamAId);
            mockMvc.perform(delete("/api/v1/workflow-requests/{id}/attachments/{attachmentId}",
                            inProgressRequestId, attachmentId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非所属者は404（スコープ外のBOLA）")
        void 非所属のBOLAは404() throws Exception {
            Long attachmentId = registerAttachment(otherMemberTeamAId);
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/workflow-requests/{id}/attachments/{attachmentId}",
                            inProgressRequestId, attachmentId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("アップロード者本人は204")
        void アップロード者本人は204() throws Exception {
            Long attachmentId = registerAttachment(otherMemberTeamAId);
            setAuth(otherMemberTeamAId);
            mockMvc.perform(delete("/api/v1/workflow-requests/{id}/attachments/{attachmentId}",
                            inProgressRequestId, attachmentId))
                    .andExpect(status().isNoContent());
        }

        private Long registerAttachment(Long uploaderUserId) throws Exception {
            setAuth(uploaderUserId);
            String body = objectMapper.writeValueAsString(registerBody(inProgressRequestId));
            String response = mockMvc.perform(post("/api/v1/workflow-requests/{id}/attachments",
                            inProgressRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(response);
            return json.get("data").get("id").asLong();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Map<String, Object> createTemplateBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "新規テンプレート");
        body.put("isSealRequired", false);
        return body;
    }

    private Map<String, Object> updateTemplateBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "更新後の名前");
        body.put("isSealRequired", false);
        body.put("version", 0);
        return body;
    }

    private Map<String, Object> createRequestBody(Long templateId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateId", templateId);
        body.put("title", "新規申請");
        return body;
    }

    private Map<String, Object> updateRequestBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "更新後の申請タイトル");
        body.put("version", 0);
        return body;
    }

    private Map<String, Object> decideBody(String decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", decision);
        return body;
    }

    private Map<String, Object> commentBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("body", "テストコメント本文");
        return body;
    }

    private Map<String, Object> presignBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentType", "image/png");
        body.put("fileSize", 1024);
        return body;
    }

    private Map<String, Object> registerBody(Long requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileKey", "workflow-attachments/" + requestId + "/" + java.util.UUID.randomUUID() + ".png");
        body.put("originalFilename", "test.png");
        body.put("fileSize", 1024);
        return body;
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
                                + "VALUES (:email, 'WFAUTHZ', 'テスト', 'WFAUTHZ テスト', 'ACTIVE', "
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
