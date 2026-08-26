package com.mannschaft.app.advertising.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.AdvertiserAccountStatus;
import com.mannschaft.app.advertising.BillingMethod;
import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.controller.TeamAdvertiserAccountController;
import com.mannschaft.app.advertising.dto.AdvertiserAccountResponse;
import com.mannschaft.app.advertising.dto.RegisterAdvertiserRequest;
import com.mannschaft.app.advertising.service.AdvertiserAccountService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * F09.17 {@link TeamAdvertiserAccountController} 結合テスト。
 *
 * <p>チームスコープ URL {@code POST /api/v1/teams/{teamId}/advertiser/register} の
 * 権限検証（TEAM ADMIN 以上要求）と Service 呼び出しが正しく
 * {@code scope_type=TEAM, scope_id=teamId} で行われることを検証する。</p>
 */
@WebMvcTest(TeamAdvertiserAccountController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("TeamAdvertiserAccountController 結合テスト (F09.17 チーム広告主登録)")
class TeamAdvertiserAccountControllerIT {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 7300L;
    private static final Long ACCOUNT_ID = 99L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AdvertiserAccountService advertiserAccountService;

    @MockitoBean
    private AccessControlService accessControlService;

    // フィルタ依存
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private RegisterAdvertiserRequest validRequest() {
        return new RegisterAdvertiserRequest("チーム株式会社", "team-ads@example.com", BillingMethod.STRIPE);
    }

    private AdvertiserAccountResponse stubAccountResponse() {
        return new AdvertiserAccountResponse(
                ACCOUNT_ID,
                ScopeType.TEAM,
                TEAM_ID,
                AdvertiserAccountStatus.PENDING,
                "チーム株式会社",
                "team-ads@example.com",
                BillingMethod.STRIPE,
                new BigDecimal("100000"),
                null,
                LocalDateTime.now()
        );
    }

    @Nested
    @DisplayName("POST /api/v1/teams/{teamId}/advertiser/register")
    class Register {

        @Test
        @DisplayName("ハッピーパス: TEAM ADMIN が登録 → 201、TEAM スコープで Service 呼び出し")
        void 正常系_201_team_scope() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
            given(advertiserAccountService.register(
                    eq(ScopeType.TEAM), eq(TEAM_ID), any(RegisterAdvertiserRequest.class)))
                    .willReturn(stubAccountResponse());

            mockMvc.perform(post("/api/v1/teams/{teamId}/advertiser/register", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(ACCOUNT_ID))
                    .andExpect(jsonPath("$.data.scopeType").value("TEAM"))
                    .andExpect(jsonPath("$.data.scopeId").value(TEAM_ID))
                    .andExpect(jsonPath("$.data.status").value("PENDING"))
                    .andExpect(jsonPath("$.data.companyName").value("チーム株式会社"));

            verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
            verify(advertiserAccountService).register(eq(ScopeType.TEAM), eq(TEAM_ID), any());
        }

        @Test
        @DisplayName("権限拒否: TEAM ADMIN 未満 → COMMON_002 → 403")
        void 権限拒否_403() throws Exception {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService)
                    .checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            mockMvc.perform(post("/api/v1/teams/{teamId}/advertiser/register", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("重複登録: 同一チームに既に広告主アカウント存在 → AD_006 → 409")
        void 重複登録_409() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
            willThrow(new BusinessException(AdvertisingErrorCode.AD_006))
                    .given(advertiserAccountService)
                    .register(eq(ScopeType.TEAM), eq(TEAM_ID), any(RegisterAdvertiserRequest.class));

            mockMvc.perform(post("/api/v1/teams/{teamId}/advertiser/register", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("バリデーション: companyName が空 → 400")
        void バリデーション_400_companyName_空() throws Exception {
            RegisterAdvertiserRequest badRequest =
                    new RegisterAdvertiserRequest("", "team-ads@example.com", BillingMethod.STRIPE);

            mockMvc.perform(post("/api/v1/teams/{teamId}/advertiser/register", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(badRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("バリデーション: contactEmail が不正な形式 → 400")
        void バリデーション_400_email_不正() throws Exception {
            RegisterAdvertiserRequest badRequest =
                    new RegisterAdvertiserRequest("チーム株式会社", "not-an-email", BillingMethod.STRIPE);

            mockMvc.perform(post("/api/v1/teams/{teamId}/advertiser/register", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(badRequest)))
                    .andExpect(status().isBadRequest());
        }
    }
}
