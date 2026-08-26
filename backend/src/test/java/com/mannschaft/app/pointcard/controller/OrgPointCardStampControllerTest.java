package com.mannschaft.app.pointcard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.pointcard.dto.StampEventResponse;
import com.mannschaft.app.pointcard.dto.StampRequest;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.service.PointCardStampService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link OrgPointCardStampController} の MockMvc 結合テスト（F18 Phase 2 第二陣 2C）。
 *
 * <p>カバー観点:
 * <ul>
 *   <li>POST /stamps 正常系（201 + JSON 形状）</li>
 *   <li>POST /stamps バリデーション（delta 必須 / 範囲）</li>
 *   <li>POST /stamps 業務エラー → 適切な HTTP（012=400 / 認可違反=403）</li>
 *   <li>GET /stamps 一覧（Page JSON 形状）</li>
 *   <li>GET /{cardId}/stamps 単一カード履歴</li>
 * </ul>
 */
@WebMvcTest(OrgPointCardStampController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("OrgPointCardStampController 結合テスト")
class OrgPointCardStampControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long ORG_ID = 10L;
    private static final UUID CARD_ID = UUID.fromString("01956c00-0000-7000-8000-000000000bbb");
    private static final UUID PROVIDER_ID = UUID.fromString("01956c00-0000-7000-8000-000000000ccc");
    private static final UUID EVENT_ID = UUID.fromString("01956c00-0000-7000-8000-000000000ddd");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PointCardStampService stampService;

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

    private StampEventResponse sampleResponse() {
        return new StampEventResponse(
                EVENT_ID, CARD_ID, PROVIDER_ID,
                "カフェ A スタンプ", ORG_ID,
                1, USER_ID, "店員 田中",
                OffsetDateTime.parse("2026-05-14T10:00:00Z"),
                "10 杯目！");
    }

    // ──────────────────────────────────────────────
    // POST /stamps
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /{cardId}/stamps: 押印成功で 201 + JSON")
    void stamp_201() throws Exception {
        given(stampService.stamp(
                eq(ORG_ID), eq(CARD_ID), eq(USER_ID), any(StampRequest.class),
                any(), any(), any()))
                .willReturn(sampleResponse());

        StampRequest req = new StampRequest(1, "10 杯目！");
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/{cardId}/stamps",
                        ORG_ID, CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.data.cardId").value(CARD_ID.toString()))
                .andExpect(jsonPath("$.data.delta").value(1))
                .andExpect(jsonPath("$.data.pressedByUserDisplayName").value("店員 田中"))
                .andExpect(jsonPath("$.data.providerDisplayName").value("カフェ A スタンプ"));
    }

    @Test
    @DisplayName("POST /{cardId}/stamps: delta 必須欠落で 400")
    void stamp_validation_missingDelta_400() throws Exception {
        String body = "{\"memo\":\"only memo\"}";

        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/{cardId}/stamps",
                        ORG_ID, CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /{cardId}/stamps: delta 範囲外（101）で 400")
    void stamp_validation_outOfRange_400() throws Exception {
        StampRequest req = new StampRequest(101, null);

        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/{cardId}/stamps",
                        ORG_ID, CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /{cardId}/stamps: STAMP_INVALID_PROVIDER は 400 POINT_CARD_012")
    void stamp_invalidProvider_400() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.STAMP_INVALID_PROVIDER))
                .given(stampService).stamp(
                        eq(ORG_ID), eq(CARD_ID), eq(USER_ID), any(StampRequest.class),
                        any(), any(), any());

        StampRequest req = new StampRequest(1, null);
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/{cardId}/stamps",
                        ORG_ID, CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_012"));
    }

    @Test
    @DisplayName("POST /{cardId}/stamps: 認可違反 COMMON_002 は 403")
    void stamp_unauthorized_403() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(stampService).stamp(
                        eq(ORG_ID), eq(CARD_ID), eq(USER_ID), any(StampRequest.class),
                        any(), any(), any());

        StampRequest req = new StampRequest(1, null);
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/{cardId}/stamps",
                        ORG_ID, CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /{cardId}/stamps: STAMP_DELTA_ZERO は 400 POINT_CARD_014")
    void stamp_deltaZero_400() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.STAMP_DELTA_ZERO))
                .given(stampService).stamp(
                        eq(ORG_ID), eq(CARD_ID), eq(USER_ID), any(StampRequest.class),
                        any(), any(), any());

        // バリデーション層は通すために delta=1 を送り、Service 層で 014 を投げる擬似シナリオ
        StampRequest req = new StampRequest(1, null);
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/{cardId}/stamps",
                        ORG_ID, CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_014"));
    }

    // ──────────────────────────────────────────────
    // GET /stamps（組織配下一覧）
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /stamps: 200 + Page JSON 形状")
    void listOrg_200() throws Exception {
        Page<StampEventResponse> page =
                new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 20), 1);
        given(stampService.listOrgStamps(eq(ORG_ID), eq(USER_ID), isNull(), any()))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/point-cards/stamps", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.content[0].delta").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /stamps?providerId=...: クエリで絞り込みが Service に伝わる")
    void listOrg_withProviderFilter_200() throws Exception {
        Page<StampEventResponse> page =
                new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        given(stampService.listOrgStamps(eq(ORG_ID), eq(USER_ID), eq(PROVIDER_ID), any()))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/point-cards/stamps", ORG_ID)
                        .param("providerId", PROVIDER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ──────────────────────────────────────────────
    // GET /{cardId}/stamps（単一カード履歴）
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /{cardId}/stamps: 200 + リスト JSON")
    void listCard_200() throws Exception {
        given(stampService.listCardStamps(eq(ORG_ID), eq(CARD_ID), eq(USER_ID)))
                .willReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/organizations/{orgId}/point-cards/{cardId}/stamps",
                        ORG_ID, CARD_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.data[0].cardId").value(CARD_ID.toString()));
    }

    @Test
    @DisplayName("GET /{cardId}/stamps: CARD_NOT_FOUND は 404 POINT_CARD_006")
    void listCard_notFound_404() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.CARD_NOT_FOUND))
                .given(stampService).listCardStamps(eq(ORG_ID), eq(CARD_ID), eq(USER_ID));

        mockMvc.perform(get("/api/v1/organizations/{orgId}/point-cards/{cardId}/stamps",
                        ORG_ID, CARD_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_006"));
    }
}
