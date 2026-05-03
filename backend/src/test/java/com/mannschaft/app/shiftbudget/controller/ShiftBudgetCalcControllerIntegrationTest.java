package com.mannschaft.app.shiftbudget.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.dto.PositionRequiredCount;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsRequest;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsRequest.RateMode;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsResponse;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetCalcService;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ShiftBudgetCalcController} の MockMvc 結合テスト。
 *
 * <p>F08.7 Phase 9-α — 設計書 §6.2.2 / §13 / §14.2 に準拠:</p>
 * <ul>
 *   <li>3 モード (MEMBER_AVG / POSITION_AVG / EXPLICIT) の HTTP 200 応答 + JSON 形状</li>
 *   <li>フィーチャーフラグ OFF → 503</li>
 *   <li>権限なし → 403</li>
 *   <li>バリデーションエラー → 400</li>
 *   <li>team_id 組織不在 → 404 (IDOR 対策)</li>
 * </ul>
 */
@WebMvcTest(ShiftBudgetCalcController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ShiftBudgetCalcController 結合テスト (F08.7 Phase 9-α)")
class ShiftBudgetCalcControllerIntegrationTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 12L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ShiftBudgetCalcService calcService;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ────────────────────────────────────────────────────────
    // 正常系
    // ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("正常系")
    class Normal {

        @Test
        @DisplayName("MEMBER_AVG_200_required_slots_62")
        void MEMBER_AVG_200_required_slots_62() throws Exception {
            RequiredSlotsResponse stub = new RequiredSlotsResponse(
                    new BigDecimal("300000"),
                    new BigDecimal("1200"),
                    new BigDecimal("4.0"),
                    62L,
                    "floor(300000 / (1200 * 4.0)) = 62",
                    List.of(),
                    null);
            given(calcService.calculateRequiredSlots(any())).willReturn(stub);

            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.MEMBER_AVG, null, null);

            mockMvc.perform(post("/api/v1/shift-budget/calc/required-slots")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.required_slots").value(62))
                    .andExpect(jsonPath("$.data.avg_hourly_rate").value(1200))
                    .andExpect(jsonPath("$.data.budget_amount").value(300000))
                    .andExpect(jsonPath("$.data.calculation").value(
                            "floor(300000 / (1200 * 4.0)) = 62"))
                    .andExpect(jsonPath("$.data.warnings").isArray());
        }

        @Test
        @DisplayName("EXPLICIT_200_required_slots_50")
        void EXPLICIT_200_required_slots_50() throws Exception {
            RequiredSlotsResponse stub = new RequiredSlotsResponse(
                    new BigDecimal("300000"),
                    new BigDecimal("1500"),
                    new BigDecimal("4.0"),
                    50L,
                    "floor(300000 / (1500 * 4.0)) = 50",
                    List.of(),
                    null);
            given(calcService.calculateRequiredSlots(any())).willReturn(stub);

            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    null, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.EXPLICIT, new BigDecimal("1500"), null);

            mockMvc.perform(post("/api/v1/shift-budget/calc/required-slots")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.required_slots").value(50));
        }

        @Test
        @DisplayName("POSITION_AVG_200_position_breakdown含む")
        void POSITION_AVG_200_position_breakdown含む() throws Exception {
            RequiredSlotsResponse stub = new RequiredSlotsResponse(
                    new BigDecimal("300000"),
                    new BigDecimal("1200"),
                    new BigDecimal("4.0"),
                    62L,
                    "floor(300000 / (1200 * 4.0)) = 62",
                    List.of(),
                    List.of(
                            new com.mannschaft.app.shiftbudget.dto.PositionBreakdown(
                                    1L, new BigDecimal("1200"), 1, 5)
                    ));
            given(calcService.calculateRequiredSlots(any())).willReturn(stub);

            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.POSITION_AVG, null,
                    List.of(new PositionRequiredCount(1L, 5)));

            mockMvc.perform(post("/api/v1/shift-budget/calc/required-slots")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.position_breakdown").isArray())
                    .andExpect(jsonPath("$.data.position_breakdown[0].position_id").value(1));
        }
    }

    // ────────────────────────────────────────────────────────
    // フィーチャーフラグ OFF
    // ────────────────────────────────────────────────────────

    @Test
    @DisplayName("フラグOFF_503_FEATURE_DISABLED")
    void フラグOFF_503_FEATURE_DISABLED() throws Exception {
        willThrow(new BusinessException(ShiftBudgetErrorCode.FEATURE_DISABLED))
                .given(calcService).calculateRequiredSlots(any());

        RequiredSlotsRequest req = new RequiredSlotsRequest(
                TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                RateMode.MEMBER_AVG, null, null);

        mockMvc.perform(post("/api/v1/shift-budget/calc/required-slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SHIFT_BUDGET_001"));
    }

    // ────────────────────────────────────────────────────────
    // 権限・テナント
    // ────────────────────────────────────────────────────────

    @Test
    @DisplayName("MANAGE_SHIFTS権限なし_403_COMMON_002")
    void MANAGE_SHIFTS権限なし_403_COMMON_002() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(calcService).calculateRequiredSlots(any());

        RequiredSlotsRequest req = new RequiredSlotsRequest(
                TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                RateMode.MEMBER_AVG, null, null);

        mockMvc.perform(post("/api/v1/shift-budget/calc/required-slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("team_id組織不在_404_TEAM_NOT_FOUND")
    void team_id組織不在_404_TEAM_NOT_FOUND() throws Exception {
        willThrow(new BusinessException(ShiftBudgetErrorCode.TEAM_NOT_FOUND))
                .given(calcService).calculateRequiredSlots(any());

        RequiredSlotsRequest req = new RequiredSlotsRequest(
                999L, new BigDecimal("300000"), new BigDecimal("4.0"),
                RateMode.MEMBER_AVG, null, null);

        mockMvc.perform(post("/api/v1/shift-budget/calc/required-slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SHIFT_BUDGET_008"));
    }

    // ────────────────────────────────────────────────────────
    // バリデーション (Bean Validation by @Valid)
    // ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("バリデーション")
    class Validation {

        @Test
        @DisplayName("budget_null_400_COMMON_001")
        void budget_null_400_COMMON_001() throws Exception {
            String body = "{\"team_id\":12,\"slot_hours\":4.0,\"rate_mode\":\"MEMBER_AVG\"}";

            mockMvc.perform(post("/api/v1/shift-budget/calc/required-slots")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }

        @Test
        @DisplayName("slot_hours範囲外_400_COMMON_001")
        void slot_hours範囲外_400_COMMON_001() throws Exception {
            String body = "{\"team_id\":12,\"budget_amount\":300000,\"slot_hours\":25.0,"
                    + "\"rate_mode\":\"MEMBER_AVG\"}";

            mockMvc.perform(post("/api/v1/shift-budget/calc/required-slots")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }

        @Test
        @DisplayName("rate_mode_null_400_COMMON_001")
        void rate_mode_null_400_COMMON_001() throws Exception {
            String body = "{\"team_id\":12,\"budget_amount\":300000,\"slot_hours\":4.0}";

            mockMvc.perform(post("/api/v1/shift-budget/calc/required-slots")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("EMPTY_POSITION_LIST_400")
        void EMPTY_POSITION_LIST_400() throws Exception {
            willThrow(new BusinessException(ShiftBudgetErrorCode.EMPTY_POSITION_LIST))
                    .given(calcService).calculateRequiredSlots(any());

            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.POSITION_AVG, null, List.of());

            mockMvc.perform(post("/api/v1/shift-budget/calc/required-slots")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_BUDGET_002"));
        }
    }
}
