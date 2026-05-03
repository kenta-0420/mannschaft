package com.mannschaft.app.event.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.event.dto.DismissalReminderTargetResponse;
import com.mannschaft.app.event.service.EventDismissalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link EventDismissalController} のコントローラーテスト。F03.12 §16 / Phase11。
 *
 * <p>本テストは Phase11 で追加した {@code GET /api/v1/events/my-organizing/dismissal-reminders}
 * を中心に検証する。既存の POST/GET dismissal/status は Service テスト側でカバー済み。</p>
 */
@WebMvcTest(EventDismissalController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("EventDismissalController テスト")
class EventDismissalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventDismissalService eventDismissalService;

    // JwtAuthenticationFilter の依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    // F11.3: UserLocaleFilter の依存解決用
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    // F14.1: ProxyInputContextFilter の依存解決用（@WebMvcTest コンテキストで必要）
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void tearDownSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================
    // GET /api/v1/events/my-organizing/dismissal-reminders
    // =========================================================

    @Nested
    @DisplayName("GET /api/v1/events/my-organizing/dismissal-reminders")
    class GetMyOrganizingReminders {

        @Test
        @DisplayName("正常系: 主催未解散イベントを 200 で返す（Service の戻り値を data フィールドに格納）")
        void 正常系_主催未解散イベントを返す() throws Exception {
            // given
            LocalDateTime endAt = LocalDateTime.of(2026, 4, 28, 18, 0);
            DismissalReminderTargetResponse target = DismissalReminderTargetResponse.builder()
                    .eventId(123L)
                    .eventName("○○イベント")
                    .teamId(5L)
                    .teamName("△△チーム")
                    .endAt(endAt)
                    .minutesPassed(45L)
                    .reminderCount(1)
                    .build();
            given(eventDismissalService.getMyDismissalReminderTargets(eq(USER_ID)))
                    .willReturn(List.of(target));

            // when & then
            mockMvc.perform(get("/api/v1/events/my-organizing/dismissal-reminders"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].eventId").value(123))
                    .andExpect(jsonPath("$.data[0].eventName").value("○○イベント"))
                    .andExpect(jsonPath("$.data[0].teamId").value(5))
                    .andExpect(jsonPath("$.data[0].teamName").value("△△チーム"))
                    .andExpect(jsonPath("$.data[0].minutesPassed").value(45))
                    .andExpect(jsonPath("$.data[0].reminderCount").value(1));

            verify(eventDismissalService).getMyDismissalReminderTargets(eq(USER_ID));
        }

        @Test
        @DisplayName("対象0件: 空配列を 200 で返す")
        void 対象0件() throws Exception {
            // given
            given(eventDismissalService.getMyDismissalReminderTargets(eq(USER_ID)))
                    .willReturn(List.of());

            // when & then
            mockMvc.perform(get("/api/v1/events/my-organizing/dismissal-reminders"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }
}
