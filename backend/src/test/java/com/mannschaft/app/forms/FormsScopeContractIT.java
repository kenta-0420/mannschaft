package com.mannschaft.app.forms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.forms.entity.FormSubmissionEntity;
import com.mannschaft.app.forms.entity.FormTemplateEntity;
import com.mannschaft.app.forms.entity.FormTemplateFieldEntity;
import com.mannschaft.app.forms.repository.FormSubmissionRepository;
import com.mannschaft.app.forms.repository.FormTemplateFieldRepository;
import com.mannschaft.app.forms.repository.FormTemplateRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3 トランシェB4 — forms ドメイン（F05.7 書類テンプレート・フォームビルダー）
 * API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: forms ドメイン全体が {@code AccessControlService} 未配線の全体無防備状態だった
 * （FormTemplateController/FormSubmissionController/FormSubmissionAdminController/
 * FormCsvExportController/FormPdfController/FormRemindController/FormPresetController/
 * FormPresetCatalogController のいずれも認可チェックが皆無）。特に
 * {@code FormSubmissionController#getSubmission} は submissionId のみで取得しており、
 * 認証済みなら任意スコープの提出内容を閲覧できる重大な BOLA だった。</p>
 *
 * <p>金型: {@code ServiceRecordScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext）。Spring Security フィルタは無効化するが、越境 403/404 は
 * {@code AccessControlService.checkMembership}/{@code checkAdminOrAbove} または BOLA スコープ一致検証の
 * アプリケーション層例外として発生するためフィルタ無効でも検証できる。</p>
 *
 * <p><b>4象限</b>: 非メンバー（outsider）/ 別 scope ADMIN（teamB の ADMIN が teamA へアクセス、
 * BOLA 越境）/ 非 ADMIN メンバー（memberA）/ 正当 ADMIN（adminA）。閲覧系は checkMembership、
 * 作成/更新/削除/公開/複製/Export/リマインド/管理系は checkAdminOrAbove を期待する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("forms ドメイン（F05.7）認可契約テスト（試練）")
class FormsScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FormTemplateRepository templateRepository;

    @Autowired
    private FormTemplateFieldRepository fieldRepository;

    @Autowired
    private FormSubmissionRepository submissionRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;   // TEAM A の ADMIN（正当）
    private Long adminBId;   // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberAId;  // TEAM A の非 ADMIN メンバー
    private Long outsiderId; // どこにも所属しない非メンバー

    private Long templateAId;      // TEAM A の PUBLISHED テンプレート
    private Long submissionAId;    // TEAM A の SUBMITTED 提出（memberA が提出）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("FMAUTHZ チームA");
        teamBId = insertTeam("FMAUTHZ チームB");

        adminAId = insertUser("fmauthz-admin-a@example.com");
        adminBId = insertUser("fmauthz-admin-b@example.com");
        memberAId = insertUser("fmauthz-member-a@example.com");
        outsiderId = insertUser("fmauthz-outsider@example.com");

        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどのチームにも所属させない。

        FormTemplateEntity template = templateRepository.save(FormTemplateEntity.builder()
                .scopeType("teams")
                .scopeId(teamAId)
                .name("FMAUTHZ テンプレート")
                .createdBy(adminAId)
                .build());
        template.publish();
        templateRepository.save(template);
        templateAId = template.getId();
        fieldRepository.save(FormTemplateFieldEntity.builder()
                .templateId(templateAId)
                .fieldKey("name")
                .fieldLabel("氏名")
                .fieldType(FormFieldType.TEXT)
                .sortOrder(0)
                .build());

        FormSubmissionEntity submission = FormSubmissionEntity.builder()
                .templateId(templateAId)
                .scopeType("teams")
                .scopeId(teamAId)
                .submittedBy(memberAId)
                .build();
        submission.submit();
        submissionAId = submissionRepository.save(submission).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /teams/{teamId}/form-templates（一覧: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/form-templates（テンプレート一覧）")
    class ListTemplates {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/form-templates", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/form-templates", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/form-templates", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/form-templates", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /teams/{teamId}/form-templates/{id}（詳細: checkMembership + BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /teams/{teamId}/form-templates/{id}（テンプレート詳細）")
    class GetTemplate {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/form-templates/{id}", teamAId, templateAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境: teamBのADMINがteamAのtemplateIdをteamB URLで叩く→404で存在秘匿")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/form-templates/{id}", teamBId, templateAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/form-templates/{id}", teamAId, templateAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /teams/{teamId}/form-templates（作成: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST /teams/{teamId}/form-templates（テンプレート作成）")
    class CreateTemplate {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/form-templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTemplateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/form-templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTemplateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/form-templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTemplateBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createTemplateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "新規テンプレート");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. PUT /teams/{teamId}/form-templates/{id}（更新: checkAdminOrAbove + BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PUT /teams/{teamId}/form-templates/{id}（テンプレート更新）")
    class UpdateTemplate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/form-templates/{id}", teamAId, templateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTemplateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境: teamBのADMINがteamAのtemplateIdを更新→404で存在秘匿")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/form-templates/{id}", teamBId, templateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTemplateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/form-templates/{id}", teamAId, templateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTemplateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateTemplateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "更新後タイトル");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. DELETE /teams/{teamId}/form-templates/{id}（削除: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. DELETE /teams/{teamId}/form-templates/{id}（テンプレート削除）")
    class DeleteTemplate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/form-templates/{id}", teamAId, templateAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/form-templates/{id}", teamAId, templateAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. POST .../form-templates/{id}/publish・duplicate（checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. POST .../form-templates/{id}/duplicate（複製）")
    class DuplicateTemplate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/form-templates/{id}/duplicate", teamAId, templateAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/form-templates/{id}/duplicate", teamAId, templateAId))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. GET /teams/{teamId}/form-submissions/my（自分の提出一覧: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. GET /teams/{teamId}/form-submissions/my（自分の提出一覧）")
    class ListMySubmissions {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/form-submissions/my", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/form-submissions/my", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. GET /teams/{teamId}/form-submissions/{id}（提出詳細: checkMembership + BOLA 重大欠陥）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. GET /teams/{teamId}/form-submissions/{id}（提出詳細）")
    class GetSubmission {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/form-submissions/{id}", teamAId, submissionAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境: teamBのADMINがteamAの提出をteamB URLで叩く→404で存在秘匿（BOLA根治確認）")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/form-submissions/{id}", teamBId, submissionAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/form-submissions/{id}", teamAId, submissionAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. POST /teams/{teamId}/form-submissions（提出作成: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. POST /teams/{teamId}/form-submissions（提出作成）")
    class CreateSubmission {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/form-submissions", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSubmissionBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバー（一般メンバーの提出行為）は201")
        void 非ADMINメンバーは201() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/form-submissions", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSubmissionBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createSubmissionBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("templateId", templateAId);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. GET .../form-templates/{templateId}/submissions（管理者向け提出一覧: checkAdminOrAbove + BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. GET .../form-templates/{templateId}/submissions（管理者向け提出一覧）")
    class ListSubmissionsAdmin {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(
                            "/api/v1/teams/{teamId}/form-templates/{templateId}/submissions",
                            teamAId, templateAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境: teamBのADMINがteamAのtemplateIdをteamB URLで叩く→404で存在秘匿")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get(
                            "/api/v1/teams/{teamId}/form-templates/{templateId}/submissions",
                            teamBId, templateAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(
                            "/api/v1/teams/{teamId}/form-templates/{templateId}/submissions",
                            teamAId, templateAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. POST .../submissions/{id}/approve（承認: checkAdminOrAbove + BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. POST .../submissions/{id}/approve（提出承認）")
    class ApproveSubmission {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post(
                            "/api/v1/teams/{teamId}/form-templates/{templateId}/submissions/{id}/approve",
                            teamAId, templateAId, submissionAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境: teamBのADMINがteamAの提出を承認しようとする→404で存在秘匿（BOLA）")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post(
                            "/api/v1/teams/{teamId}/form-templates/{templateId}/submissions/{id}/approve",
                            teamBId, templateAId, submissionAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post(
                            "/api/v1/teams/{teamId}/form-templates/{templateId}/submissions/{id}/approve",
                            teamAId, templateAId, submissionAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 12. GET .../form-templates/{templateId}/submissions/export（CSV: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. GET .../form-templates/{templateId}/submissions/export（CSVエクスポート）")
    class ExportCsv {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(
                            "/api/v1/teams/{teamId}/form-templates/{templateId}/submissions/export",
                            teamAId, templateAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(
                            "/api/v1/teams/{teamId}/form-templates/{templateId}/submissions/export",
                            teamAId, templateAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 13. POST .../form-submissions/{id}/pdf（PDF生成: 提出者/作成者/ADMIN）
    //
    // 正当系（200）は実 PDF 生成 + StorageService.upload（R2/S3）を実呼びしてしまうため、
    // 金型 ServiceRecordScopeContractIT の「アップロードURL発行」テスト同様に本契約テストでは
    // 非メンバー拒否（403）のみを検証する。ensureViewerCanAccess は Storage 呼び出しより前に
    // 評価されるため、403 判定単体は Storage 未モックでも安全に検証できる。
    // ADMIN 経路の過小権限バグ根治（isAdminOrAbove 追加）は FormPdfServiceTest（単体・Storage モック済）
    // で担保する。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("13. POST .../form-submissions/{id}/pdf（PDF生成）")
    class GeneratePdf {

        @Test
        @DisplayName("無関係の非メンバーは403")
        void 無関係の非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/form-submissions/{id}/pdf", teamAId, submissionAId))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 14. POST .../form-templates/{templateId}/remind（リマインド: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14. POST .../form-templates/{templateId}/remind-specific（特定者向けリマインド）")
    class RemindSpecific {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post(
                            "/api/v1/teams/{teamId}/form-templates/{templateId}/remind-specific",
                            teamAId, templateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(remindBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post(
                            "/api/v1/teams/{teamId}/form-templates/{templateId}/remind-specific",
                            teamAId, templateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(remindBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> remindBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userIds", List.of(memberAId));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 15. GET /teams/{teamId}/form-presets（プリセットカタログ: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("15. GET /teams/{teamId}/form-presets（プリセットカタログ）")
    class PresetCatalog {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/form-presets", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/form-presets", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 16. /api/v1/admin/form-presets（SYSTEM_ADMIN限定・全体無防備の根治確認）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("16. /api/v1/admin/form-presets（SYSTEM_ADMIN限定プリセット管理）")
    class AdminPresets {

        @Test
        @DisplayName("チームADMIN（SYSTEM_ADMINではない）は一覧取得403")
        void チームADMINは403() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/admin/form-presets"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/admin/form-presets"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SYSTEM_ADMINは200")
        void SYSTEM_ADMINは200() throws Exception {
            setAuth(outsiderId);
            MembershipTestHelper.insertUserRole(em, outsiderId, "SYSTEM_ADMIN", null, null);
            em.flush();
            mockMvc.perform(get("/api/v1/admin/form-presets"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("チームADMIN（SYSTEM_ADMINではない）はプリセット作成403")
        void チームADMINは作成403() throws Exception {
            setAuth(adminAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "不正プリセット");
            body.put("fieldsJson", "[]");
            mockMvc.perform(post("/api/v1/admin/form-presets")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
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
                                + "VALUES (:email, 'FMAUTHZ', 'テスト', 'FMAUTHZ テスト', 'ACTIVE', "
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
}
