package com.mannschaft.app.schedule.controller;

/** {@link TeamScheduleController} 予約タスク取消 EP（DELETE /{teamId}/schedules/{scheduleId}/scheduled-tasks/{taskId}）軽量結合テスト。 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.CalendarSyncScopeType;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.schedule.service.ScheduleCrossRefService;
import com.mannschaft.app.schedule.service.ScheduleReminderService;
import com.mannschaft.app.schedule.service.ScheduleScheduledTaskService;
import com.mannschaft.app.schedule.service.ScheduleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("TeamScheduleController 予約タスク取消 EP 軽量結合テスト")
class TeamScheduleControllerScheduledTaskTest {

    @Mock
    private ScheduleService scheduleService;
    @Mock
    private ScheduleAttendanceService attendanceService;
    @Mock
    private ScheduleCrossRefService crossRefService;
    @Mock
    private ScheduleReminderService reminderService;
    @Mock
    private ScheduleScheduledTaskService scheduledTaskService;
    @Mock
    private NameResolverService nameResolverService;
    @Mock
    private TeamService teamService;

    private MockMvc mockMvc;

    private static final Long USER_ID = 100L;
    private static final Long TEAM_ID = 10L;
    private static final Long SCHEDULE_ID = 200L;
    private static final UUID TASK_ID = UUID.fromString("019607a0-0000-7000-8000-000000000099");

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        TeamScheduleController controller = new TeamScheduleController(
                scheduleService, attendanceService, crossRefService,
                reminderService, scheduledTaskService, nameResolverService, teamService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ScheduleEntity buildSchedule() {
        return ScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("テスト予定")
                .build();
    }

    @Nested
    @DisplayName("予約タスク取消 DELETE /api/v1/teams/{teamId}/schedules/{scheduleId}/scheduled-tasks/{taskId}")
    class 予約タスク取消 {

        @Test
        @DisplayName("取消成功_PENDINGタスク_204を返す")
        void 取消成功_PENDINGタスク_204を返す() throws Exception {
            given(scheduleService.getScheduleWithAccessCheck(eq(SCHEDULE_ID), eq(USER_ID)))
                    .willReturn(buildSchedule());
            doNothing().when(scheduledTaskService).cancelTask(
                    eq(TASK_ID), eq(CalendarSyncScopeType.TEAM), eq(TEAM_ID));

            mockMvc.perform(delete("/api/v1/teams/{teamId}/schedules/{scheduleId}/scheduled-tasks/{taskId}",
                            TEAM_ID, SCHEDULE_ID, TASK_ID))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("取消不可_PENDING以外_409を返す")
        void 取消不可_PENDING以外_409を返す() throws Exception {
            given(scheduleService.getScheduleWithAccessCheck(eq(SCHEDULE_ID), eq(USER_ID)))
                    .willReturn(buildSchedule());
            doThrow(new BusinessException(ScheduleErrorCode.SCHEDULED_TASK_NOT_CANCELLABLE))
                    .when(scheduledTaskService).cancelTask(any(UUID.class), any(), anyLong());

            mockMvc.perform(delete("/api/v1/teams/{teamId}/schedules/{scheduleId}/scheduled-tasks/{taskId}",
                            TEAM_ID, SCHEDULE_ID, TASK_ID))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_092"));
        }

        @Test
        @DisplayName("予約タスク未存在_404を返す")
        void 予約タスク未存在_404を返す() throws Exception {
            given(scheduleService.getScheduleWithAccessCheck(eq(SCHEDULE_ID), eq(USER_ID)))
                    .willReturn(buildSchedule());
            doThrow(new BusinessException(ScheduleErrorCode.SCHEDULED_TASK_NOT_FOUND))
                    .when(scheduledTaskService).cancelTask(any(UUID.class), any(), anyLong());

            mockMvc.perform(delete("/api/v1/teams/{teamId}/schedules/{scheduleId}/scheduled-tasks/{taskId}",
                            TEAM_ID, SCHEDULE_ID, TASK_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_091"));
        }
    }
}
