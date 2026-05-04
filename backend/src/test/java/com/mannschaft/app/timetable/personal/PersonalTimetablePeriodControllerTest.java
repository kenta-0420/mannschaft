package com.mannschaft.app.timetable.personal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.timetable.personal.controller.PersonalTimetablePeriodController;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import com.mannschaft.app.timetable.personal.service.PersonalTimetablePeriodService;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

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
 * F03.15 Phase 2 PersonalTimetablePeriodController の MockMvc 結合テスト。
 */
@WebMvcTest(PersonalTimetablePeriodController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PersonalTimetablePeriodController 結合テスト")
class PersonalTimetablePeriodControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long TIMETABLE_ID = 1L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private PersonalTimetablePeriodService service;
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

    private static PersonalTimetablePeriodEntity buildPeriod(Long id, int num, String label,
                                                              String start, String end, boolean br) {
        PersonalTimetablePeriodEntity e = PersonalTimetablePeriodEntity.builder()
                .personalTimetableId(TIMETABLE_ID)
                .periodNumber(num)
                .label(label)
                .startTime(LocalTime.parse(start))
                .endTime(LocalTime.parse(end))
                .isBreak(br)
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
    @DisplayName("GET 正常系: 200 で配列を返す")
    void list_200() throws Exception {
        given(service.list(TIMETABLE_ID, USER_ID)).willReturn(List.of(
                buildPeriod(11L, 1, "1限", "09:00", "10:30", false)));

        mockMvc.perform(get("/api/v1/me/personal-timetables/{id}/periods", TIMETABLE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(11))
                .andExpect(jsonPath("$.data[0].period_number").value(1))
                .andExpect(jsonPath("$.data[0].label").value("1限"));
    }

    @Test
    @DisplayName("PUT 正常系: 200 で置換結果を返す")
    void put_200() throws Exception {
        given(service.replaceAll(eq(TIMETABLE_ID), eq(USER_ID), anyList()))
                .willReturn(List.of(buildPeriod(21L, 1, "1限", "09:00", "10:30", false)));

        String body = """
                {
                  "periods": [
                    {"period_number": 1, "label": "1限", "start_time": "09:00", "end_time": "10:30", "is_break": false}
                  ]
                }
                """;
        mockMvc.perform(put("/api/v1/me/personal-timetables/{id}/periods", TIMETABLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(21));
    }

    @Test
    @DisplayName("PUT 異常系: DRAFT でないと 409 (PERSONAL_TIMETABLE_044)")
    void put_409_DRAFT以外() throws Exception {
        willThrow(new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_EDITABLE))
                .given(service).replaceAll(eq(TIMETABLE_ID), eq(USER_ID), anyList());

        String body = """
                {
                  "periods": [
                    {"period_number": 1, "label": "1限", "start_time": "09:00", "end_time": "10:30", "is_break": false}
                  ]
                }
                """;
        mockMvc.perform(put("/api/v1/me/personal-timetables/{id}/periods", TIMETABLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PERSONAL_TIMETABLE_044"));
    }

    @Test
    @DisplayName("PUT 異常系: period_number 範囲外 (16) は 400 (Bean Validation)")
    void put_400_period番号16() throws Exception {
        String body = """
                {
                  "periods": [
                    {"period_number": 16, "label": "限外", "start_time": "09:00", "end_time": "10:30", "is_break": false}
                  ]
                }
                """;
        mockMvc.perform(put("/api/v1/me/personal-timetables/{id}/periods", TIMETABLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT 異常系: 上限15超過 Service 例外で 409 (PERSONAL_TIMETABLE_040)")
    void put_409_上限超過() throws Exception {
        willThrow(new BusinessException(PersonalTimetableErrorCode.PERSONAL_PERIOD_LIMIT_EXCEEDED))
                .given(service).replaceAll(eq(TIMETABLE_ID), eq(USER_ID), anyList());

        String body = """
                {
                  "periods": [
                    {"period_number": 1, "label": "1", "start_time": "09:00", "end_time": "10:30", "is_break": false}
                  ]
                }
                """;
        mockMvc.perform(put("/api/v1/me/personal-timetables/{id}/periods", TIMETABLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PERSONAL_TIMETABLE_040"));
    }
}
