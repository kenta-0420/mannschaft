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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link ShiftScheduleController} の MockMvc 結合テスト（F03.5 Phase 11 第二陣 2-α）。
 *
 * <p>summary / remind の 2 エンドポイントについて、正常 200 / リソース不在 404 /
 * ステータス不整合 409 を検証する。</p>
 *
 * <p><b>認可テストの実装方針（根治治療版）</b>: 本番 {@code SecurityConfig} はまだ
 * {@code @EnableMethodSecurity} を有効化していない（開発中は全エンドポイント素通し）。
 * {@code @WebMvcTest} 単体で {@code @EnableMethodSecurity} を {@code @Import} する
 * アプローチは Spring Security 6 / Spring Boot 3 環境では {@code AuthorizationManager} 等の
 * 依存 Bean が slice に登録されず、Controller のハンドラマッピングが破綻して 404 になる
 * 副作用が確認された（PR #829 CI 失敗の根本原因）。したがって本テストでは
 * {@code AdminActionMemoControllerTest} の先例にならい、認可の検証は
 * <b>{@code @PreAuthorize} アノテーションの存在を Reflection で確認</b>する方式に切り替える。
 * 将来 {@code @EnableMethodSecurity} が本番有効化された時点で Spring Security が
 * 自動的に 403 を返すようになる。</p>
 */
@WebMvcTest(ShiftScheduleController.class)
@AutoConfigureMockMvc
@DisplayName("ShiftScheduleController Phase 11 結合テスト")
class ShiftSchedulePhase11ControllerTest {

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

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

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
        given(scheduleService.getScheduleSummary(eq(SCHEDULE_ID), eq(USER_ID))).willReturn(summary);

        mockMvc.perform(get("/api/v1/shifts/schedules/{id}/summary", SCHEDULE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleId").value(SCHEDULE_ID))
                .andExpect(jsonPath("$.data.summaryByDate[0].totalRequired").value(5))
                .andExpect(jsonPath("$.data.summaryByDate[0].totalConfirmed").value(2));
    }

    @Test
    @DisplayName("GET summary: @PreAuthorize('isAuthenticated()') が付与されている（真の per-scope 認可は Service 層）")
    void summary_isAuthorizedByAnnotation() throws NoSuchMethodException {
        Method method = ShiftScheduleController.class
                .getMethod("getScheduleSummary", Long.class);

        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);

        assertThat(annotation)
                .as("getScheduleSummary に @PreAuthorize が付与されていること")
                .isNotNull();
        // 認可根治 Phase 3-b（2026-05-30）: scope はスケジュールエンティティ由来で SpEL からパス変数参照
        // できないため、宣言は isAuthenticated() とし、真の per-scope 認可は
        // ShiftScheduleService.getScheduleSummary 内の checkScheduleAdminAccess で強制する。
        // 旧 hasRole('ADMIN') は method-security 点火時に JWT へ ROLE_ADMIN が乗らず一斉403になるため是正済。
        assertThat(annotation.value())
                .as("@PreAuthorize は isAuthenticated() を要求すること（点火時の一斉403を回避）")
                .isEqualTo("isAuthenticated()");
    }

    @Test
    @DisplayName("GET summary: スケジュール非存在で 404 SHIFT_001")
    @WithMockUser(username = "100", roles = "ADMIN")
    void summary_notFound_404() throws Exception {
        willThrow(new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND))
                .given(scheduleService).getScheduleSummary(eq(SCHEDULE_ID), eq(USER_ID));

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
    @DisplayName("POST remind: @PreAuthorize('isAuthenticated()') が付与されている（真の per-scope 認可は Service 層）")
    void remind_isAuthorizedByAnnotation() throws NoSuchMethodException {
        Method method = ShiftScheduleController.class
                .getMethod("remindUnsubmitted", Long.class);

        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);

        assertThat(annotation)
                .as("remindUnsubmitted に @PreAuthorize が付与されていること")
                .isNotNull();
        // 認可根治 Phase 3-b（2026-05-30）: scope はスケジュールエンティティ由来で SpEL からパス変数参照
        // できないため、宣言は isAuthenticated() とし、真の per-scope 認可は
        // ShiftPreferenceReminderBatchService.triggerManualReminder 内で AccessControlService により強制する。
        assertThat(annotation.value())
                .as("@PreAuthorize は isAuthenticated() を要求すること（点火時の一斉403を回避）")
                .isEqualTo("isAuthenticated()");
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
