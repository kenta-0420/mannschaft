package com.mannschaft.app.line;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
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

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave2 トランシェ2C: line ドメイン API 契約テスト（試練）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md}（line 節: BotConfig / SnsFeed に
 * 認可なし ＋ webhookSecret 平文露出）・{@code AccessControlService}
 * （{@code checkMembership}/{@code checkAdminOrAbove}）。
 * 金型: {@code ParkingScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL・
 * 越境 403/404 はアプリ層例外として認可フィルタ無効でも検証できる）。</p>

 * <p>担当スコープ（他は対象外）:</p>
 * <ul>
 *   <li>LineBotConfigService 全メソッド: BOT 設定は webhookSecret／チャネル資格情報を扱う管理機能のため
 *       閲覧・変更とも {@code checkAdminOrAbove}（先行 #2259 webhook ドメインの listTokens 方針に倣う）。
 *       BOT 設定はスコープ 1:1（path に entity ID を持たない）ため、越境 ADMIN は 403 で拒否される
 *       （ID ベースの BOLA は構造上存在しない）。設定不在は LINE_001 → 404（存在秘匿マッピング）。</li>
 *   <li>SnsFeedConfigService: 閲覧（一覧）= {@code checkMembership}、変更（作成/更新/削除）=
 *       {@code checkAdminOrAbove}。update/delete/preview は path の id から entity を先に fetch し、
 *       entity 由来 scope と path scope の不一致は LINE_007 → 404 で存在秘匿（BOLA 是正）。</li>
 *   <li>webhookSecret は応答で平文返却せず prefix マスク（先頭8文字+****+末尾4文字）。</li>
 * </ul>
 *
 * <p>ADMIN 役の被験者は {@code checkMembership}（memberships 表）と
 * {@code checkAdminOrAbove}（user_roles 表）の両方を満たすよう二重に seed する
 * （認可根治戦役 Wave0+1 で確立した既知の地雷）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("line ドメイン API 契約テスト（認可根治 Wave2 トランシェ2C）")
class LineScopeContractIT extends AbstractMySqlIntegrationTest {

    /** teamA の BOT 設定 webhookSecret（生値）。応答ではマスクされていることを検証する。 */
    private static final String TEAM_A_WEBHOOK_SECRET = "wh-team-a-secret-0001";
    /** 生値 {@link #TEAM_A_WEBHOOK_SECRET} の期待マスク（先頭8文字 + **** + 末尾4文字）。 */
    private static final String TEAM_A_WEBHOOK_SECRET_MASKED = "wh-team-****0001";

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

    private Long teamAFeedId;

    @BeforeEach
    void setUp() {
        insertRoleIfAbsent("ADMIN", "管理者", 2, false);

        teamAId = insertTeam("LINE契約テストチームA");
        teamBId = insertTeam("LINE契約テストチームB");

        adminAId = insertUser("line-contract-admin-a@example.com");
        adminBId = insertUser("line-contract-admin-b@example.com");
        memberAId = insertUser("line-contract-member-a@example.com");
        outsiderId = insertUser("line-contract-outsider@example.com");

        // ADMIN 役は checkMembership(memberships) と checkAdminOrAbove(user_roles) の両方を満たす必要がある
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId,
                com.mannschaft.app.membership.domain.ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId,
                com.mannschaft.app.membership.domain.ScopeType.TEAM, teamBId, RoleKind.MEMBER);

        // memberA はチームAの一般メンバー（ADMIN権限なし）
        MembershipTestHelper.insertMembership(em, memberAId,
                com.mannschaft.app.membership.domain.ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        // outsiderId はどちらのチームにも一切所属しない

        // teamA には BOT 設定＋フィード設定を seed（teamB は未設定のまま）
        insertLineBotConfig("TEAM", teamAId, "ch-team-a", TEAM_A_WEBHOOK_SECRET, adminAId);
        teamAFeedId = insertSnsFeedConfig("TEAM", teamAId, "INSTAGRAM", "team_a_insta", adminAId);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // LineBotConfigService（BOT設定・テスト送信・メッセージ履歴）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("LINE BOT設定(config/test/logs)")
    class BotConfig {

        @Test
        @DisplayName("非メンバーのBOT設定取得は403（COMMON_002）")
        void 非メンバーのBOT設定取得は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/line/config", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバーのBOT設定取得は403（シークレット含有のため閲覧もADMIN限定）")
        void 一般メンバーのBOT設定取得は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/line/config", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINのBOT設定取得は403（越境拒否。BOT設定はスコープ1:1でIDを持たないため404ではなく403）")
        void 他チームADMINのBOT設定取得は403() throws Exception {
            setAuthentication(adminBId); // チームBのADMINがチームAのURLを叩く

            mockMvc.perform(get("/api/v1/teams/{teamId}/line/config", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINのBOT設定取得は200かつwebhookSecretはprefixマスクされる（平文露出の根治）")
        void 正当ADMINのBOT設定取得は200かつマスク() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/line/config", teamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.channelId").value("ch-team-a"))
                    .andExpect(jsonPath("$.data.webhookSecret").value(TEAM_A_WEBHOOK_SECRET_MASKED))
                    .andExpect(jsonPath("$.data.webhookSecret").value(not(TEAM_A_WEBHOOK_SECRET)));
        }

        @Test
        @DisplayName("設定不在スコープの正当ADMIN取得は404（LINE_001の404マッピング）")
        void 設定不在の正当ADMIN取得は404() throws Exception {
            setAuthentication(adminBId); // teamB には BOT 設定を seed していない

            mockMvc.perform(get("/api/v1/teams/{teamId}/line/config", teamBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("LINE_001"));
        }

        @Test
        @DisplayName("一般メンバーのBOT設定更新は403（変更系はcheckAdminOrAbove）")
        void 一般メンバーのBOT設定更新は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(put("/api/v1/teams/{teamId}/line/config", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(botConfigBody("wh-hijack-secret-9999"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのBOT設定作成は201かつwebhookSecretはマスクされる")
        void 正当ADMINのBOT設定作成は201かつマスク() throws Exception {
            setAuthentication(adminBId); // teamB は未設定なので新規作成できる

            mockMvc.perform(post("/api/v1/teams/{teamId}/line/config", teamBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(botConfigBody("wh-team-b-secret-0002"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.webhookSecret").value("wh-team-****0002"))
                    .andExpect(jsonPath("$.data.webhookSecret").value(not("wh-team-b-secret-0002")));
        }

        @Test
        @DisplayName("一般メンバーのテストメッセージ送信は403（変更系はcheckAdminOrAbove）")
        void 一般メンバーのテスト送信は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("lineUserId", "line-user-001");
            body.put("message", "乗っ取りテスト");

            mockMvc.perform(post("/api/v1/teams/{teamId}/line/test", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバーのメッセージ履歴取得は403（LINE利用者ID・本文要約を含むためADMIN限定）")
        void 一般メンバーのメッセージ履歴取得は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/line/logs", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのメッセージ履歴取得は200")
        void 正当ADMINのメッセージ履歴取得は200() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/line/logs", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // SnsFeedConfigService（フィード設定: 閲覧=membership / 変更=ADMIN・id越境はLINE_007→404）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SNSフィード設定(feeds)")
    class SnsFeed {

        @Test
        @DisplayName("非メンバーのフィード一覧取得は403（COMMON_002）")
        void 非メンバーのフィード一覧取得は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/sns/feeds", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバーのフィード一覧取得は200（閲覧はcheckMembership）")
        void 一般メンバーのフィード一覧取得は200() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/sns/feeds", teamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].accountUsername").value("team_a_insta"));
        }

        @Test
        @DisplayName("一般メンバーのフィード作成は403（変更系はcheckAdminOrAbove。認可は重複チェックより先）")
        void 一般メンバーのフィード作成は403() throws Exception {
            setAuthentication(memberAId);

            // teamA には INSTAGRAM フィードが seed 済みだが、認可(403)が重複判定(LINE_008)より先に立つこと
            mockMvc.perform(post("/api/v1/teams/{teamId}/sns/feeds", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(feedCreateBody("INSTAGRAM"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのフィード作成は201")
        void 正当ADMINのフィード作成は201() throws Exception {
            setAuthentication(adminBId); // teamB にはフィード未設定なので新規作成できる

            mockMvc.perform(post("/api/v1/teams/{teamId}/sns/feeds", teamBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(feedCreateBody("INSTAGRAM"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }

        @Test
        @DisplayName("他チームADMINが自チームURLで他チームのフィードを更新しようとすると404（BOLA是正: entity由来scope検証・LINE_007）")
        void 他チームADMINによる越境フィード更新は404() throws Exception {
            // チームBのADMINが、自分のチームBのURLパスに「teamAのフィードID」を指定して更新を試みる
            setAuthentication(adminBId);

            mockMvc.perform(put("/api/v1/teams/{teamId}/sns/feeds/{id}", teamBId, teamAFeedId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(feedUpdateBody("hijacked_user"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("LINE_007"));
        }

        @Test
        @DisplayName("一般メンバーのフィード削除は403（変更系はcheckAdminOrAbove）")
        void 一般メンバーのフィード削除は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(delete("/api/v1/teams/{teamId}/sns/feeds/{id}", teamAId, teamAFeedId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのフィード更新は200")
        void 正当ADMINのフィード更新は200() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(put("/api/v1/teams/{teamId}/sns/feeds/{id}", teamAId, teamAFeedId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(feedUpdateBody("team_a_insta_v2"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accountUsername").value("team_a_insta_v2"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // リクエストボディ組み立て
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> botConfigBody(String webhookSecret) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channelId", "ch-contract");
        body.put("channelSecret", "channel-secret-raw");
        body.put("channelAccessToken", "channel-access-token-raw");
        body.put("webhookSecret", webhookSecret);
        body.put("notificationEnabled", true);
        return body;
    }

    private Map<String, Object> feedCreateBody(String provider) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", provider);
        body.put("accountUsername", "new_account");
        body.put("displayCount", 6);
        return body;
    }

    private Map<String, Object> feedUpdateBody(String accountUsername) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountUsername", accountUsername);
        body.put("displayCount", 9);
        body.put("isActive", true);
        return body;
    }

    // ═════════════════════════════════════════════════════════════════════
    // seed ヘルパー（entity の NOT NULL 列と突合済み。roles は name で引く idempotent seed）
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /**
     * roles をグローバル参照テーブルとして扱い、既存があれば触らない idempotent seed。
     * （deleteAll 禁止・他テストとの共存のため name で引いて不在時のみ INSERT する。）
     */
    private void insertRoleIfAbsent(String name, String displayName, int priority, boolean isSystem) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (count.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, :sys, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .setParameter("sys", isSystem ? 1 : 0)
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
                                + "VALUES (:email, 'LINE契約', 'テスト', 'LINE契約テスト', 'ACTIVE', "
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
                                + "CONCAT('linec-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    /**
     * line_bot_configs を 1 行 seed する。NOT NULL 列（scope_type/scope_id/channel_id/
     * channel_secret_enc/channel_access_token_enc/encryption_key_version/webhook_secret/
     * is_active/notification_enabled/configured_by）はすべて明示指定する
     * （@Builder.Default は DDL デフォルトを生成しないため省略不可）。
     */
    private void insertLineBotConfig(String scopeType, Long scopeId, String channelId,
                                     String webhookSecret, Long configuredBy) {
        em.createNativeQuery(
                        "INSERT INTO line_bot_configs (scope_type, scope_id, channel_id, "
                                + "channel_secret_enc, channel_access_token_enc, encryption_key_version, "
                                + "webhook_secret, bot_user_id, is_active, notification_enabled, "
                                + "configured_by, created_at, updated_at) "
                                + "VALUES (:st, :sid, :cid, :sec, :tok, 1, :whs, NULL, 1, 1, :by, NOW(), NOW())")
                .setParameter("st", scopeType)
                .setParameter("sid", scopeId)
                .setParameter("cid", channelId)
                .setParameter("sec", new byte[]{1, 2, 3})
                .setParameter("tok", new byte[]{4, 5, 6})
                .setParameter("whs", webhookSecret)
                .setParameter("by", configuredBy)
                .executeUpdate();
    }

    /**
     * sns_feed_configs を 1 行 seed し、その ID を返す。NOT NULL 列（scope_type/scope_id/provider/
     * account_username/encryption_key_version/display_count/is_active/configured_by）は明示指定する。
     */
    private Long insertSnsFeedConfig(String scopeType, Long scopeId, String provider,
                                     String accountUsername, Long configuredBy) {
        em.createNativeQuery(
                        "INSERT INTO sns_feed_configs (scope_type, scope_id, provider, account_username, "
                                + "access_token_enc, encryption_key_version, display_count, is_active, "
                                + "configured_by, created_at, updated_at) "
                                + "VALUES (:st, :sid, :prov, :acct, NULL, 1, 6, 1, :by, NOW(), NOW())")
                .setParameter("st", scopeType)
                .setParameter("sid", scopeId)
                .setParameter("prov", provider)
                .setParameter("acct", accountUsername)
                .setParameter("by", configuredBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM sns_feed_configs").getSingleResult()).longValue();
    }
}
