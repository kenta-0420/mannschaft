package com.mannschaft.app.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 検分 P1-5(e) 根治: {@code SecurityConfig} 一層目（実 Security フィルタチェーン）を検証する。
 *
 * <p>{@code ProvisioningAcceptanceIT} は {@code addFilters = false} のため、コントローラ内の
 * 手動認証チェックのみを検証しており、{@code SecurityConfig} の
 * {@code /api/v1/system-admin/** -> hasRole("SYSTEM_ADMIN")} という一層目の宣言自体は
 * 未検証だった。本クラスは {@code addFilters} を付けず（既定 true）実フィルタチェーンを通し、
 * 匿名 401・非 SYSTEM_ADMIN 403 を検証する（金型: {@code FeatureFlagControllerIT}）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱②-2 販促プロビジョニング SecurityConfig 一層目（実フィルタチェーン）")
class ProvisioningSecurityConfigLayerIT extends AbstractMySqlIntegrationTest {

    private static final String CREATE_ORG_ENDPOINT = "/api/v1/system-admin/provisioning/organizations";
    private static final String PREVIEW_ENDPOINT = "/api/v1/provisioning/invitations/preview";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("SecurityConfig一層目: 匿名での組織作成は401")
    void anonymousCreateOrganizationReturns401() throws Exception {
        Map<String, Object> body = Map.of("name", "SecurityConfig検証組織", "inviteEmail", "invited@example.com");

        mockMvc.perform(post(CREATE_ORG_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SecurityConfig一層目: SYSTEM_ADMIN以外（一般MEMBER）での組織作成は403")
    @WithMockUser(username = "1", roles = "MEMBER")
    void nonSystemAdminCreateOrganizationReturns403() throws Exception {
        Map<String, Object> body = Map.of("name", "SecurityConfig検証組織2", "inviteEmail", "invited@example.com");

        mockMvc.perform(post(CREATE_ORG_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SecurityConfig一層目: preview()はController内にSecurityUtilsチェックが無く、"
            + "SecurityConfigの宣言的authenticated()のみで守られる（未認証は401）")
    void anonymousPreviewReturns401() throws Exception {
        Map<String, Object> body = Map.of("token", "irrelevant-token");

        mockMvc.perform(post(PREVIEW_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }
}
