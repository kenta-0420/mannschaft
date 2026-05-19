package com.mannschaft.app.shift.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.ManualRemindResponse;
import com.mannschaft.app.shift.dto.ShiftScheduleSummaryResponse;
import com.mannschaft.app.shift.service.ShiftPreferenceReminderBatchService;
import com.mannschaft.app.shift.service.ShiftScheduleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ShiftScheduleController} の MockMvc 結合テスト（F03.5 Phase 11 第二陣 2-α）。
 *
 * <p>summary / remind の 2 エンドポイントについて、正常 200 / 認可不正 403 /
 * リソース不在 404 / ステータス不整合 409 を検証する。</p>
 *
 * <p>Security フィルタ有効のまま {@link WithMockUser} で認証を注入し、
 * {@code @PreAuthorize("hasRole('ADMIN')")} の認可判定を本物の経路で検証する。
 * POST は CSRF が有効なので {@code with(csrf())} を付与。</p>
 */
@WebMvcTest(ShiftScheduleController.class)
@AutoConfigureMockMvc
@Import(ShiftSchedulePhase11ControllerTest.MethodSecurityTestConfig.class)
@DisplayName("ShiftScheduleController Phase 11 結合テスト")
class ShiftSchedulePhase11ControllerTest {

    /**
     * {@code @PreAuthorize} をテスト時に有効化するための設定。
     *
     * <p>本番 {@code SecurityConfig} はまだ {@code @EnableMethodSecurity} を有効化していない
     * （開発中は全エンドポイント素通し）ため、{@code @WebMvcTest} 単体では {@code hasRole('ADMIN')}
     * の認可判定が効かず 200 が返ってしまう。本クラスを {@code @Import} することで
     * 当該テストクラスのコンテキストでのみ Method Security を有効化し、Spring Security の
     * {@code AuthorizationManager} が 403 を返す経路を本物どおり検証する。</p>
     */
    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    private static final Long USER_ID = 100L;
    private static final Long SCHEDULE_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShiftScheduleService scheduleService;

    @MockitoBean
    private ShiftPreferenceReminderBatchService preferenceReminderBatchService;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    // ------------------------------------------------------------------
    // GET /shifts/schedules/{id}/summary
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET summary: ADMIN で 200 + サマリ JSON 返却")
    @WithMockUser(username = "100", roles = "ADMIN")
    void summary_admin_200() throws Exception {
        ShiftScheduleSummaryResponse summary = ShiftScheduleSummaryResponse.builder()
                .scheduleId(SCHEDULE_ID)
                .summaryByDate(List.of(ShiftScheduleSummaryResponse.DateSummary.builder()
                        .date(LocalDate.of(2026, 3, 1))
                        .byPosition(List.of())
                        .totalRequired(5)
                        .totalConfirmed(2)
                        .totalRequested(3)
                        .build()))
                .build();
        given(scheduleService.getScheduleSummary(SCHEDULE_ID)).willReturn(summary);

        mockMvc.perform(get("/api/v1/shifts/schedules/{id}/summary", SCHEDULE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleId").value(SCHEDULE_ID))
                .andExpect(jsonPath("$.data.summaryByDate[0].totalRequired").value(5))
                .andExpect(jsonPath("$.data.summaryByDate[0].totalConfirmed").value(2));
    }

    @Test
    @DisplayName("GET summary: 非 ADMIN（MEMBER）は 403")
    @WithMockUser(username = "100", roles = "MEMBER")
    void summary_member_403() throws Exception {
        mockMvc.perform(get("/api/v1/shifts/schedules/{id}/summary", SCHEDULE_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET summary: スケジュール非存在で 404 SHIFT_001")
    @WithMockUser(username = "100", roles = "ADMIN")
    void summary_notFound_404() throws Exception {
        willThrow(new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND))
                .given(scheduleService).getScheduleSummary(eq(SCHEDULE_ID));

        mockMvc.perform(get("/api/v1/shifts/schedules/{id}/summary", SCHEDULE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SHIFT_001"));
    }

    // ------------------------------------------------------------------
    // POST /shifts/schedules/{id}/remind
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST remind: ADMIN で 200 + 送信件数返却")
    @WithMockUser(username = "100", roles = "ADMIN")
    void remind_admin_200() throws Exception {
        ManualRemindResponse response = ManualRemindResponse.builder()
                .scheduleId(SCHEDULE_ID)
                .remindedCount(2)
                .remindedUserIds(List.of(11L, 12L))
                .build();
        given(preferenceReminderBatchService.triggerManualReminder(eq(SCHEDULE_ID), eq(USER_ID)))
                .willReturn(response);

        mockMvc.perform(post("/api/v1/shifts/schedules/{id}/remind", SCHEDULE_ID).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleId").value(SCHEDULE_ID))
                .andExpect(jsonPath("$.data.remindedCount").value(2))
                .andExpect(jsonPath("$.data.remindedUserIds[0]").value(11));
    }

    @Test
    @DisplayName("POST remind: 非 ADMIN（MEMBER）は 403")
    @WithMockUser(username = "100", roles = "MEMBER")
    void remind_member_403() throws Exception {
        mockMvc.perform(post("/api/v1/shifts/schedules/{id}/remind", SCHEDULE_ID).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST remind: COLLECTING 以外で 409 SHIFT_012")
    @WithMockUser(username = "100", roles = "ADMIN")
    void remind_wrongStatus_409() throws Exception {
        willThrow(new BusinessException(ShiftErrorCode.INVALID_SCHEDULE_STATUS))
                .given(preferenceReminderBatchService).triggerManualReminder(eq(SCHEDULE_ID), eq(USER_ID));

        mockMvc.perform(post("/api/v1/shifts/schedules/{id}/remind", SCHEDULE_ID).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHIFT_012"));
    }
}
