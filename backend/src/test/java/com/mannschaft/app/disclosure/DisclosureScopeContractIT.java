package com.mannschaft.app.disclosure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.disclosure.controller.AbstractDisclosureIntegrationTest;
import com.mannschaft.app.disclosure.entity.DisclosureFormDraftEntity;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.mannschaft.app.disclosure.repository.DisclosureFormDraftRepository;
import com.mannschaft.app.disclosure.repository.DisclosureFormTemplateRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3 トランシェB4 — disclosure ドメイン（F09.14 重要事項説明書）
 * API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: disclosure ドメインは Phase 2-β-5 以降に持ち越しの FIXME
 * （「設計書 §2 で要求される ADMIN / DEPUTY_ADMIN 判定は Phase 2-β-5 以降で実装する」）が
 * 各 Controller に残っており、{@code SecurityUtils.getCurrentUserId()} による認証ガードのみで
 * 実質全体無防備だった。既存の {@code ensureScope}/{@code ensureSameOrganization} は
 * 「path の organizationId と entity の scope が一致するか」のみを検証しており、
 * 「呼び出し元がその組織に実際に所属しているか」は一切検証していなかった
 * （= 任意の認証済みユーザーが任意の実在 organizationId を騙れば CRUD できた）。</p>
 *
 * <p>金型: {@code ServiceRecordScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)}）
 * を disclosure 系統合テストの共通基盤 {@code AbstractDisclosureIntegrationTest}
 * （R2StorageService を {@code @MockitoBean} 化済み）に重ねる。</p>
 *
 * <p><b>3象限</b>: 非メンバー（outsider）/ 越境（orgB の ADMIN が orgA の draftId/exportId/templateId に
 * orgB の URL からアクセス、BOLA）/ 正当 ADMIN（adminA）。閲覧系は checkMembership、
 * 作成/更新/削除/自動引用更新/出力/期限延長/回覧開始は checkAdminOrAbove を期待する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("disclosure ドメイン（F09.14）認可契約テスト（試練）")
