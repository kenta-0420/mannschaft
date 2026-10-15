package com.mannschaft.app.signage;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave7: signage（デジタルサイネージ）ドメインの参照系 API 契約テスト（試練）。
 *
 * <p>正本: {@code SignageScreenService}/{@code SignageSlotService}/{@code SignageEmergencyService}。
 * {@code getScreen}/{@code listScreens}/{@code listSlots} はサイネージ端末向けトークン認証経路
 * （{@code SignageDisplayController}）と共有されるため元のメソッドは温存し、認証ユーザー向けの
 * {@code *ForActor} オーバーロードで {@code AccessControlService#checkMembership} を敷いた。
 * {@code listEmergencyMessages} は端末経路と共有されないため直接敷いた。書込系
 * （create/update/delete/addSlot/broadcastEmergency）は既に {@code checkAdminOrAbove} で
 * 保護済み（今回のスコープ外）。</p>
 *
 * <p>アクセストークン（{@code SignageAccessTokenService}）については以下を固定する:</p>
 * <ul>
 *   <li>発行・一覧・無効化は当該画面スコープの ADMIN/DEPUTY_ADMIN に限定する
 *       （未認証は 401・非会員／一般メンバー／スコープ違いの管理者は 403）。</li>
 *   <li>表示API（{@code GET /signage/{token}}）が受け付けるのは、無効化されておらず
 *       かつ有効期限を過ぎていないトークンのみである。発行時に指定された有効期限は
 *       永続化され、満了後は SIGNAGE_002 を返す（無期限トークンは NULL で表現する）。</li>
 * </ul>
 *
 * <p>金型: {@code CmsSeriesTagScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("signage ドメイン 参照系 API 契約テスト（認可根治 Wave7）")
class SignageScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long adminAId;
    private Long memberAId;
    private Long outsiderId;

    /** チームB（スコープ違いの管理者を用意するための別スコープ）。 */
    private Long teamBId;
    private Long adminBId;

    private Long screenId;

    @BeforeEach
    void setUp() throws Exception {
        insertRoleIfAbsent("ADMIN", "管理者", 2);

        teamAId = insertTeam("SIGNAGE-STG認可契約チームA");
        teamBId = insertTeam("SIGNAGE-STG認可契約チームB");

        adminAId = insertUser("signage-stg-authz-admin-a@example.com");
        memberAId = insertUser("signage-stg-authz-member-a@example.com");
        outsiderId = insertUser("signage-stg-authz-outsider@example.com");
        adminBId = insertUser("signage-stg-authz-admin-b@example.com");

        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        // スコープ違いの管理者: チームB では ADMIN だが チームA には一切属さない
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);

        em.flush();
        em.clear();

        screenId = createScreenAsAdminA();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 画面取得(getScreen)・一覧(listScreens)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("画面取得(getScreen)・一覧(listScreens)")
    class ScreenRead {

        @Test
        @DisplayName("非会員の画面取得は403(他チームの画面設定閲覧禁止)")
        void 非会員の画面取得は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/signage/screens/{id}", screenId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の画面取得は200")
        void 一般メンバーの画面取得は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/signage/screens/{id}", screenId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(screenId));
        }

        @Test
        @DisplayName("非会員の画面一覧取得は403")
        void 非会員の画面一覧取得は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/signage/screens")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の画面一覧取得は200")
        void 一般メンバーの画面一覧取得は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/signage/screens")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // スロット一覧(listSlots)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("スロット一覧(listSlots)")
    class SlotRead {

        @Test
        @DisplayName("非会員のスロット一覧取得は403")
        void 非会員のスロット一覧取得は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/signage/screens/{screenId}/slots", screenId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)のスロット一覧取得は200")
        void 一般メンバーのスロット一覧取得は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/signage/screens/{screenId}/slots", screenId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 緊急メッセージ履歴一覧(listEmergencyMessages)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("緊急メッセージ履歴一覧(listEmergencyMessages)")
    class EmergencyRead {

        @Test
        @DisplayName("非会員の緊急メッセージ履歴取得は403")
        void 非会員の緊急メッセージ履歴取得は403() throws Exception {
            broadcastEmergencyAsAdminA();

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/signage/screens/{id}/emergency", screenId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)の緊急メッセージ履歴取得は200")
        void 一般メンバーの緊急メッセージ履歴取得は200() throws Exception {
            broadcastEmergencyAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/signage/screens/{id}/emergency", screenId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // アクセストークン管理(issueToken/listTokens/revokeToken)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("アクセストークン管理(issueToken/listTokens/revokeToken)")
    class TokenManagement {

        @Test
        @DisplayName("スコープADMINのトークン発行は201")
        void スコープADMINのトークン発行は201() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/signage/screens/{id}/tokens", screenId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tokenRequestJson("正常系トークン", null)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.token").isNotEmpty());
        }

        @Test
        @DisplayName("未認証のトークン発行は401")
        void 未認証のトークン発行は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/signage/screens/{id}/tokens", screenId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tokenRequestJson("未認証トークン", null)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非会員のトークン発行は403")
        void 非会員のトークン発行は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/signage/screens/{id}/tokens", screenId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tokenRequestJson("非会員トークン", null)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("スコープ違いの管理者のトークン発行は403")
        void スコープ違いの管理者のトークン発行は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/signage/screens/{id}/tokens", screenId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tokenRequestJson("越境トークン", null)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一般メンバー(非ADMIN)のトークン発行は403")
        void 一般メンバーのトークン発行は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/signage/screens/{id}/tokens", screenId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tokenRequestJson("一般メンバートークン", null)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("スコープADMINのトークン一覧取得は200")
        void スコープADMINのトークン一覧取得は200() throws Exception {
            issueTokenAsAdminA("一覧確認トークン", null);

            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/signage/screens/{id}/tokens", screenId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }

        @Test
        @DisplayName("未認証のトークン一覧取得は401")
        void 未認証のトークン一覧取得は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/signage/screens/{id}/tokens", screenId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非会員のトークン一覧取得は403")
        void 非会員のトークン一覧取得は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/signage/screens/{id}/tokens", screenId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("スコープ違いの管理者のトークン一覧取得は403")
        void スコープ違いの管理者のトークン一覧取得は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/signage/screens/{id}/tokens", screenId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("スコープADMINのトークン無効化は204")
        void スコープADMINのトークン無効化は204() throws Exception {
            Long tokenId = issueTokenAsAdminA("無効化対象トークン", null).path("id").asLong();

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/signage/screens/tokens/{tokenId}", tokenId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("未認証のトークン無効化は401")
        void 未認証のトークン無効化は401() throws Exception {
            Long tokenId = issueTokenAsAdminA("未認証無効化対象トークン", null).path("id").asLong();

            SecurityContextHolder.clearContext();
            mockMvc.perform(delete("/api/v1/signage/screens/tokens/{tokenId}", tokenId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非会員のトークン無効化は403")
        void 非会員のトークン無効化は403() throws Exception {
            Long tokenId = issueTokenAsAdminA("非会員無効化対象トークン", null).path("id").asLong();

            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/signage/screens/tokens/{tokenId}", tokenId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("スコープ違いの管理者のトークン無効化は403")
        void スコープ違いの管理者のトークン無効化は403() throws Exception {
            Long tokenId = issueTokenAsAdminA("越境無効化対象トークン", null).path("id").asLong();

            setAuthentication(adminBId);
            mockMvc.perform(delete("/api/v1/signage/screens/tokens/{tokenId}", tokenId))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 表示API(GET /signage/{token}) のトークン有効期限
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("表示API(GET /signage/{token}) のトークン有効期限")
    class DisplayTokenExpiry {

        @Test
        @DisplayName("有効期限内のトークンでの表示取得は200（正常系）")
        void 有効期限内のトークンでの表示取得は200() throws Exception {
            String token = issueTokenAsAdminA("期限内トークン", LocalDateTime.now().plusDays(7))
                    .path("token").asText();

            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/signage/{token}", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.screen.id").value(screenId));
        }

        @Test
        @DisplayName("無期限(expiredAt=null)トークンでの表示取得は200（正常系）")
        void 無期限トークンでの表示取得は200() throws Exception {
            String token = issueTokenAsAdminA("無期限トークン", null).path("token").asText();

            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/signage/{token}", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.screen.id").value(screenId));
        }

        @Test
        @DisplayName("有効期限を過ぎたトークンでの表示取得は400(SIGNAGE_002)")
        void 有効期限を過ぎたトークンでの表示取得は400() throws Exception {
            String token = issueTokenAsAdminA("期限切れトークン", LocalDateTime.now().minusDays(1))
                    .path("token").asText();

            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/signage/{token}", token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SIGNAGE_002"));
        }

        @Test
        @DisplayName("無効化済みトークンでの表示取得は400(SIGNAGE_002)")
        void 無効化済みトークンでの表示取得は400() throws Exception {
            var issued = issueTokenAsAdminA("無効化済みトークン", LocalDateTime.now().plusDays(7));
            String token = issued.path("token").asText();

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/signage/screens/tokens/{tokenId}", issued.path("id").asLong()))
                    .andExpect(status().isNoContent());

            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/signage/{token}", token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SIGNAGE_002"));
        }

        @Test
        @DisplayName("存在しないトークンでの表示取得は400(SIGNAGE_002)")
        void 存在しないトークンでの表示取得は400() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/signage/{token}", java.util.UUID.randomUUID().toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SIGNAGE_002"));
        }

        @Test
        @DisplayName("発行時の有効期限が発行レスポンスと一覧取得の双方で返る")
        void 発行時の有効期限が発行レスポンスと一覧取得の双方で返る() throws Exception {
            // 発行レスポンス自体に有効期限が載ること（欠落・null のいずれも不可）
            JsonNode issued = issueTokenAsAdminA("期限永続化確認トークン", LocalDateTime.now().plusDays(30));
            assertThat(issued.hasNonNull("expiredAt")).isTrue();

            // 別リクエストで読み直しても有効期限が残っていること（＝永続化されていること）
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/signage/screens/{id}/tokens", screenId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].expiredAt").value(notNullValue()));
        }

        @Test
        @DisplayName("有効期限を指定しない発行は無期限(expiredAt=null)として永続化される")
        void 有効期限を指定しない発行は無期限として永続化される() throws Exception {
            issueTokenAsAdminA("無期限永続化確認トークン", null);

            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/signage/screens/{id}/tokens", screenId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].expiredAt").value(nullValue()));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** トークン発行リクエストの JSON を組み立てる（expiredAt が null なら無期限）。 */
    private String tokenRequestJson(String name, LocalDateTime expiredAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (expiredAt != null) {
            body.put("expiredAt", expiredAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        return objectMapper.writeValueAsString(body);
    }

    /**
     * adminA の認証コンテキストで screenId にトークンを1件発行し、レスポンスの data ノードを返す。
     * 呼び出し側は {@code path("token")} / {@code path("id")} で必要な値を取り出す。
     */
    private JsonNode issueTokenAsAdminA(String name, LocalDateTime expiredAt) throws Exception {
        setAuthentication(adminAId);
        String resp = mockMvc.perform(post("/api/v1/signage/screens/{id}/tokens", screenId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenRequestJson(name, expiredAt)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data");
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** adminA の認証コンテキストでチームAの画面を1件作成し、そのIDを返す。 */
    private Long createScreenAsAdminA() throws Exception {
        setAuthentication(adminAId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", "TEAM");
        body.put("scopeId", teamAId);
        body.put("name", "認可契約テスト画面 " + System.nanoTime());
        String resp = mockMvc.perform(post("/api/v1/signage/screens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /** adminA の認証コンテキストで screenId に緊急メッセージを1件配信する。 */
    private void broadcastEmergencyAsAdminA() throws Exception {
        setAuthentication(adminAId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "認可契約テスト緊急連絡");
        mockMvc.perform(post("/api/v1/signage/screens/{id}/emergency", screenId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
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
                                + "VALUES (:email, 'SIGNAGE-STG契約', 'テスト', 'SIGNAGE-STG契約テスト', 'ACTIVE', "
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
                                + "CONCAT('signage-stg-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
