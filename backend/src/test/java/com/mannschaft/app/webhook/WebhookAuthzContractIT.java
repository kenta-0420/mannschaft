package com.mannschaft.app.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 2 トランシェ2A #4 webhook ドメイン API契約テスト（試練 / red 先行→出陣で green）。
 *
 * <p>対象: {@code com.mannschaft.app.webhook} 配下の4コントローラ
 * （ApiKey / WebhookEndpoint / IncomingWebhook / WebhookDelivery）。
 * 出陣前は全入口に認可（{@code AccessControlService.checkAdminOrAbove}）が存在せず、
 * 誰でも他スコープの APIキー・Webhookシークレット・受信トークンを閲覧/操作できた（IDOR）。</p>
 *
 * <p>金型: {@code TeamAdvertiserScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実MySQL + 手動 SecurityContext）。Spring Security フィルタは無効化するが、越境403は
 * {@code AccessControlService.checkAdminOrAbove} のアプリケーション層例外（{@code COMMON_002} → 403）
 * として発生するためフィルタ無効でも検証できる。未認証は {@code SecurityUtils.getCurrentUserId()} が
 * 投げる {@code COMMON_000} → 401 で検証する。</p>
 *
 * <p>4象限: 未認証(401) / 別スコープADMIN=BOLA攻撃者(403) / 対象スコープの非ADMINメンバー(403) /
 * 正当な対象スコープADMIN(成功)。entity由来scopeでの認可（★BOLA厳禁★）を、
 * path上のidのみでscopeパラメータを持たないrevoke/get/update/delete/retry系で重点検証する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("認可Wave2 #4 webhookドメイン API契約テスト（試練）")
class WebhookAuthzContractIT extends AbstractMySqlIntegrationTest {

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

    @BeforeEach
    void setUp() {
        insertRole("ADMIN", "管理者", 2);
        Long adminRoleId = roleId("ADMIN");

        adminAId = insertUser("w2webhook-team-a-admin@example.com");
        adminBId = insertUser("w2webhook-team-b-admin@example.com");
        memberAId = insertUser("w2webhook-team-a-member@example.com");

        teamAId = insertTeam("W2Webhook チームA");
        teamBId = insertTeam("W2Webhook チームB");

        insertUserRole(adminAId, adminRoleId, teamAId);
        insertUserRole(adminBId, adminRoleId, teamBId);
        insertMembership(memberAId, teamAId);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // ApiKeyController / ApiKeyService
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ApiKeyController: APIキー発行・一覧・失効の認可")
    class ApiKeyAuthz {

        @Test
        @DisplayName("POST /api/api-keys: 未認証は401")
        void issueApiKey_未認証は401() throws Exception {
            mockMvc.perform(post("/api/api-keys")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(issueApiKeyBody(teamAId))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /api/api-keys: 対象スコープの非ADMINメンバーは403")
        void issueApiKey_非ADMINメンバーは403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/api-keys")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(issueApiKeyBody(teamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /api/api-keys: 別スコープADMIN（BOLA攻撃者）が他チーム宛に発行しようとすると403")
        void issueApiKey_別スコープADMINは403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/api-keys")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(issueApiKeyBody(teamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /api/api-keys: 対象スコープの正当ADMINは201・rawKeyを1回だけ含む")
        void issueApiKey_正当ADMINは201() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/api-keys")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(issueApiKeyBody(teamAId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists())
                    .andExpect(jsonPath("$.data.rawKey").isNotEmpty());
        }

        @Test
        @DisplayName("GET /api/api-keys: 別スコープADMINが他チームscopeIdを指定すると403")
        void listApiKeys_別スコープADMINは403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(get("/api/api-keys")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /api/api-keys: 正当ADMINは200で一覧取得できる")
        void listApiKeys_正当ADMINは200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/api-keys")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }

        @Test
        @DisplayName("DELETE /api/api-keys/{id}: pathにscopeパラメータが無くとも別スコープADMINは403（★BOLA厳禁★entity由来scopeで判定）")
        void revokeApiKey_別スコープADMINはentity由来scopeで403() throws Exception {
            setAuthentication(adminAId);
            Long apiKeyId = createApiKeyAndGetId(teamAId);

            // 攻撃者: teamBのADMIN。pathにscopeIdは無い（idのみ）が、entity由来でteamA所属と判定され403
            setAuthentication(adminBId);
            mockMvc.perform(delete("/api/api-keys/{id}", apiKeyId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("DELETE /api/api-keys/{id}: 正当ADMINは204")
        void revokeApiKey_正当ADMINは204() throws Exception {
            setAuthentication(adminAId);
            Long apiKeyId = createApiKeyAndGetId(teamAId);

            mockMvc.perform(delete("/api/api-keys/{id}", apiKeyId))
                    .andExpect(status().isNoContent());
        }

        private Long createApiKeyAndGetId(Long scopeId) throws Exception {
            String body = mockMvc.perform(post("/api/api-keys")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(issueApiKeyBody(scopeId))))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(body).path("data").path("id").asLong();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // IncomingWebhookController / IncomingWebhookService
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IncomingWebhookController: 受信トークン発行・一覧・失効の認可＋生トークンマスク")
    class IncomingWebhookAuthz {

        @Test
        @DisplayName("POST /api/webhooks/incoming: 未認証は401")
        void createToken_未認証は401() throws Exception {
            mockMvc.perform(post("/api/webhooks/incoming")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTokenBody(teamAId))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /api/webhooks/incoming: 非ADMINメンバーは403")
        void createToken_非ADMINメンバーは403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/webhooks/incoming")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTokenBody(teamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /api/webhooks/incoming: 別スコープADMINは403")
        void createToken_別スコープADMINは403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/webhooks/incoming")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTokenBody(teamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST→GET一覧: 発行直後は生token、一覧では平文露出せずマスクされる")
        void listTokens_一覧では生tokenがマスクされる() throws Exception {
            setAuthentication(adminAId);

            String createBody = mockMvc.perform(post("/api/webhooks/incoming")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTokenBody(teamAId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.token").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();
            String rawToken = objectMapper.readTree(createBody).path("data").path("token").asText();

            String listBody = mockMvc.perform(get("/api/webhooks/incoming")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String listedToken = objectMapper.readTree(listBody).path("data").get(0).path("token").asText();

            org.assertj.core.api.Assertions.assertThat(listedToken)
                    .as("一覧のtokenは生の平文と一致してはならない（マスク必須）")
                    .isNotEqualTo(rawToken);
            org.assertj.core.api.Assertions.assertThat(listedToken).contains("*");
        }

        @Test
        @DisplayName("GET /api/webhooks/incoming: 別スコープADMINは403")
        void listTokens_別スコープADMINは403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(get("/api/webhooks/incoming")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("DELETE /api/webhooks/incoming/{id}: pathにscopeが無くとも別スコープADMINはentity由来scopeで403")
        void revokeToken_別スコープADMINはentity由来scopeで403() throws Exception {
            setAuthentication(adminAId);
            Long tokenId = createTokenAndGetId(teamAId);

            setAuthentication(adminBId);
            mockMvc.perform(delete("/api/webhooks/incoming/{id}", tokenId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("DELETE /api/webhooks/incoming/{id}: 正当ADMINは204")
        void revokeToken_正当ADMINは204() throws Exception {
            setAuthentication(adminAId);
            Long tokenId = createTokenAndGetId(teamAId);

            mockMvc.perform(delete("/api/webhooks/incoming/{id}", tokenId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("POST /incoming/{token}: 受信エンドポイントは意図どおり認証不要のまま（permitAll維持）")
        void processIncoming_公開受信は認証不要のまま維持される() throws Exception {
            setAuthentication(adminAId);
            Long tokenId = createTokenAndGetId(teamAId);
            String rawToken = tokenValueById(tokenId);

            // SecurityContextをクリアして完全未認証状態で受信APIを叩く
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/incoming/{token}", rawToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
        }

        private Long createTokenAndGetId(Long scopeId) throws Exception {
            String body = mockMvc.perform(post("/api/webhooks/incoming")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTokenBody(scopeId))))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(body).path("data").path("id").asLong();
        }

        private String tokenValueById(Long id) {
            return (String) em.createNativeQuery(
                            "SELECT token FROM incoming_webhook_tokens WHERE id = :id")
                    .setParameter("id", id)
                    .getSingleResult();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // WebhookEndpointController / WebhookEndpointService
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WebhookEndpointController: エンドポイント作成・取得・一覧・更新・削除の認可")
    class WebhookEndpointAuthz {

        @Test
        @DisplayName("POST /api/webhooks/endpoints: 未認証は401")
        void createEndpoint_未認証は401() throws Exception {
            mockMvc.perform(post("/api/webhooks/endpoints")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createEndpointBody(teamAId))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /api/webhooks/endpoints: 非ADMINメンバーは403")
        void createEndpoint_非ADMINメンバーは403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/webhooks/endpoints")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createEndpointBody(teamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /api/webhooks/endpoints: 別スコープADMINは403")
        void createEndpoint_別スコープADMINは403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/webhooks/endpoints")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createEndpointBody(teamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /api/webhooks/endpoints: 正当ADMINは201でsigningSecretを1回だけ含む")
        void createEndpoint_正当ADMINは201() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/webhooks/endpoints")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createEndpointBody(teamAId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists())
                    .andExpect(jsonPath("$.data.signingSecret").isNotEmpty());
        }

        @Test
        @DisplayName("GET /api/webhooks/endpoints/{id}: pathにscopeが無くとも別スコープADMINはentity由来scopeで403")
        void getEndpoint_別スコープADMINはentity由来scopeで403() throws Exception {
            setAuthentication(adminAId);
            Long endpointId = createEndpointAndGetId(teamAId);

            setAuthentication(adminBId);
            mockMvc.perform(get("/api/webhooks/endpoints/{id}", endpointId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /api/webhooks/endpoints/{id}: 正当ADMINは200・signingSecretは含まれない")
        void getEndpoint_正当ADMINは200でsigningSecret非露出() throws Exception {
            setAuthentication(adminAId);
            Long endpointId = createEndpointAndGetId(teamAId);

            mockMvc.perform(get("/api/webhooks/endpoints/{id}", endpointId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(endpointId))
                    .andExpect(jsonPath("$.data.signingSecret").doesNotExist());
        }

        @Test
        @DisplayName("GET /api/webhooks/endpoints: 別スコープADMINは403")
        void listEndpoints_別スコープADMINは403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(get("/api/webhooks/endpoints")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PUT /api/webhooks/endpoints/{id}: 別スコープADMINはentity由来scopeで403")
        void updateEndpoint_別スコープADMINはentity由来scopeで403() throws Exception {
            setAuthentication(adminAId);
            Long endpointId = createEndpointAndGetId(teamAId);

            setAuthentication(adminBId);
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("name", "乗っ取り改称");
            mockMvc.perform(put("/api/webhooks/endpoints/{id}", endpointId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(update)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PUT /api/webhooks/endpoints/{id}: 正当ADMINは200で更新できる")
        void updateEndpoint_正当ADMINは200() throws Exception {
            setAuthentication(adminAId);
            Long endpointId = createEndpointAndGetId(teamAId);

            Map<String, Object> update = new LinkedHashMap<>();
            update.put("name", "更新後の名前");
            mockMvc.perform(put("/api/webhooks/endpoints/{id}", endpointId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(update)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("更新後の名前"));
        }

        @Test
        @DisplayName("DELETE /api/webhooks/endpoints/{id}: 別スコープADMINはentity由来scopeで403")
        void deleteEndpoint_別スコープADMINはentity由来scopeで403() throws Exception {
            setAuthentication(adminAId);
            Long endpointId = createEndpointAndGetId(teamAId);

            setAuthentication(adminBId);
            mockMvc.perform(delete("/api/webhooks/endpoints/{id}", endpointId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("DELETE /api/webhooks/endpoints/{id}: 正当ADMINは204")
        void deleteEndpoint_正当ADMINは204() throws Exception {
            setAuthentication(adminAId);
            Long endpointId = createEndpointAndGetId(teamAId);

            mockMvc.perform(delete("/api/webhooks/endpoints/{id}", endpointId))
                    .andExpect(status().isNoContent());
        }

        private Long createEndpointAndGetId(Long scopeId) throws Exception {
            String body = mockMvc.perform(post("/api/webhooks/endpoints")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createEndpointBody(scopeId))))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(body).path("data").path("id").asLong();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // WebhookDeliveryController / WebhookDeliveryService
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WebhookDeliveryController: 配信ログ一覧・再送の認可")
    class WebhookDeliveryAuthz {

        @Test
        @DisplayName("GET /api/webhooks/endpoints/{endpointId}/deliveries: 別スコープADMINはentity由来scopeで403")
        void listDeliveryLogs_別スコープADMINはentity由来scopeで403() throws Exception {
            setAuthentication(adminAId);
            Long endpointId = createEndpointAndGetId(teamAId);

            setAuthentication(adminBId);
            mockMvc.perform(get("/api/webhooks/endpoints/{endpointId}/deliveries", endpointId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /api/webhooks/endpoints/{endpointId}/deliveries: 正当ADMINは200")
        void listDeliveryLogs_正当ADMINは200() throws Exception {
            setAuthentication(adminAId);
            Long endpointId = createEndpointAndGetId(teamAId);

            mockMvc.perform(get("/api/webhooks/endpoints/{endpointId}/deliveries", endpointId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }

        @Test
        @DisplayName("POST /api/webhooks/deliveries/{id}/retry: 別スコープADMINはentity由来scope(配信ログ→エンドポイント)で403")
        void retryDelivery_別スコープADMINはentity由来scopeで403() throws Exception {
            setAuthentication(adminAId);
            Long endpointId = createEndpointAndGetId(teamAId);
            Long deliveryLogId = insertDeliveryLog(endpointId);

            setAuthentication(adminBId);
            mockMvc.perform(post("/api/webhooks/deliveries/{id}/retry", deliveryLogId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /api/webhooks/deliveries/{id}/retry: 正当ADMINは200")
        void retryDelivery_正当ADMINは200() throws Exception {
            setAuthentication(adminAId);
            Long endpointId = createEndpointAndGetId(teamAId);
            Long deliveryLogId = insertDeliveryLog(endpointId);

            mockMvc.perform(post("/api/webhooks/deliveries/{id}/retry", deliveryLogId))
                    .andExpect(status().isOk());
        }

        private Long createEndpointAndGetId(Long scopeId) throws Exception {
            String body = mockMvc.perform(post("/api/webhooks/endpoints")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createEndpointBody(scopeId))))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(body).path("data").path("id").asLong();
        }

        private Long insertDeliveryLog(Long endpointId) {
            em.createNativeQuery(
                            "INSERT INTO webhook_delivery_logs "
                                    + "(endpoint_id, event_type, event_id, request_payload, delivery_status, "
                                    + "retry_count, created_at, updated_at) "
                                    + "VALUES (:eid, 'TEST_EVENT', :evid, '{}', :status, 0, NOW(), NOW())")
                    .setParameter("eid", endpointId)
                    .setParameter("evid", java.util.UUID.randomUUID().toString())
                    .setParameter("status", DeliveryStatus.FAILED.name())
                    .executeUpdate();
            return ((Number) em.createNativeQuery(
                            "SELECT MAX(id) FROM webhook_delivery_logs WHERE endpoint_id = :eid")
                    .setParameter("eid", endpointId)
                    .getSingleResult()).longValue();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // リクエストボディヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> issueApiKeyBody(Long scopeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", "TEAM");
        body.put("scopeId", scopeId);
        body.put("name", "W2Webhook APIキー");
        body.put("description", "契約テスト用");
        body.put("permissions", List.of("READ"));
        return body;
    }

    private Map<String, Object> createTokenBody(Long scopeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", "TEAM");
        body.put("scopeId", scopeId);
        body.put("name", "W2Webhook 受信トークン");
        body.put("description", "契約テスト用");
        return body;
    }

    private Map<String, Object> createEndpointBody(Long scopeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", "TEAM");
        body.put("scopeId", scopeId);
        body.put("name", "W2Webhook エンドポイント");
        body.put("url", "https://example.com/w2webhook/receive");
        body.put("description", "契約テスト用");
        // retryDelivery のentity由来scope認可テストで実HTTP呼び出しが発生しうるため、
        // Service内自己呼び出し（@Async self-invocation）で同期実行された場合の待ち時間を短く抑える
        body.put("timeoutMs", 2000);
        body.put("eventTypes", List.of());
        return body;
    }

    // ═════════════════════════════════════════════════════════════════════
    // フィクスチャヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void insertRole(String name, String displayName, int priority) {
        boolean exists = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue() > 0;
        if (exists) {
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

    private Long roleId(String name) {
        return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
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
                                + "VALUES (:email, 'W2Webhook', 'テスト', 'W2Webhook テスト', 'ACTIVE', "
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

    private void insertUserRole(Long uid, Long roleIdParam, Long teamIdParam) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "VALUES (:uid, :rid, :tid, NULL, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleIdParam)
                .setParameter("tid", teamIdParam)
                .executeUpdate();
    }

    /** チームの非ADMIN一般メンバー(MEMBER)としてmembershipsに登録する。 */
    private void insertMembership(Long uid, Long teamIdParam) {
        em.createNativeQuery(
                        "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, "
                                + "created_at, updated_at) "
                                + "VALUES (:uid, 'TEAM', :tid, 'MEMBER', NOW(), NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("tid", teamIdParam)
                .executeUpdate();
    }
}
