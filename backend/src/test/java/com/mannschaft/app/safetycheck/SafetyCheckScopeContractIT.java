package com.mannschaft.app.safetycheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.safetycheck.entity.SafetyCheckEntity;
import com.mannschaft.app.safetycheck.entity.SafetyCheckTemplateEntity;
import com.mannschaft.app.safetycheck.entity.SafetyResponseEntity;
import com.mannschaft.app.safetycheck.entity.SafetyResponseFollowupEntity;
import com.mannschaft.app.safetycheck.repository.SafetyCheckRepository;
import com.mannschaft.app.safetycheck.repository.SafetyCheckTemplateRepository;
import com.mannschaft.app.safetycheck.repository.SafetyResponseFollowupRepository;
import com.mannschaft.app.safetycheck.repository.SafetyResponseRepository;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治 Wave7 — safetycheck ドメイン（一覧/詳細/履歴・テンプレート・フォローアップ）認可契約テスト。
 *
 * <p>対象 8EP は認可シグナルを一切持たず、{@code SafetyFollowupController} に至っては Service 層すら
 * 無く Repository を直接叩いて災害時フォローアップ（要救助者追跡レコード）を誰でも改竄できた。
 * 金型: {@code EquipmentScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 *
 * <p><b>認可モデル</b>:</p>
 * <ul>
 *   <li><b>スコープ宣言型 EP</b>（一覧 / 履歴 / テンプレート一覧 / テンプレート作成）:
 *       参照は宣言スコープの membership、作成は ADMIN/DEPUTY_ADMIN。権限が無ければ 403。
 *       スコープは呼び出し元が既に知っているため秘匿不要。</li>
 *   <li><b>bare id EP</b>（詳細 / テンプレート詳細 / テンプレート更新 / フォローアップ更新）:
 *       entity を fetch し <b>entity 由来スコープ</b>で判定。<b>非メンバーは 404</b>（存在秘匿。
 *       部外者も別団体 ADMIN の越境も区別不能なため同一に収束）、
 *       <b>メンバーだが非 ADMIN の管理操作は 403</b>（当該メンバーは参照系で既に存在を知るため）。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("safetycheck ドメイン 認可契約テスト（Wave7）")
class SafetyCheckScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SafetyCheckRepository safetyCheckRepository;

    @Autowired
    private SafetyCheckTemplateRepository templateRepository;

    @Autowired
    private SafetyResponseRepository responseRepository;

    @Autowired
    private SafetyResponseFollowupRepository followupRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminAId;     // teamA の ADMIN（正当）
    private Long adminBId;     // teamB の ADMIN（越境攻撃者）
    private Long memberAId;    // teamA の非 ADMIN メンバー
    private Long outsiderId;   // どこにも所属しない部外者

    private Long checkAId;         // teamA の ACTIVE な安否確認
    private Long closedCheckAId;   // teamA のクローズ済み安否確認（履歴用）
    private Long checkBId;         // teamB の安否確認（越境検証用）

    private Long templateAId;      // teamA 所有テンプレート
    private Long templateBId;      // teamB 所有テンプレート（越境検証用）
    private Long systemTemplateId; // スコープ null のシステム既定テンプレート

    private Long followupAId;      // teamA の要支援者フォローアップ

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("SCAUTHZ チームA");
        teamBId = insertTeam("SCAUTHZ チームB");

        adminAId = insertUser("scauthz-admin-a@example.com");
        adminBId = insertUser("scauthz-admin-b@example.com");
        memberAId = insertUser("scauthz-member-a@example.com");
        outsiderId = insertUser("scauthz-outsider@example.com");

        // checkAdminOrAbove（user_roles）と isMember（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（Wave 踏襲の既知の地雷）。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        checkAId = safetyCheckRepository.save(buildCheck(teamAId, false)).getId();
        closedCheckAId = safetyCheckRepository.save(buildCheck(teamAId, true)).getId();
        checkBId = safetyCheckRepository.save(buildCheck(teamBId, false)).getId();

        templateAId = templateRepository.save(buildScopedTemplate(teamAId, "SCAUTHZ テンプレA")).getId();
        templateBId = templateRepository.save(buildScopedTemplate(teamBId, "SCAUTHZ テンプレB")).getId();
        systemTemplateId = templateRepository.save(SafetyCheckTemplateEntity.builder()
                .templateName("SCAUTHZ システム既定")
                .title("システム既定タイトル")
                .message("システム既定メッセージ")
                .isSystemDefault(true)
                .build()).getId();

        SafetyResponseEntity responseA = responseRepository.save(SafetyResponseEntity.builder()
                .safetyCheckId(checkAId)
                .userId(memberAId)
                .status(SafetyResponseStatus.NEED_SUPPORT)
                .message("支援が必要です")
                .respondedAt(LocalDateTime.now())
                .build());
        followupAId = followupRepository.save(SafetyResponseFollowupEntity.builder()
                .safetyResponseId(responseA.getId())
                .followupStatus(FollowupStatus.PENDING)
                .build()).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /api/v1/safety-checks（一覧・スコープ宣言型: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /safety-checks（一覧）")
    class ListSafetyChecks {

        @Test
        @DisplayName("部外者は403")
        void 部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/safety-checks")
                            .param("scopeType", "TEAM").param("scopeId", String.valueOf(teamAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/safety-checks")
                            .param("scopeType", "TEAM").param("scopeId", String.valueOf(teamAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200（回答のため参照可）")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/safety-checks")
                            .param("scopeType", "TEAM").param("scopeId", String.valueOf(teamAId)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/safety-checks")
                            .param("scopeType", "TEAM").param("scopeId", String.valueOf(teamAId)))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /api/v1/safety-checks/{id}（詳細・bare id: entity由来scope＋404秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /safety-checks/{id}（詳細）")
    class GetSafetyCheck {

        @Test
        @DisplayName("部外者は404（存在秘匿）")
        void 部外者は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/safety-checks/{id}", checkAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SAFETY_001"));
        }

        @Test
        @DisplayName("別scope ADMINは404（BOLA・存在秘匿）")
        void 別scopeADMINは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/safety-checks/{id}", checkAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("teamAのADMINは他チームの安否確認を取得できない（404）")
        void 他チームの安否確認は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/safety-checks/{id}", checkBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/safety-checks/{id}", checkAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/safety-checks/{id}", checkAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. GET /api/v1/safety-checks/history（履歴・スコープ宣言型）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET /safety-checks/history（履歴）")
    class GetHistory {

        @Test
        @DisplayName("部外者は403")
        void 部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/safety-checks/history")
                            .param("scopeType", "TEAM").param("scopeId", String.valueOf(teamAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/safety-checks/history")
                            .param("scopeType", "TEAM").param("scopeId", String.valueOf(teamAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/safety-checks/history")
                            .param("scopeType", "TEAM").param("scopeId", String.valueOf(teamAId)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200でクローズ済みが返る（機能非回帰）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/safety-checks/history")
                            .param("scopeType", "TEAM").param("scopeId", String.valueOf(teamAId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(closedCheckAId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. GET /api/v1/safety-checks/templates（テンプレート一覧・スコープ宣言型）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. GET /safety-checks/templates（一覧）")
    class ListTemplates {

        @Test
        @DisplayName("部外者は403")
        void 部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/safety-checks/templates")
                            .param("scopeType", "TEAM").param("scopeId", String.valueOf(teamAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/safety-checks/templates")
                            .param("scopeType", "TEAM").param("scopeId", String.valueOf(teamAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/safety-checks/templates")
                            .param("scopeType", "TEAM").param("scopeId", String.valueOf(teamAId)))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. GET /api/v1/safety-checks/templates/{id}（詳細・bare id）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. GET /safety-checks/templates/{id}（詳細）")
    class GetTemplate {

        @Test
        @DisplayName("部外者は404（存在秘匿）")
        void 部外者は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/safety-checks/templates/{id}", templateAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SAFETY_006"));
        }

        @Test
        @DisplayName("別scope ADMINは404（BOLA・存在秘匿）")
        void 別scopeADMINは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/safety-checks/templates/{id}", templateAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/safety-checks/templates/{id}", templateAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("teamAのADMINは他チーム所有テンプレートを取得できない（404）")
        void 他チームのテンプレートは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/safety-checks/templates/{id}", templateBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("システム既定テンプレートは認証済みユーザーなら200（全スコープ共通文言）")
        void システム既定は200() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/safety-checks/templates/{id}", systemTemplateId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. POST /api/v1/safety-checks/templates（作成・スコープ宣言型: ADMIN）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. POST /safety-checks/templates（作成）")
    class CreateTemplate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/safety-checks/templates")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(teamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/safety-checks/templates")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(teamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("スコープ省略（システム既定テンプレート作成）は403 — SYSTEM_ADMIN入口専用")
        void スコープ省略は403() throws Exception {
            setAuth(adminAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("templateName", "SCAUTHZ 全体既定を狙う");
            body.put("title", "乗っ取り");
            body.put("message", "乗っ取りメッセージ");
            mockMvc.perform(post("/api/v1/safety-checks/templates")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201（機能非回帰）")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/safety-checks/templates")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(teamAId))))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createBody(Long scopeId) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("templateName", "SCAUTHZ 新規テンプレ");
            body.put("title", "新規タイトル");
            body.put("message", "新規メッセージ");
            body.put("scopeType", "TEAM");
            body.put("scopeId", scopeId);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. PATCH /api/v1/safety-checks/templates/{id}（更新・bare id）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. PATCH /safety-checks/templates/{id}（更新）")
    class UpdateTemplate {

        @Test
        @DisplayName("部外者は404（存在秘匿）")
        void 部外者は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/safety-checks/templates/{id}", templateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("別scope ADMINは404（BOLA・存在秘匿）")
        void 別scopeADMINは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(patch("/api/v1/safety-checks/templates/{id}", templateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("システム既定テンプレートは404（SYSTEM_ADMIN入口専用）")
        void システム既定は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/safety-checks/templates/{id}", systemTemplateId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("メンバーだが非ADMINは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch("/api/v1/safety-checks/templates/{id}", templateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200（機能非回帰）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/safety-checks/templates/{id}", templateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("templateName", "SCAUTHZ 更新後");
            body.put("title", "更新後タイトル");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. PATCH /api/v1/safety-checks/followups/{id}（フォローアップ更新・bare id）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. PATCH /safety-checks/followups/{id}（要支援者フォローアップ更新）")
    class UpdateFollowup {

        @Test
        @DisplayName("部外者は404（存在秘匿）")
        void 部外者は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/safety-checks/followups/{id}", followupAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SAFETY_008"));
        }

        @Test
        @DisplayName("別scope ADMINは404（BOLA・存在秘匿）— 救助対象を握り潰せない")
        void 別scopeADMINは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(patch("/api/v1/safety-checks/followups/{id}", followupAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("メンバーだが非ADMINは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch("/api/v1/safety-checks/followups/{id}", followupAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200（機能非回帰）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/safety-checks/followups/{id}", followupAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.followupStatus").value("COMPLETED"));
        }

        private Map<String, Object> resolveBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("followupStatus", "COMPLETED");
            body.put("note", "対応完了");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private SafetyCheckEntity buildCheck(Long scopeId, boolean closed) {
        SafetyCheckEntity entity = SafetyCheckEntity.builder()
                .scopeType(SafetyCheckScopeType.TEAM)
                .scopeId(scopeId)
                .title("SCAUTHZ 地震発生")
                .message("安否を報告してください")
                .isDrill(true)
                .status(SafetyCheckStatus.ACTIVE)
                .totalTargetCount(1)
                .build();
        if (closed) {
            entity.close(adminAId);
        }
        return entity;
    }

    private SafetyCheckTemplateEntity buildScopedTemplate(Long scopeId, String name) {
        return SafetyCheckTemplateEntity.builder()
                .scopeType(SafetyCheckScopeType.TEAM)
                .scopeId(scopeId)
                .templateName(name)
                .title(name + " タイトル")
                .message(name + " メッセージ")
                .build();
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
                                + "VALUES (:email, 'SCAUTHZ', 'テスト', 'SCAUTHZ テスト', 'ACTIVE', "
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
