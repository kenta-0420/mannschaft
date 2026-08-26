package com.mannschaft.app.pointcard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.pointcard.dto.CreateOrgProviderRequest;
import com.mannschaft.app.pointcard.dto.CustomerQrResponse;
import com.mannschaft.app.pointcard.dto.PointCardProviderResponse;
import com.mannschaft.app.pointcard.dto.UpdateOrgProviderRequest;
import com.mannschaft.app.pointcard.enums.PointCardCategory;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.service.OrgPointCardProviderService;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link OrgPointCardProviderController} の MockMvc 結合テスト（F18 Phase 2 S2B）。
 *
 * <p>カバー観点:
 * <ul>
 *   <li>各エンドポイント HTTP ステータス + JSON 形状（GET 200 / POST 201 / DELETE 204）</li>
 *   <li>POINT_CARD_010 (PROVIDER_LIMIT_EXCEEDED) は 409</li>
 *   <li>POINT_CARD_011 (PROVIDER_NOT_OWNED) は 404</li>
 *   <li>COMMON_002 権限不足は 403</li>
 *   <li>新規発行のバリデーション（displayName 空）は 400</li>
 *   <li>顧客 QR エンドポイントが deepLinkUrl + webUrl を返す</li>
 * </ul>
 */
@WebMvcTest(OrgPointCardProviderController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("OrgPointCardProviderController 結合テスト")
class OrgPointCardProviderControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long ORG_ID = 42L;
    private static final UUID PROVIDER_ID =
            UUID.fromString("01956c00-0000-7000-8000-000000000ccc");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrgPointCardProviderService service;

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

    private PointCardProviderResponse sampleResponse() {
        return new PointCardProviderResponse(
                PROVIDER_ID,
                "org_" + ORG_ID + "_12345678",
                "サロン○○ ポイント",
                PointCardCategory.OTHER,
                PointCardProviderType.SELF_ISSUED_STAMP,
                ORG_ID,
                "https://r2.example.com/logos/salon.png",
                "#FF6699",
                null,
                "8 桁の数字",
                null,
                true
        );
    }

    // ─────────────────────────────────────────────
    // GET 一覧
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /providers: 200 で一覧を返す（activeOnly=false）")
    void list_200_all() throws Exception {
        given(service.listOrgProviders(eq(ORG_ID), eq(USER_ID), eq(false)))
                .willReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/organizations/{orgId}/point-cards/providers", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(PROVIDER_ID.toString()))
                .andExpect(jsonPath("$.data[0].type").value("SELF_ISSUED_STAMP"))
                .andExpect(jsonPath("$.data[0].organizationId").value(ORG_ID));
    }

    @Test
    @DisplayName("GET /providers?active=true: activeOnly=true で Service を呼び出す")
    void list_200_activeOnly() throws Exception {
        given(service.listOrgProviders(eq(ORG_ID), eq(USER_ID), eq(true)))
                .willReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/organizations/{orgId}/point-cards/providers", ORG_ID)
                        .param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].isActive").value(true));
    }

    // ─────────────────────────────────────────────
    // POST 新規発行
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /providers: 201 でレスポンスを返す")
    void create_201() throws Exception {
        given(service.createOrgProvider(eq(ORG_ID), eq(USER_ID), any(CreateOrgProviderRequest.class)))
                .willReturn(sampleResponse());

        CreateOrgProviderRequest req = new CreateOrgProviderRequest(
                "サロン○○ ポイント", "#FF6699",
                "https://r2.example.com/logos/salon.png",
                "^[0-9]{8}$", "8 桁の数字");
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/providers", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(PROVIDER_ID.toString()))
                .andExpect(jsonPath("$.data.type").value("SELF_ISSUED_STAMP"))
                .andExpect(jsonPath("$.data.organizationId").value(ORG_ID));
    }

    @Test
    @DisplayName("POST /providers: 20 個上限 → 409 POINT_CARD_010")
    void create_limit_409() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.PROVIDER_LIMIT_EXCEEDED))
                .given(service).createOrgProvider(
                        eq(ORG_ID), eq(USER_ID), any(CreateOrgProviderRequest.class));

        CreateOrgProviderRequest req = new CreateOrgProviderRequest(
                "別店舗", null, null, null, null);
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/providers", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_010"));
    }

    @Test
    @DisplayName("POST /providers: displayName 空はバリデーション 400")
    void create_blankName_400() throws Exception {
        String body = "{\"displayName\":\"\"}";
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/providers", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /providers: 権限不足 → 403 COMMON_002")
    void create_forbidden_403() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(service).createOrgProvider(
                        eq(ORG_ID), eq(USER_ID), any(CreateOrgProviderRequest.class));

        CreateOrgProviderRequest req = new CreateOrgProviderRequest(
                "店舗", null, null, null, null);
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/providers", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    // ─────────────────────────────────────────────
    // PATCH 編集
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /providers/{id}: 200 で更新後を返す")
    void update_200() throws Exception {
        given(service.updateOrgProvider(
                eq(ORG_ID), eq(PROVIDER_ID), eq(USER_ID), any(UpdateOrgProviderRequest.class)))
                .willReturn(sampleResponse());

        UpdateOrgProviderRequest req = new UpdateOrgProviderRequest(
                "新名", null, null, null, null);
        mockMvc.perform(patch("/api/v1/organizations/{orgId}/point-cards/providers/{pid}",
                        ORG_ID, PROVIDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(PROVIDER_ID.toString()));
    }

    @Test
    @DisplayName("PATCH /providers/{id}: 他組織 provider は 404 POINT_CARD_011 (IDOR)")
    void update_idor_404() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.PROVIDER_NOT_OWNED))
                .given(service).updateOrgProvider(
                        eq(ORG_ID), eq(PROVIDER_ID), eq(USER_ID),
                        any(UpdateOrgProviderRequest.class));

        UpdateOrgProviderRequest req = new UpdateOrgProviderRequest(
                "侵入", null, null, null, null);
        mockMvc.perform(patch("/api/v1/organizations/{orgId}/point-cards/providers/{pid}",
                        ORG_ID, PROVIDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_011"));
    }

    // ─────────────────────────────────────────────
    // DELETE 停止
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /providers/{id}: 204 No Content で停止")
    void deactivate_204() throws Exception {
        willDoNothing().given(service)
                .deactivateOrgProvider(eq(ORG_ID), eq(PROVIDER_ID), eq(USER_ID));

        mockMvc.perform(delete("/api/v1/organizations/{orgId}/point-cards/providers/{pid}",
                        ORG_ID, PROVIDER_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /providers/{id}: DEPUTY_ADMIN（isAdmin=false）は 403 COMMON_002")
    void deactivate_deputy_403() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(service)
                .deactivateOrgProvider(eq(ORG_ID), eq(PROVIDER_ID), eq(USER_ID));

        mockMvc.perform(delete("/api/v1/organizations/{orgId}/point-cards/providers/{pid}",
                        ORG_ID, PROVIDER_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    // ─────────────────────────────────────────────
    // GET customer-qr
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /providers/{id}/customer-qr: 200 で deepLinkUrl と webUrl を返す")
    void customerQr_200() throws Exception {
        CustomerQrResponse qr = new CustomerQrResponse(
                PROVIDER_ID, "サロン○○ ポイント",
                "mannschaft://wallet/add-from-qr?providerId=" + PROVIDER_ID,
                "https://mannschaft.example.com/wallet/add-from-qr?providerId=" + PROVIDER_ID);
        given(service.getCustomerQr(eq(ORG_ID), eq(PROVIDER_ID), eq(USER_ID)))
                .willReturn(qr);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/point-cards/providers/{pid}/customer-qr",
                        ORG_ID, PROVIDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerId").value(PROVIDER_ID.toString()))
                .andExpect(jsonPath("$.data.deepLinkUrl")
                        .value("mannschaft://wallet/add-from-qr?providerId=" + PROVIDER_ID))
                .andExpect(jsonPath("$.data.webUrl")
                        .value("https://mannschaft.example.com/wallet/add-from-qr?providerId=" + PROVIDER_ID));
    }
}
