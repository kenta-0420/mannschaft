package com.mannschaft.app.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.dto.GuardianChildAnnouncementsResponse;
import com.mannschaft.app.auth.dto.GuardianChildMembershipsResponse;
import com.mannschaft.app.auth.dto.GuardianChildProxyActionsResponse;
import com.mannschaft.app.auth.guardianship.GuardianChildViewService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.schedule.dto.AttendanceStatsResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@link GuardianChildViewController} 契約テスト（F08.9 件2 保護者による子データ閲覧見守り）。
 *
 * <h3>受け入れ条件との対応</h3>
 * <ul>
 *   <li>4 面それぞれ 200 正常系 + 認可 403（AC-1/AC-2）</li>
 *   <li>AGE_LOCKED → 403 GUARDIANSHIP_SWITCH_AGE_LOCKED（AC-4）</li>
 *   <li>AC-7: GET パスへの POST は 405（書き込み経路を持たない）</li>
 *   <li>未認証 → 401 COMMON_000</li>
 * </ul>
 *
 * <p>{@code @WebMvcTest + @EnableMethodSecurity} は SecurityConfig 全ロードを要求し失敗するため、
 * {@code standaloneSetup} + {@code MockedStatic<SecurityUtils>} で Controller のみを構成する
 * （{@link GuardianshipSwitchControllerTest} と同流儀）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuardianChildViewController 契約テスト（F08.9 件2）")
class GuardianChildViewControllerTest {

    private static final Long GUARDIAN_USER_ID = 100L;
    private static final String BASE = "/api/v1/me/guardianship/children/11";

