package com.mannschaft.app.directmail;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave2 トランシェ2C: directmail ドメイン API 契約テスト（試練）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md}（directmail 節: 全体が認可なし。
 * 即時送信＝なりすまし一斉送信・受信者一覧＝PII露出・Template CRUD 認可皆無）・
 * {@code AccessControlService}（{@code checkMembership}/{@code checkAdminOrAbove}）。
 * 金型: {@code ParkingScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL・
 * 越境 403/404 はアプリ層例外として認可フィルタ無効でも検証できる）。</p>

 * <p>敷設する認可の型:</p>
 * <ul>
 *   <li>閲覧系（一覧/詳細/統計/プレビュー/テンプレ一覧）= {@code checkMembership}</li>
 *   <li>変更系（作成/更新/送信/予約/キャンセル/テンプレCRUD/画像アップロード）= {@code checkAdminOrAbove}</li>
 *   <li>受信者一覧（メールアドレス PII）と配信対象数見積（ロール別メンバー数インテリジェンス）は
 *       {@code checkAdminOrAbove}（台帳 findings「受信者一覧 PII露出」の根治）</li>
 *   <li>BOLA: (id, scopeType, scopeId) 複合フェッチにより path スコープと entity スコープの不一致は
 *       DM_001/DM_002 → 404 で存在秘匿</li>
 * </ul>
 *
 * <p>ADMIN 役の被験者は {@code checkMembership}（memberships 表）と
 * {@code checkAdminOrAbove}（user_roles 表）の両方を満たすよう二重に seed する
 * （認可根治戦役 Wave0+1 で確立した既知の地雷）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("directmail ドメイン API 契約テスト（認可根治 Wave2 トランシェ2C）")
class DirectMailScopeContractIT extends AbstractMySqlIntegrationTest {

    /** 組織スコープ検証用の合成組織ID（高位ネームスペース 92x_xxx 台・実 organizations 行は不要） */
    private static final Long ORG_ID = 920_601L;

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
    private Long orgAdminId;
    private Long orgMemberId;

    @BeforeEach
    void setUp() {
        ensureRole("ADMIN", "管理者", 2);

        teamAId = insertTeam("DM契約テストチームA");
        teamBId = insertTeam("DM契約テストチームB");

        adminAId = insertUser("dm-contract-admin-a@example.com");
        adminBId = insertUser("dm-contract-admin-b@example.com");
        memberAId = insertUser("dm-contract-member-a@example.com");
        outsiderId = insertUser("dm-contract-outsider@example.com");
        orgAdminId = insertUser("dm-contract-org-admin@example.com");
        orgMemberId = insertUser("dm-contract-org-member@example.com");

        // ADMIN 役は checkMembership(memberships) と checkAdminOrAbove(user_roles) の両方を満たす必要がある
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);

        // memberA はチームAの一般メンバー（ADMIN権限なし）
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        // 組織スコープ: orgAdmin は組織ADMIN、orgMember は一般メンバー
        MembershipTestHelper.insertUserRole(em, orgAdminId, "ADMIN", null, ORG_ID);
        MembershipTestHelper.insertMembership(em, orgAdminId, ScopeType.ORGANIZATION, ORG_ID, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, orgMemberId, ScopeType.ORGANIZATION, ORG_ID, RoleKind.MEMBER);

        // outsiderId はどのスコープにも一切所属しない

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // メール本体（DirectMailService: CRUD・即時送信）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("メール本体（作成・一覧・詳細・更新・即時送信）")
    class MailCrudAndSend {

        @Test
        @DisplayName("非メンバーのメール一覧取得は403（COMMON_002）")
        void 非メンバーのメール一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/direct-mails", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバーのメール一覧取得は200（閲覧系はcheckMembership）")
        void 一般メンバーのメール一覧は200() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/direct-mails", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーのメール作成は403（変更系はcheckAdminOrAbove）")
        void 非ADMINメンバーのメール作成は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/direct-mails", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createMailBody("メンバー作成不可"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのメール作成は201")
        void 正当ADMINのメール作成は201() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/direct-mails", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createMailBody("正当ADMIN作成"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }

        @Test
        @DisplayName("非ADMINメンバーの即時送信は403（なりすまし一斉送信の根治）")
        void 非ADMINメンバーの即時送信は403() throws Exception {
            Long mailId = insertDirectMail("TEAM", teamAId, adminAId, "DRAFT");

            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/direct-mails/{id}/send", teamAId, mailId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINが自チームURLで他チームのメールを送信しようとすると404（BOLA・DM_001存在秘匿）")
        void 他チームADMINによる越境送信は404() throws Exception {
            Long mailId = insertDirectMail("TEAM", teamAId, adminAId, "DRAFT");

            // チームBのADMINが、自分のチームBのURLパスに「teamAのメールID」を指定して送信を試みる
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/direct-mails/{id}/send", teamBId, mailId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("DM_001"));
        }

        @Test
        @DisplayName("正当ADMINの即時送信は200")
        void 正当ADMINの即時送信は200() throws Exception {
            Long mailId = insertDirectMail("TEAM", teamAId, adminAId, "DRAFT");

            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/direct-mails/{id}/send", teamAId, mailId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SENDING"));
        }

        @Test
        @DisplayName("非ADMINメンバーのメール更新は403")
        void 非ADMINメンバーのメール更新は403() throws Exception {
            Long mailId = insertDirectMail("TEAM", teamAId, adminAId, "DRAFT");

            setAuthentication(memberAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/direct-mails/{id}", teamAId, mailId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createMailBody("乗っ取り更新"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINが自チームURLで他チームのメール詳細を閲覧しようとすると404（BOLA・DM_001存在秘匿）")
        void 他チームADMINによる越境詳細閲覧は404() throws Exception {
            Long mailId = insertDirectMail("TEAM", teamAId, adminAId, "DRAFT");

            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/direct-mails/{id}", teamBId, mailId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("DM_001"));
        }

        @Test
        @DisplayName("一般メンバーのメール詳細取得は200（閲覧系はcheckMembership）")
        void 一般メンバーのメール詳細は200() throws Exception {
            Long mailId = insertDirectMail("TEAM", teamAId, adminAId, "DRAFT");

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/direct-mails/{id}", teamAId, mailId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 受信者一覧（PII）・統計・見積・プレビュー
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("受信者一覧（PII）・統計・見積・プレビュー")
    class RecipientsStatsEstimatePreview {

        @Test
        @DisplayName("非ADMINメンバーの受信者一覧は403（受信者メールPIIはADMIN限定）")
        void 非ADMINメンバーの受信者一覧は403() throws Exception {
            Long mailId = insertDirectMail("TEAM", teamAId, adminAId, "SENT");

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/direct-mails/{id}/recipients", teamAId, mailId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINが自チームURLで他チームの受信者一覧を閲覧しようとすると404（PII越境是正）")
        void 他チームADMINによる越境受信者閲覧は404() throws Exception {
            Long mailId = insertDirectMail("TEAM", teamAId, adminAId, "SENT");

            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/direct-mails/{id}/recipients", teamBId, mailId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("DM_001"));
        }

        @Test
        @DisplayName("正当ADMINの受信者一覧は200")
        void 正当ADMINの受信者一覧は200() throws Exception {
            Long mailId = insertDirectMail("TEAM", teamAId, adminAId, "SENT");

            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/direct-mails/{id}/recipients", teamAId, mailId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーの送信統計は403")
        void 非メンバーの送信統計は403() throws Exception {
            Long mailId = insertDirectMail("TEAM", teamAId, adminAId, "SENT");

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/direct-mails/{id}/stats", teamAId, mailId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一般メンバーの送信統計は200（閲覧系はcheckMembership）")
        void 一般メンバーの送信統計は200() throws Exception {
            Long mailId = insertDirectMail("TEAM", teamAId, adminAId, "SENT");

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/direct-mails/{id}/stats", teamAId, mailId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーの配信対象数見積は403（ロール別メンバー数インテリジェンスはADMIN限定）")
        void 非ADMINメンバーの配信対象数見積は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recipientType", "ALL");

            mockMvc.perform(post("/api/v1/teams/{teamId}/direct-mails/estimate-recipients", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの配信対象数見積は200")
        void 正当ADMINの配信対象数見積は200() throws Exception {
            setAuthentication(adminAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recipientType", "ALL");

            mockMvc.perform(post("/api/v1/teams/{teamId}/direct-mails/estimate-recipients", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーのプレビューは403（スコープ入口の認可皆無を根治）")
        void 非メンバーのプレビューは403() throws Exception {
            setAuthentication(outsiderId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("bodyMarkdown", "# プレビュー");

            mockMvc.perform(post("/api/v1/teams/{teamId}/direct-mails/preview", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバーのプレビューは200")
        void 一般メンバーのプレビューは200() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("bodyMarkdown", "# プレビュー");

            mockMvc.perform(post("/api/v1/teams/{teamId}/direct-mails/preview", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // テンプレート（DirectMailTemplateService）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("テンプレートCRUD")
    class TemplateCrud {

        @Test
        @DisplayName("非メンバーのテンプレート一覧は403（COMMON_002）")
        void 非メンバーのテンプレ一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/direct-mail-templates", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバーのテンプレート一覧は200（閲覧系はcheckMembership）")
        void 一般メンバーのテンプレ一覧は200() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/direct-mail-templates", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーのテンプレート作成は403")
        void 非ADMINメンバーのテンプレ作成は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/direct-mail-templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTemplateBody("メンバー作成不可"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのテンプレート作成は201")
        void 正当ADMINのテンプレ作成は201() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/direct-mail-templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTemplateBody("正当ADMIN作成"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }

        @Test
        @DisplayName("他チームADMINが自チームURLで他チームのテンプレートを更新しようとすると404（BOLA・DM_002存在秘匿）")
        void 他チームADMINによる越境テンプレ更新は404() throws Exception {
            Long templateId = insertTemplate("TEAM", teamAId, adminAId, "越境更新対象");

            setAuthentication(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/direct-mail-templates/{id}", teamBId, templateId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTemplateBody("乗っ取り"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("DM_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーのテンプレート削除は403")
        void 非ADMINメンバーのテンプレ削除は403() throws Exception {
            Long templateId = insertTemplate("TEAM", teamAId, adminAId, "削除対象");

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/direct-mail-templates/{id}", teamAId, templateId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのテンプレート削除は204")
        void 正当ADMINのテンプレ削除は204() throws Exception {
            Long templateId = insertTemplate("TEAM", teamAId, adminAId, "正当削除対象");

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/direct-mail-templates/{id}", teamAId, templateId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 画像アップロード（DirectMailImageService）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("画像アップロード")
    class ImageUpload {

        @Test
        @DisplayName("非ADMINメンバーのDM画像アップロードは403（ストレージ到達前に遮断）")
        void 非ADMINメンバーの画像アップロードは403() throws Exception {
            setAuthentication(memberAId);

            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.png", "image/png", new byte[]{1, 2, 3});

            mockMvc.perform(multipart("/api/v1/teams/{teamId}/direct-mails/images", teamAId).file(file))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非メンバーのDM画像アップロードは403")
        void 非メンバーの画像アップロードは403() throws Exception {
            setAuthentication(outsiderId);

            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.png", "image/png", new byte[]{1, 2, 3});

            mockMvc.perform(multipart("/api/v1/teams/{teamId}/direct-mails/images", teamAId).file(file))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 組織スコープ（ORGANIZATION パリティ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("組織スコープ")
    class OrganizationScope {

        @Test
        @DisplayName("組織非メンバーの組織メール一覧は403")
        void 組織非メンバーの組織メール一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/organizations/{orgId}/direct-mails", ORG_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("組織の非ADMINメンバーの組織メール作成は403")
        void 組織非ADMINメンバーの組織メール作成は403() throws Exception {
            setAuthentication(orgMemberId);

            mockMvc.perform(post("/api/v1/organizations/{orgId}/direct-mails", ORG_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createMailBody("組織メンバー作成不可"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("組織ADMINの組織メール作成は201")
        void 組織ADMINの組織メール作成は201() throws Exception {
            setAuthentication(orgAdminId);

            mockMvc.perform(post("/api/v1/organizations/{orgId}/direct-mails", ORG_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createMailBody("組織ADMIN作成"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Map<String, Object> createMailBody(String subject) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", subject);
        body.put("bodyMarkdown", "# 本文");
        body.put("bodyHtml", "<h1>本文</h1>");
        body.put("recipientType", "ALL");
        return body;
    }

    private Map<String, Object> createTemplateBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("subject", "テンプレ件名");
        body.put("bodyMarkdown", "# テンプレ本文");
        return body;
    }

    /**
     * roles を name で引く idempotent seed（グローバル参照テーブルを deleteAll しない・
     * 既存 seed と衝突しない）。ADMIN priority=2 は本番 V2.014 seed と同値。
     */
    private void ensureRole(String name, String displayName, int priority) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (count.longValue() == 0) {
            em.createNativeQuery(
                            "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                    + "VALUES (:name, :dn, :priority, 0, NOW(), NOW())")
                    .setParameter("name", name)
                    .setParameter("dn", displayName)
                    .setParameter("priority", priority)
                    .executeUpdate();
        }
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
                                + "VALUES (:email, 'DMContract', 'テスト', 'DM契約テスト', 'ACTIVE', "
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
                                + "CONCAT('dmc-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    /**
     * direct_mail_logs へ 1 行 INSERT する。
     * NOT NULL 列（sender_type/total_recipients/sent_count/opened_count/bounced_count/status）は
     * {@code @Builder.Default} が DDL デフォルトを生成しないため明示指定する（entity と突合済）。
     */
    private Long insertDirectMail(String scopeType, Long scopeId, Long senderId, String status) {
        em.createNativeQuery(
                        "INSERT INTO direct_mail_logs (scope_type, scope_id, sender_id, sender_type, "
                                + "subject, body_markdown, body_html, recipient_type, "
                                + "total_recipients, sent_count, opened_count, bounced_count, status, "
                                + "created_at, updated_at) "
                                + "VALUES (:st, :sid, :sender, 'USER', "
                                + "'DM契約テスト件名', '# 本文', '<h1>本文</h1>', 'ALL', "
                                + "0, 0, 0, 0, :status, NOW(), NOW())")
                .setParameter("st", scopeType)
                .setParameter("sid", scopeId)
                .setParameter("sender", senderId)
                .setParameter("status", status)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM direct_mail_logs").getSingleResult()).longValue();
    }

    private Long insertTemplate(String scopeType, Long scopeId, Long createdBy, String name) {
        em.createNativeQuery(
                        "INSERT INTO direct_mail_templates (scope_type, scope_id, name, subject, "
                                + "body_markdown, created_by, created_at, updated_at) "
                                + "VALUES (:st, :sid, :name, 'テンプレ件名', '# テンプレ本文', :createdBy, NOW(), NOW())")
                .setParameter("st", scopeType)
                .setParameter("sid", scopeId)
                .setParameter("name", name)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM direct_mail_templates").getSingleResult()).longValue();
    }
}
