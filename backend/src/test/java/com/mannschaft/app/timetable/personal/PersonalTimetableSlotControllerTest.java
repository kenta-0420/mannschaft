package com.mannschaft.app.timetable.personal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.personal.controller.PersonalTimetableSlotController;
import com.mannschaft.app.timetable.personal.dto.PersonalWeeklyViewResponse;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableSlotService;
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

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.15 Phase 2 PersonalTimetableSlotController の MockMvc 結合テスト。
 */
@WebMvcTest(PersonalTimetableSlotController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PersonalTimetableSlotController 結合テスト")
class PersonalTimetableSlotControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long TIMETABLE_ID = 1L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private PersonalTimetableSlotService service;
    @MockitoBean private AuthTokenService authTokenService;
    @MockitoBean private UserLocaleCache userLocaleCache;
    // F14.1: ProxyInputContextFilter の依存解決用（@WebMvcTest コンテキストで必要）
    @MockitoBean private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUpAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    private static PersonalTimetableSlotEntity buildSlot(Long id, String dow, int period, String subject) {
        PersonalTimetableSlotEntity e = PersonalTimetableSlotEntity.builder()
                .personalTimetableId(TIMETABLE_ID)
                .dayOfWeek(dow)
                .periodNumber(period)
                .weekPattern(WeekPattern.EVERY)
                .subjectName(subject)
                .autoSyncChanges(true)
                .build();
        try {
            Field idField = e.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(e, id);
            Field createdAtField = e.getClass().getSuperclass().getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(e, LocalDateTime.now());
            Field updatedAtField = e.getClass().getSuperclass().getDeclaredField("updatedAt");
            updatedAtField.setAccessible(true);
            updatedAtField.set(e, LocalDateTime.now());
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    @Test
    @DisplayName("GET /slots 正常系: 200")
    void list_200() throws Exception {
        given(service.list(eq(TIMETABLE_ID), eq(USER_ID), eq(null)))
                .willReturn(List.of(buildSlot(11L, "MON", 1, "国語")));

        mockMvc.perform(get("/api/v1/me/personal-timetables/{id}/slots", TIMETABLE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].subject_name").value("国語"))
                .andExpect(jsonPath("$.data[0].day_of_week").value("MON"));
    }

    @Test
    @DisplayName("GET /slots?day=MON 正常系: 曜日フィルタが Service に渡る")
    void list_曜日フィルタ() throws Exception {
        given(service.list(eq(TIMETABLE_ID), eq(USER_ID), eq("MON")))
                .willReturn(List.of(buildSlot(11L, "MON", 1, "月のみ")));
        mockMvc.perform(get("/api/v1/me/personal-timetables/{id}/slots", TIMETABLE_ID)
                        .param("day", "MON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].subject_name").value("月のみ"));
    }

    @Test
    @DisplayName("PUT /slots 正常系: 200")
    void put_200() throws Exception {
        given(service.replaceAll(eq(TIMETABLE_ID), eq(USER_ID), eq(null), anyList()))
                .willReturn(List.of(buildSlot(21L, "MON", 1, "国語")));

        String body = """
                {
                  "slots": [
                    {"day_of_week": "MON", "period_number": 1, "subject_name": "国語"}
                  ]
                }
                """;
        mockMvc.perform(put("/api/v1/me/personal-timetables/{id}/slots", TIMETABLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(21));
    }

    @Test
    @DisplayName("PUT /slots 異常系: ACTIVE で 409 (PERSONAL_TIMETABLE_044)")
    void put_409_ACTIVE() throws Exception {
        willThrow(new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_EDITABLE))
                .given(service).replaceAll(eq(TIMETABLE_ID), eq(USER_ID), eq(null), anyList());

        String body = """
                {
                  "slots": [
                    {"day_of_week": "MON", "period_number": 1, "subject_name": "X"}
                  ]
                }
                """;
        mockMvc.perform(put("/api/v1/me/personal-timetables/{id}/slots", TIMETABLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PERSONAL_TIMETABLE_044"));
    }

    @Test
    @DisplayName("PUT /slots 異常系: linked_team_id 指定で 400 (Phase 2 では未対応)")
    void put_400_リンク指定() throws Exception {
        willThrow(new BusinessException(PersonalTimetableErrorCode.PERSONAL_SLOT_LINK_NOT_SUPPORTED_YET))
                .given(service).replaceAll(eq(TIMETABLE_ID), eq(USER_ID), eq(null), anyList());

        String body = """
                {
                  "slots": [
                    {"day_of_week": "MON", "period_number": 1, "subject_name": "X", "linked_team_id": 42}
                  ]
                }
                """;
        mockMvc.perform(put("/api/v1/me/personal-timetables/{id}/slots", TIMETABLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PERSONAL_TIMETABLE_056"));
    }

    @Test
    @DisplayName("PUT /slots 異常系: day_of_week が不正値で 400 (Bean Validation)")
    void put_400_曜日不正() throws Exception {
        String body = """
                {
                  "slots": [
                    {"day_of_week": "ABC", "period_number": 1, "subject_name": "X"}
                  ]
                }
                """;
        mockMvc.perform(put("/api/v1/me/personal-timetables/{id}/slots", TIMETABLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /slots/today 正常系: 200")
    void today_200() throws Exception {
        given(service.listToday(TIMETABLE_ID, USER_ID))
                .willReturn(List.of(buildSlot(31L, "MON", 1, "今日")));
        mockMvc.perform(get("/api/v1/me/personal-timetables/{id}/slots/today", TIMETABLE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].subject_name").value("今日"));
    }

    @Test
    @DisplayName("GET /weekly 正常系: 200 で週情報を返す")
    void weekly_200() throws Exception {
        Map<String, PersonalWeeklyViewResponse.WeeklyDayInfo> days = new LinkedHashMap<>();
        for (String dow : List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")) {
            days.put(dow, new PersonalWeeklyViewResponse.WeeklyDayInfo(
                    LocalDate.of(2026, 4, 6), List.of()));
        }
        PersonalWeeklyViewResponse view = new PersonalWeeklyViewResponse(
                TIMETABLE_ID, "テスト時間割",
                LocalDate.of(2026, 4, 6), LocalDate.of(2026, 4, 12),
                false, "EVERY", List.of(), days);
        given(service.getWeeklyView(eq(TIMETABLE_ID), eq(USER_ID), any()))
                .willReturn(view);

        mockMvc.perform(get("/api/v1/me/personal-timetables/{id}/weekly", TIMETABLE_ID)
                        .param("week_of", "2026-04-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.personal_timetable_id").value(TIMETABLE_ID))
                .andExpect(jsonPath("$.data.week_start").value("2026-04-06"))
                .andExpect(jsonPath("$.data.current_week_pattern").value("EVERY"))
                .andExpect(jsonPath("$.data.days.MON").exists());
    }
}