    @Mock
    private GuardianChildViewService guardianChildViewService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MessageSource ms = new StaticMessageSource();
        GuardianChildViewController controller = new GuardianChildViewController(guardianChildViewService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();

        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(GUARDIAN_USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    // ========================================
    // ① 予定
    // ========================================

    @Test
    @DisplayName("① schedules 正常系: 200 / viewer=自分が Service に渡る")
    void schedules_200() throws Exception {
        given(guardianChildViewService.getChildSchedules(eq(GUARDIAN_USER_ID), eq(11L), any(), any()))
                .willReturn(List.of());

        mockMvc.perform(get(BASE + "/schedules")
                        .param("from", "2026-07-01T00:00:00")
                        .param("to", "2026-07-31T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("① schedules リンクなし: 403 GUARDIANSHIP_LINK_NOT_FOUND（MEMBERSHIP_BILLING_005）")
    void schedules_linkNotFound_403() throws Exception {
        given(guardianChildViewService.getChildSchedules(eq(GUARDIAN_USER_ID), eq(11L), any(), any()))
                .willThrow(new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND));

        mockMvc.perform(get(BASE + "/schedules")
                        .param("from", "2026-07-01T00:00:00")
                        .param("to", "2026-07-31T23:59:59"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_005"));
    }

    // ========================================
    // ② 出欠
    // ========================================

    @Test
    @DisplayName("② attendance/stats 正常系: 200 / camelCase")
    void attendance_200() throws Exception {
        given(guardianChildViewService.getChildAttendanceStats(eq(GUARDIAN_USER_ID), eq(11L), any(), any()))
                .willReturn(new AttendanceStatsResponse(11L, 10, 8, 1, 1, 80.0));

        mockMvc.perform(get(BASE + "/attendance/stats")
                        .param("from", "2026-07-01T00:00:00")
                        .param("to", "2026-07-31T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(11))
                .andExpect(jsonPath("$.data.attendanceRate").value(80.0));
    }

    @Test
    @DisplayName("② attendance/stats 年齢封印: 403 GUARDIANSHIP_SWITCH_AGE_LOCKED（MEMBERSHIP_BILLING_004）")
    void attendance_ageLocked_403() throws Exception {
        given(guardianChildViewService.getChildAttendanceStats(eq(GUARDIAN_USER_ID), eq(11L), any(), any()))
                .willThrow(new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_SWITCH_AGE_LOCKED));

        mockMvc.perform(get(BASE + "/attendance/stats")
                        .param("from", "2026-07-01T00:00:00")
                        .param("to", "2026-07-31T23:59:59"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_004"));
    }

    // ========================================
    // ③ 所属
    // ========================================

    @Test
    @DisplayName("③ memberships 正常系: 200 / teams・organizations が camelCase で返る")
    void memberships_200() throws Exception {
        given(guardianChildViewService.getChildMemberships(eq(GUARDIAN_USER_ID), eq(11L)))
                .willReturn(new GuardianChildMembershipsResponse(
                        List.of(new GuardianChildMembershipsResponse.ScopeRef(200L, "サッカークラブ")),
                        List.of(new GuardianChildMembershipsResponse.ScopeRef(300L, "県協会"))));

        mockMvc.perform(get(BASE + "/memberships"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teams[0].scopeId").value(200))
                .andExpect(jsonPath("$.data.teams[0].name").value("サッカークラブ"))
                .andExpect(jsonPath("$.data.organizations[0].scopeId").value(300));
    }

    @Test
    @DisplayName("③ memberships リンクなし: 403 MEMBERSHIP_BILLING_005")
    void memberships_linkNotFound_403() throws Exception {
        given(guardianChildViewService.getChildMemberships(eq(GUARDIAN_USER_ID), eq(11L)))
                .willThrow(new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND));

        mockMvc.perform(get(BASE + "/memberships"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_005"));
    }

    // ========================================
    // ④ お知らせ
    // ========================================

    @Test
    @DisplayName("④ announcements 正常系: 200 / items・totalElements が返る")
    void announcements_200() throws Exception {
        given(guardianChildViewService.getChildAnnouncements(eq(GUARDIAN_USER_ID), eq(11L), eq(0), eq(20)))
                .willReturn(new GuardianChildAnnouncementsResponse(
                        List.of(new GuardianChildAnnouncementsResponse.AnnouncementItem(
                                2L, "ORGANIZATION", 300L, "県協会", "大会案内", "INFO",
                                LocalDateTime.parse("2026-07-03T10:00:00"),
                                LocalDateTime.parse("2026-07-03T10:00:00"))),
                        0, 20, 1L));

        mockMvc.perform(get(BASE + "/announcements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].threadId").value(2))
                .andExpect(jsonPath("$.data.items[0].scopeName").value("県協会"));
    }

    @Test
    @DisplayName("④ announcements 年齢封印: 403 MEMBERSHIP_BILLING_004")
    void announcements_ageLocked_403() throws Exception {
        given(guardianChildViewService.getChildAnnouncements(eq(GUARDIAN_USER_ID), eq(11L), any(Integer.class), any(Integer.class)))
                .willThrow(new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_SWITCH_AGE_LOCKED));

        mockMvc.perform(get(BASE + "/announcements"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_004"));
    }

    // ========================================
    // 代理履歴（件3）
    // ========================================

    @Test
    @DisplayName("proxy-actions 正常系: 200 / items が返る")
    void proxyActions_200() throws Exception {
        given(guardianChildViewService.getChildProxyActions(eq(GUARDIAN_USER_ID), eq(11L)))
                .willReturn(new GuardianChildProxyActionsResponse(
                        List.of(new GuardianChildProxyActionsResponse.ProxyActionItem(
                                77L, 100L, "SCHEDULE_ATTENDANCE", "SCHEDULE_ATTENDANCE", 999L,
                                "GUARDIANSHIP_SWITCH", LocalDateTime.parse("2026-07-04T09:00:00")))));

        mockMvc.perform(get(BASE + "/proxy-actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(77))
                .andExpect(jsonPath("$.data.items[0].proxyUserId").value(100))
                .andExpect(jsonPath("$.data.items[0].inputSource").value("GUARDIANSHIP_SWITCH"));
    }

    @Test
    @DisplayName("proxy-actions リンクなし: 403 MEMBERSHIP_BILLING_005")
    void proxyActions_linkNotFound_403() throws Exception {
        given(guardianChildViewService.getChildProxyActions(eq(GUARDIAN_USER_ID), eq(11L)))
                .willThrow(new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND));

        mockMvc.perform(get(BASE + "/proxy-actions"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_005"));
    }

    // ========================================
    // AC-7: read-only（書き込み経路を持たない）
    // ========================================

    @Test
    @DisplayName("AC-7: schedules への POST は 405（書き込み経路を持たない）")
    void schedules_post_405() throws Exception {
        mockMvc.perform(post(BASE + "/schedules")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("AC-7: proxy-actions への POST は 405（書き込み経路を持たない）")
    void proxyActions_post_405() throws Exception {
        mockMvc.perform(post(BASE + "/proxy-actions")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    // ========================================
    // 未認証
    // ========================================

    @Test
    @DisplayName("未認証: 401 COMMON_000")
    void unauthenticated_401() throws Exception {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId)
                .thenThrow(new BusinessException(CommonErrorCode.COMMON_000));

        mockMvc.perform(get(BASE + "/memberships"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("COMMON_000"));
    }
}
