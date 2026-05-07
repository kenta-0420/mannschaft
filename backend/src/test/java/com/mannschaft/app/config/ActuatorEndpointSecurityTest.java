package com.mannschaft.app.config;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F10.5 Phase 10-α §5.1.1 / §6.1: Actuator エンドポイントのセキュリティテスト。
 *
 * <p>{@link SecurityConfig} の改修により以下を担保する:</p>
 * <ul>
 *   <li>{@code /actuator/health} は匿名で 200 (Liveness/Readiness 用)</li>
 *   <li>{@code /actuator/metrics} は匿名で 401 / 403</li>
 *   <li>{@code /actuator/prometheus} は匿名で 401 / 403</li>
 *   <li>SYSTEM_ADMIN ロール保持者であれば上記 metrics / prometheus も 200</li>
 * </ul>
 *
 * <p>本テストは Spring Security のロジック検証なので Mock 認証 (WithMockUser) を使用する。
 * JWT トークン発行や DB 認証は対象外。</p>
 */
@AutoConfigureMockMvc
@DisplayName("Actuator エンドポイントのセキュリティ (F10.5 Phase 10-α)")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ActuatorEndpointSecurityTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: /actuator/health は 200")
    void anonymous_can_access_health() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: /actuator/metrics は 401 または 403")
    void anonymous_cannot_access_metrics() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status != 401 && status != 403) {
                        throw new AssertionError("expected 401 or 403, got " + status);
                    }
                });
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: /actuator/prometheus は 401 または 403")
    void anonymous_cannot_access_prometheus() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status != 401 && status != 403) {
                        throw new AssertionError("expected 401 or 403, got " + status);
                    }
                });
    }

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    @DisplayName("SYSTEM_ADMIN: /actuator/metrics は 200")
    void system_admin_can_access_metrics() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    @DisplayName("SYSTEM_ADMIN: /actuator/prometheus は 200")
    void system_admin_can_access_prometheus() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }
}
