package com.mannschaft.app.pointcard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.pointcard.dto.BalanceEventRequest;
import com.mannschaft.app.pointcard.dto.BalanceEventResponse;
import com.mannschaft.app.pointcard.enums.BalanceOperationType;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.service.PointCardBalanceService;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link OrgPointCardBalanceController} の MockMvc 結合テスト（F18 Phase 3 第二陣 2B）。
 *
 * <p>カバー観点:
 * <ul>
 *   <li>POST /balance-events CHARGE 正常系（201 + JSON 形状）</li>
 *   <li>POST /balance-events SPENT 正常系（delta 負値）</li>
 *   <li>POST /balance-events REFUND 正常系</li>
 *   <li>POST /balance-events バリデーション（operationType 必須）</li>
 *   <li>POST /balance-events 業務エラー（017 残高不足 = 400 / 020 = 409 / 認可違反 = 403）</li>
 * </ul>
 */
@WebMvcTest(OrgPointCardBalanceController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("OrgPointCardBalanceController 結合テスト")
class OrgPointCardBalanceControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long ORG_ID = 10L;
    private static final UUID CARD_ID = UUID.fromString("01956c00-0000-7000-8000-000000000bbb");
    private static final UUID PROVIDER_ID = UUID.fromString("01956c00-0000-7000-8000-000000000ccc");
    private static final UUID EVENT_ID = UUID.fromString("01956c00-0000-7000-8000-000000000ddd");
    private static final UUID ORIGINAL_EVENT_ID = UUID.fromString("01956c00-0000-7000-8000-000000000eee");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PointCardBalanceService balanceService;

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

    private BalanceEventResponse sampleResponse(BalanceOperationType type,
                                                BigDecimal delta, BigDecimal after,
                                                UUID refundOf) {
        return new BalanceEventResponse(
                EVENT_ID, CARD_ID, PROVIDER_ID,
                "カフェ A 残高", ORG_ID,
                type, delta, after, refundOf,
                USER_ID, "店員 太郎",
                OffsetDateTime.parse("2026-05-14T10:00:00Z"),
                "テストノート",
                OffsetDateTime.parse("2026-05-14T10:00:00Z"));
    }

    @Test
    @DisplayName("POST /balance-events: CHARGE 正常で 201 + JSON")
    void charge_201() throws Exception {
        given(balanceService.charge(eq(ORG_ID), eq(CARD_ID), eq(USER_ID),
                any(BalanceEventRequest.class), any(), any(), any()))
                .willReturn(sampleResponse(BalanceOperationType.CHARGE,
                        new BigDecimal("1000.00"), new BigDecimal("1500.00"), null));

        BalanceEventRequest req = new BalanceEventRequest(
                BalanceOperationType.CHARGE, new BigDecimal("1000.00"), "キャンペーン入金", null);
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/{cardId}/balance-events",
                        ORG_ID, CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.data.operationType").value("CHARGE"))
                .andExpect(jsonPath("$.data.delta").value(1000.00))
                .andExpect(jsonPath("$.data.balanceAfter").value(1500.00))
                .andExpect(jsonPath("$.data.providerDisplayName").value("カフェ A 残高"));
    }

    @Test
    @DisplayName("POST /balance-events: SPENT 正常で 201（delta は負値）")
    void spend_201() throws Exception {
        given(balanceService.spend(eq(ORG_ID), eq(CARD_ID), eq(USER_ID),
                any(BalanceEventRequest.class), any(), any(), any()))
                .willReturn(sampleResponse(BalanceOperationType.SPENT,
                        new BigDecimal("-500.00"), new BigDecimal("500.00"), null));

        BalanceEventRequest req = new BalanceEventRequest(
                BalanceOperationType.SPENT, new BigDecimal("500.00"), "支払い", null);
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/{cardId}/balance-events",
                        ORG_ID, CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.operationType").value("SPENT"))
                .andExpect(jsonPath("$.data.delta").value(-500.00));
    }

    @Test
    @DisplayName("POST /balance-events: REFUND 正常で 201")
    void refund_201() throws Exception {
        given(balanceService.refund(eq(ORG_ID), eq(CARD_ID), eq(USER_ID),
                any(BalanceEventRequest.class), any(), any(), any()))
                .willReturn(sampleResponse(BalanceOperationType.REFUND,
                        new BigDecimal("200.00"), new BigDecimal("200.00"), ORIGINAL_EVENT_ID));

        BalanceEventRequest req = new BalanceEventRequest(
                BalanceOperationType.REFUND, new BigDecimal("200.00"), "返品", ORIGINAL_EVENT_ID);
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/{cardId}/balance-events",
                        ORG_ID, CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.operationType").value("REFUND"))
                .andExpect(jsonPath("$.data.refundOfEventId").value(ORIGINAL_EVENT_ID.toString()));
    }

    @Test
    @DisplayName("POST /balance-events: operationType 欠落で 400")
    void validation_missingType_400() throws Exception {
        String body = "{\"amount\":\"100.00\"}";
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/{cardId}/balance-events",
                        ORG_ID, CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /balance-events: amount=0 で 400 (DecimalMin)")
    void validation_amountZero_400() throws Exception {
        String body = "{\"operationType\":\"CHARGE\",\"amount\":\"0\"}";
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/{cardId}/balance-events",
                        ORG_ID, CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /balance-events: SPENT で 017 残高不足 → 400 POINT_CARD_017")
    void spend_insufficient_400() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.INSUFFICIENT_BALANCE))
                .given(balanceService).spend(eq(ORG_ID), eq(CARD_ID), eq(USER_ID),
                        any(BalanceEventRequest.class), any(), any(), any());

        BalanceEventRequest req = new BalanceEventRequest(
                BalanceOperationType.SPENT, new BigDecimal("1000.00"), null, null);
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/{cardId}/balance-events",
                        ORG_ID, CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_017"));
    }

    @Test
    @DisplayName("POST /balance-events: REFUND で 020 累計超過 → 409 POINT_CARD_020")
    void refund_exceeds_409() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.REFUND_EXCEEDS_ORIGINAL))
                .given(balanceService).refund(eq(ORG_ID), eq(CARD_ID), eq(USER_ID),
                        any(BalanceEventRequest.class), any(), any(), any());

        BalanceEventRequest req = new BalanceEventRequest(
                BalanceOperationType.REFUND, new BigDecimal("100.00"), null, ORIGINAL_EVENT_ID);
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/{cardId}/balance-events",
                        ORG_ID, CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_020"));
    }

    @Test
    @DisplayName("POST /balance-events: 認可違反 COMMON_002 は 403")
    void unauthorized_403() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(balanceService).charge(eq(ORG_ID), eq(CARD_ID), eq(USER_ID),
                        any(BalanceEventRequest.class), any(), any(), any());

        BalanceEventRequest req = new BalanceEventRequest(
                BalanceOperationType.CHARGE, new BigDecimal("100.00"), null, null);
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/{cardId}/balance-events",
                        ORG_ID, CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    // ─────────────────────────────────────────────
    // F18 SELF_ISSUED_BALANCE 凍結（2026-05-17 マスター御裁可）
    // 設計書: docs/features/F18_point_card_wallet.md §1.4 / §16 / §17
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /balance-events: 機能凍結中（f18.balance.enabled=false）は 503 + POINT_CARD_024")
    void balanceServiceDisabled_503() throws Exception {
        // Service が BALANCE_SERVICE_DISABLED を投げた場合、GlobalExceptionHandler が
        // ERROR_CODE_STATUS_MAP に従って 503 SERVICE_UNAVAILABLE にマップすることを検証する。
        willThrow(new BusinessException(PointCardErrorCode.BALANCE_SERVICE_DISABLED))
                .given(balanceService).charge(eq(ORG_ID), eq(CARD_ID), eq(USER_ID),
                        any(BalanceEventRequest.class), any(), any(), any());

        BalanceEventRequest req = new BalanceEventRequest(
                BalanceOperationType.CHARGE, new BigDecimal("1000.00"), null, null);
        mockMvc.perform(post("/api/v1/organizations/{orgId}/point-cards/{cardId}/balance-events",
                        ORG_ID, CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_024"));
    }
}
