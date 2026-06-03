package com.mannschaft.app.pointcard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.pointcard.dto.ResolveTokenResponse;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.service.PointCardShareTokenService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link OrgPointCardResolveController} の MockMvc 結合テスト
 * （F18 Phase 3 第二陣 2A）。
 *
 * <p>カバー観点:
 * <ul>
 *   <li>POST /resolve-by-token 正常系（200 + ResolveTokenResponse 形状）</li>
 *   <li>POST /resolve-by-token 不存在トークン → 404 POINT_CARD_019</li>
 *   <li>POST /resolve-by-token 他組織カード → 404 POINT_CARD_011</li>
 *   <li>POST /resolve-by-token 認可違反 → 403</li>
 *   <li>POST /resolve-by-token Bean Validation（token 必須 / 36 文字）</li>
 *   <li>レスポンスに暗号化対象が含まれない（barcodeValue / nickname / memo）</li>
 * </ul>
 */
@WebMvcTest(OrgPointCardResolveController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("OrgPointCardResolveController 結合テスト")
class OrgPointCardResolveControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long ORG_ID = 10L;
    private static final UUID CARD_ID = UUID.fromString("01956c00-0000-7000-8000-000000000eee");
    private static final UUID PROVIDER_ID = UUID.fromString("01956c00-0000-7000-8000-000000000fff");
    private static final String TOKEN = "01234567-89ab-4cde-8fed-cba987654321";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PointCardShareTokenService shareTokenService;

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
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    private ResolveTokenResponse sampleStampResponse() {
        return new ResolveTokenResponse(
                CARD_ID, PROVIDER_ID, "カフェ A スタンプ",
                PointCardProviderType.SELF_ISSUED_STAMP,
                "9999", 5, null);
    }

    private ResolveTokenResponse sampleBalanceResponse() {
        return new ResolveTokenResponse(
                CARD_ID, PROVIDER_ID, "カフェ A 残高",
                PointCardProviderType.SELF_ISSUED_BALANCE,
                "8888", null, new BigDecimal("1250.00"));
    }

    private String requestBody(String token) throws Exception {
        return objectMapper.writeValueAsString(
                new OrgPointCardResolveController.ResolveByTokenRequest(token));
    }

    // ──────────────────────────────────────────────
    // 正常系
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /resolve-by-token: 正常系 STAMP — 200 + currentStampCount 含む")
    void resolve_stamp_200() throws Exception {
        given(shareTokenService.resolve(eq(USER_ID), eq(ORG_ID), eq(TOKEN)))
                .willReturn(sampleStampResponse());

        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/resolve-by-token", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cardId").value(CARD_ID.toString()))
                .andExpect(jsonPath("$.data.providerId").value(PROVIDER_ID.toString()))
                .andExpect(jsonPath("$.data.providerDisplayName").value("カフェ A スタンプ"))
                .andExpect(jsonPath("$.data.providerType").value("SELF_ISSUED_STAMP"))
                .andExpect(jsonPath("$.data.last4").value("9999"))
                .andExpect(jsonPath("$.data.currentStampCount").value(5))
                .andExpect(jsonPath("$.data.currentBalance").doesNotExist())
                // 暗号化対象は一切返さない
                .andExpect(jsonPath("$.data.barcodeValue").doesNotExist())
                .andExpect(jsonPath("$.data.nickname").doesNotExist())
                .andExpect(jsonPath("$.data.memo").doesNotExist())
                .andExpect(jsonPath("$.data.displayName").doesNotExist());
    }

    @Test
    @DisplayName("POST /resolve-by-token: 正常系 BALANCE — currentBalance 含む / currentStampCount は null")
    void resolve_balance_200() throws Exception {
        given(shareTokenService.resolve(eq(USER_ID), eq(ORG_ID), eq(TOKEN)))
                .willReturn(sampleBalanceResponse());

        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/resolve-by-token", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerType").value("SELF_ISSUED_BALANCE"))
                .andExpect(jsonPath("$.data.currentBalance").value(1250.00))
                .andExpect(jsonPath("$.data.currentStampCount").doesNotExist());
    }

    // ──────────────────────────────────────────────
    // 業務エラー
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /resolve-by-token: 不存在トークン → 404 POINT_CARD_019")
    void resolve_tokenNotFound_404() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.TOKEN_NOT_FOUND))
                .given(shareTokenService).resolve(anyLong(), anyLong(), anyString());

        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/resolve-by-token", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(TOKEN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_019"));
    }

    @Test
    @DisplayName("POST /resolve-by-token: 他組織のカード → 404 POINT_CARD_011")
    void resolve_otherOrg_404() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.PROVIDER_NOT_OWNED))
                .given(shareTokenService).resolve(anyLong(), anyLong(), anyString());

        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/resolve-by-token", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(TOKEN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_011"));
    }

    @Test
    @DisplayName("POST /resolve-by-token: 認可違反 → 403")
    void resolve_accessDenied_403() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(shareTokenService).resolve(anyLong(), anyLong(), anyString());

        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/resolve-by-token", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(TOKEN)))
                .andExpect(status().isForbidden());
    }

    // ──────────────────────────────────────────────
    // バリデーション
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /resolve-by-token: token 欠落 → 400")
    void resolve_missingToken_400() throws Exception {
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/resolve-by-token", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /resolve-by-token: token 長さ違反（36 文字以外） → 400")
    void resolve_invalidTokenLength_400() throws Exception {
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/resolve-by-token", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("too-short")))
                .andExpect(status().isBadRequest());
    }
}