class DisclosureScopeContractIT extends AbstractDisclosureIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DisclosureFormTemplateRepository templateRepository;

    @Autowired
    private DisclosureFormDraftRepository draftRepository;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long orgBId;
    private Long adminAId;   // ORG A の ADMIN（正当）
    private Long adminBId;   // ORG B の ADMIN（別 scope の越境攻撃者）
    private Long memberAId;  // ORG A の非 ADMIN メンバー
    private Long outsiderId; // どこにも所属しない非メンバー

    private Long systemTemplateId; // 全組織から利用可能なシステム提供様式
    private Long customTemplateAId; // ORG A 専用カスタム様式
    private Long draftAId; // ORG A のドラフト

    @BeforeEach
    void setUp() {
        orgAId = insertOrganization("DXAUTHZ 組織A");
        orgBId = insertOrganization("DXAUTHZ 組織B");

        adminAId = insertUser("dxauthz-admin-a@example.com");
        adminBId = insertUser("dxauthz-admin-b@example.com");
        memberAId = insertUser("dxauthz-member-a@example.com");
        outsiderId = insertUser("dxauthz-outsider@example.com");

        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        // outsiderId はどの組織にも所属させない。

        systemTemplateId = saveSystemTemplate("DXAUTHZ_SYS_" + System.nanoTime(), "1.0",
                "{\"sections\":[{\"id\":\"basic\",\"title\":\"基本\",\"fields\":["
                        + "{\"id\":\"property_name\",\"label\":\"物件名\",\"type\":\"TEXT\"}]}]}");
        customTemplateAId = saveCustomTemplate("DXAUTHZ_CUSTOM_" + System.nanoTime(), "1.0", orgAId);

        DisclosureFormDraftEntity draft = draftRepository.save(DisclosureFormDraftEntity.builder()
                .scopeType("ORGANIZATION")
                .scopeId(orgAId)
                .templateId(systemTemplateId)
                .templateVersionSnapshot("1.0")
                .title("DXAUTHZ ドラフト")
                .formData("{}")
                .status(DraftStatus.DRAFT)
                .createdBy(adminAId)
                .updatedBy(adminAId)
                .build());
        draftAId = draft.getId();

        // R2 は実 SDK 呼び出しを避けるためモック化済み（AbstractDisclosureIntegrationTest）。
        // upload は void のため未スタブでも no-op、generateDownloadUrl は未スタブなら null を返すのみで
        // 例外は発生しないため、認可契約テスト（ステータスコード検証のみ）では明示スタブ不要。

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /organizations/{id}/disclosure-drafts（一覧: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /organizations/{id}/disclosure-drafts（ドラフト一覧）")
    class ListDrafts {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{id}/disclosure-drafts", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境: orgBのADMINがorgAへアクセス→403")
        void 越境ADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/organizations/{id}/disclosure-drafts", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/disclosure-drafts", orgAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /organizations/{id}/disclosure-drafts/{draftId}（詳細: checkMembership + BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /organizations/{id}/disclosure-drafts/{draftId}（ドラフト詳細）")
    class GetDraft {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{id}/disclosure-drafts/{draftId}", orgAId, draftAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境: orgBのADMINがorgAのdraftIdをorgB URLで叩く→404で存在秘匿（BOLA）")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/organizations/{id}/disclosure-drafts/{draftId}", orgBId, draftAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/disclosure-drafts/{draftId}", orgAId, draftAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /organizations/{id}/disclosure-drafts（作成: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST /organizations/{id}/disclosure-drafts（ドラフト作成）")
    class CreateDraft {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{id}/disclosure-drafts", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(draftBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/disclosure-drafts", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(draftBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/disclosure-drafts", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(draftBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> draftBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("templateId", systemTemplateId);
            body.put("title", "新規ドラフト");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. PUT /organizations/{id}/disclosure-drafts/{draftId}（更新: checkAdminOrAbove + BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PUT /organizations/{id}/disclosure-drafts/{draftId}（ドラフト更新）")
    class UpdateDraft {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put("/api/v1/organizations/{id}/disclosure-drafts/{draftId}", orgAId, draftAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境: orgBのADMINがorgAのdraftIdを更新しようとする→404で存在秘匿")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/organizations/{id}/disclosure-drafts/{draftId}", orgBId, draftAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/organizations/{id}/disclosure-drafts/{draftId}", orgAId, draftAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "更新後タイトル");
            body.put("version", 0);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. DELETE /organizations/{id}/disclosure-drafts/{draftId}（削除: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. DELETE /organizations/{id}/disclosure-drafts/{draftId}（ドラフト削除）")
    class DeleteDraft {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/organizations/{id}/disclosure-drafts/{draftId}", orgAId, draftAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/organizations/{id}/disclosure-drafts/{draftId}", orgAId, draftAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. POST .../disclosure-drafts/{draftId}/refresh-auto-fill（checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. POST .../disclosure-drafts/{draftId}/refresh-auto-fill（自動引用更新）")
    class RefreshAutoFill {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post(
                            "/api/v1/organizations/{id}/disclosure-drafts/{draftId}/refresh-auto-fill",
                            orgAId, draftAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post(
                            "/api/v1/organizations/{id}/disclosure-drafts/{draftId}/refresh-auto-fill",
                            orgAId, draftAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. POST .../disclosure-drafts/{draftId}/export（出力: checkAdminOrAbove）
    //
    // Storage は AbstractDisclosureIntegrationTest 経由で @MockitoBean 化済み（upload は void で
    // 未スタブでも no-op・generateDownloadUrl は未スタブなら null を返すのみで例外にならないため、
    // ステータスコードのみを検証する本契約テストでは明示スタブ不要）。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. POST .../disclosure-drafts/{draftId}/export（重説書出力）")
    class ExportDraft {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post(
                            "/api/v1/organizations/{id}/disclosure-drafts/{draftId}/export?format=pdf",
                            orgAId, draftAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post(
                            "/api/v1/organizations/{id}/disclosure-drafts/{draftId}/export?format=pdf",
                            orgAId, draftAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post(
                            "/api/v1/organizations/{id}/disclosure-drafts/{draftId}/export?format=pdf",
                            orgAId, draftAId))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. GET /organizations/{id}/disclosure-exports（一覧: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. GET /organizations/{id}/disclosure-exports（出力履歴一覧）")
    class ListExports {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{id}/disclosure-exports", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/disclosure-exports", orgAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. PATCH .../disclosure-exports/{exportId}/extend-expiry（checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. PATCH .../disclosure-exports/{exportId}/extend-expiry（期限延長）")
    class ExtendExpiry {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            Long exportId = createExportedRecord(adminAId);
            setAuth(memberAId);
            mockMvc.perform(patch(
                            "/api/v1/organizations/{id}/disclosure-exports/{exportId}/extend-expiry",
                            orgAId, exportId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(extendBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境: orgBのADMINがorgAのexportIdを延長しようとする→404で存在秘匿")
        void 越境IDは404() throws Exception {
            Long exportId = createExportedRecord(adminAId);
            setAuth(adminBId);
            mockMvc.perform(patch(
                            "/api/v1/organizations/{id}/disclosure-exports/{exportId}/extend-expiry",
                            orgBId, exportId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(extendBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            Long exportId = createExportedRecord(adminAId);
            setAuth(adminAId);
            mockMvc.perform(patch(
                            "/api/v1/organizations/{id}/disclosure-exports/{exportId}/extend-expiry",
                            orgAId, exportId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(extendBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> extendBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("newExpiresAt", LocalDateTime.now().plusDays(180).toString());
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. POST .../disclosure-exports/{exportId}/circulation（回覧開始: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. POST .../disclosure-exports/{exportId}/circulation（電子印鑑承認回覧開始）")
    class StartCirculation {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            Long exportId = createExportedRecord(adminAId);
            setAuth(memberAId);
            mockMvc.perform(post(
                            "/api/v1/organizations/{id}/disclosure-exports/{exportId}/circulation",
                            orgAId, exportId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(circulationBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境: orgBのADMINがorgAのexportIdに対して回覧開始しようとする→404で存在秘匿")
        void 越境IDは404() throws Exception {
            Long exportId = createExportedRecord(adminAId);
            setAuth(adminBId);
            mockMvc.perform(post(
                            "/api/v1/organizations/{id}/disclosure-exports/{exportId}/circulation",
                            orgBId, exportId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(circulationBody())))
                    .andExpect(status().isNotFound());
        }

        private Map<String, Object> circulationBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recipientUserIds", List.of(memberAId));
            body.put("circulationMode", "SIMULTANEOUS");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. GET /disclosure-templates（様式一覧: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. GET /disclosure-templates（様式一覧）")
    class ListTemplates {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/disclosure-templates")
                            .param("organizationId", orgAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/disclosure-templates")
                            .param("organizationId", orgAId.toString()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 12. GET /disclosure-templates/{id}（様式詳細: checkMembership + BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. GET /disclosure-templates/{id}（様式詳細）")
    class GetTemplate {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/disclosure-templates/{id}", customTemplateAId)
                            .param("organizationId", orgAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境: orgBのADMINがorgAのカスタム様式をorgB名義で取得→404で存在秘匿（BOLA）")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/disclosure-templates/{id}", customTemplateAId)
                            .param("organizationId", orgBId.toString()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/disclosure-templates/{id}", customTemplateAId)
                            .param("organizationId", orgAId.toString()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 13. POST /organizations/{id}/disclosure-templates（カスタム様式作成: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("13. POST /organizations/{id}/disclosure-templates（カスタム様式作成）")
    class CreateCustomTemplate {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{id}/disclosure-templates", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(customTemplateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/disclosure-templates", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(customTemplateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/disclosure-templates", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(customTemplateBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> customTemplateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "DXAUTHZ_NEW_" + System.nanoTime() % 1_000_000L);
            body.put("name", "新規様式");
            body.put("version", "1.0");
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("sections", List.of());
            body.put("formSchema", schema);
            body.put("effectiveFrom", LocalDate.of(2024, 4, 1).toString());
            body.put("isActive", true);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 14. PUT /organizations/{id}/disclosure-templates/{id}（更新: checkAdminOrAbove + BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14. PUT /organizations/{id}/disclosure-templates/{id}（カスタム様式更新）")
    class UpdateCustomTemplate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put(
                            "/api/v1/organizations/{id}/disclosure-templates/{templateId}",
                            orgAId, customTemplateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTemplateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境: orgBのADMINがorgAのカスタム様式を更新しようとする→404で存在秘匿")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put(
                            "/api/v1/organizations/{id}/disclosure-templates/{templateId}",
                            orgBId, customTemplateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTemplateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put(
                            "/api/v1/organizations/{id}/disclosure-templates/{templateId}",
                            orgAId, customTemplateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateTemplateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateTemplateBody() {
            DisclosureFormTemplateEntity current = templateRepository.findById(customTemplateAId).orElseThrow();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", current.getCode());
            body.put("name", "更新後様式名");
            body.put("version", "2.0");
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("sections", List.of());
            body.put("formSchema", schema);
            body.put("effectiveFrom", LocalDate.of(2024, 4, 1).toString());
            body.put("isActive", true);
            body.put("versionLock", current.getVersionLock());
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 15. DELETE /organizations/{id}/disclosure-templates/{id}（削除: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("15. DELETE /organizations/{id}/disclosure-templates/{id}（カスタム様式削除）")
    class DeleteCustomTemplate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete(
                            "/api/v1/organizations/{id}/disclosure-templates/{templateId}",
                            orgAId, customTemplateAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete(
                            "/api/v1/organizations/{id}/disclosure-templates/{templateId}",
                            orgAId, customTemplateAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** ドラフト作成 → PDF 出力まで一気に行い、出力履歴 ID を返す（MockMvc 経由）。 */
    private Long createExportedRecord(Long actingAdminId) throws Exception {
        setAuth(actingAdminId);
        Map<String, Object> draftBody = new LinkedHashMap<>();
        draftBody.put("templateId", systemTemplateId);
        draftBody.put("title", "出力元ドラフト_" + System.nanoTime());
        String draftJson = mockMvc.perform(post("/api/v1/organizations/{id}/disclosure-drafts", orgAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftBody)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long newDraftId = ((Number) objectMapper.readTree(draftJson).path("data").path("id").numberValue())
                .longValue();

        String exportJson = mockMvc.perform(post(
                        "/api/v1/organizations/{id}/disclosure-drafts/{draftId}/export?format=pdf",
                        orgAId, newDraftId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) objectMapper.readTree(exportJson).path("data").path("exportId").numberValue())
                .longValue();
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
                                + "VALUES (:email, :ln, :fn, 'DXAUTHZ テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", encryptForTest("DXAUTHZ"))
                .setParameter("fn", encryptForTest("テスト"))
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
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

    private Long saveSystemTemplate(String code, String version, String formSchema) {
        DisclosureFormTemplateEntity entity = DisclosureFormTemplateEntity.builder()
                .code(code)
                .name("テンプレ " + code)
                .prefectureCode(null)
                .version(version)
                .isStandard(true)
                .isSystemTemplate(true)
                .scopeType(null)
                .scopeId(null)
                .formSchema(formSchema)
                .effectiveFrom(LocalDate.of(2024, 4, 1))
                .isActive(true)
                .build();
        return templateRepository.save(entity).getId();
    }

    private Long saveCustomTemplate(String code, String version, Long scopeId) {
        DisclosureFormTemplateEntity entity = DisclosureFormTemplateEntity.builder()
                .code(code)
                .name("カスタム " + code)
                .prefectureCode(null)
                .version(version)
                .isStandard(false)
                .isSystemTemplate(false)
                .scopeType("ORGANIZATION")
                .scopeId(scopeId)
                .formSchema("{\"sections\":[]}")
                .effectiveFrom(LocalDate.of(2024, 4, 1))
                .isActive(true)
                .build();
        return templateRepository.save(entity).getId();
    }
}
