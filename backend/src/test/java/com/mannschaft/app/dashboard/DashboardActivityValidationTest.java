package com.mannschaft.app.dashboard;

import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.dashboard.controller.DashboardController;
import com.mannschaft.app.dashboard.dto.ActivityFeedPageResponse;
import com.mannschaft.app.dashboard.service.ActivityFeedService;
import com.mannschaft.app.dashboard.service.ChatHubService;
import com.mannschaft.app.dashboard.service.DashboardService;
import com.mannschaft.app.dashboard.service.DashboardWidgetService;
import com.mannschaft.app.dashboard.service.ScopeActionRequiredFacade;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.18 第三隊 — {@code GET /dashboard/activity} の入口バリデーション（AC-20）。
 *
 * <p>{@code ScheduleFeedErrorCode} を «定義しただけ» にせず、実際に throw されて HTTP 400 と
 * エラーコードがクライアントに届くことを、{@link GlobalExceptionHandler} を噛ませた
 * standalone MockMvc で端から端まで確かめる（memory: feedback_errorcode_status_remap_pitfalls
 * — 写像の単体アサーションはステータスの証明にならない）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GET /dashboard/activity 入口バリデーション（F03.18 AC-20）")
class DashboardActivityValidationTest {

    @Mock private DashboardService dashboardService;
    @Mock private DashboardWidgetService widgetService;
    @Mock private ActivityFeedService activityFeedService;
    @Mock private ChatHubService chatHubService;
    @Mock private AccessControlService accessControlService;
    @Mock private NotificationRepository notificationRepository;
    @Mock private TimelinePostRepository timelinePostRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private BulletinThreadRepository bulletinThreadRepository;
    @Mock private BulletinReadStatusRepository bulletinReadStatusRepository;
    @Mock private ChatChannelMemberRepository chatChannelMemberRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;
    @Mock private ShiftAssignmentRepository shiftAssignmentRepository;
    @Mock private ReservationRepository reservationRepository;
    @Mock private TeamService teamService;
    @Mock private OrganizationService organizationService;
    @Mock private ScopeActionRequiredFacade scopeActionRequiredFacade;
    @Mock private com.mannschaft.app.admin.service.PlatformAnnouncementService platformAnnouncementService;

    @InjectMocks
    private DashboardController dashboardController;

    private MockMvc mockMvc;

    private static final Long USER_ID = 1L;
    private static final String PATH = "/api/v1/dashboard/activity";

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));

        given(userRoleRepository.findTeamIdsByUserId(anyLong())).willReturn(List.of(10L));
        given(userRoleRepository.findOrganizationIdsByUserId(anyLong())).willReturn(List.of(20L));
        given(activityFeedService.getActivityFeed(any(), any(), any(), any(), any()))
                .willReturn(ActivityFeedPageResponse.empty());

        mockMvc = MockMvcBuilders.standaloneSetup(dashboardController)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("AC-20: cursor=0 は SCHEDULE_FEED_001 で HTTP 400")
    void ac20_cursorZero_returns400() throws Exception {
        mockMvc.perform(get(PATH).param("cursor", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_FEED_001"));
    }

    @Test
    @DisplayName("AC-20: cursor が負値でも SCHEDULE_FEED_001 で HTTP 400")
    void ac20_cursorNegative_returns400() throws Exception {
        mockMvc.perform(get(PATH).param("cursor", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_FEED_001"));
    }

    @Test
    @DisplayName("AC-20: limit=0 は SCHEDULE_FEED_002 で HTTP 400")
    void ac20_limitZero_returns400() throws Exception {
        mockMvc.perform(get(PATH).param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_FEED_002"));
    }

    @Test
    @DisplayName("AC-20: limit が負値でも SCHEDULE_FEED_002 で HTTP 400")
    void ac20_limitNegative_returns400() throws Exception {
        mockMvc.perform(get(PATH).param("limit", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_FEED_002"));
    }

    @Test
    @DisplayName("陽性対照: 妥当な cursor / limit は 200 で items / nextCursor を返す")
    void validParams_returns200WithWrapper() throws Exception {
        mockMvc.perform(get(PATH).param("cursor", "100").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    @DisplayName("陽性対照: cursor 未指定・limit 既定でも 200")
    void noParams_returns200() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
    }
}
