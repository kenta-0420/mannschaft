package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.timetable.personal.controller.PersonalTimetableDashboardController;
import com.mannschaft.app.timetable.personal.dto.DashboardTimetableTodayResponse;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableDashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PersonalTimetableDashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PersonalTimetableDashboardController 結合テスト")
class PersonalTimetableDashboardControllerTest {

    private static final Long USER_ID = 100L;

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PersonalTimetableDashboardService service;
    @MockitoBean private AuthTokenService authTokenService;
    @MockitoBean private UserLocaleCache userLocaleCache;
    @MockitoBean private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @Test
    @DisplayName("GET 正常系: 200 マージ済みアイテム")
    void today_200() throws Exception {
        var item = new DashboardTimetableTodayResponse.TimetableTodayItem(
                "PERSONAL", null, null, 5L, null, 11L,
                "1限", 1, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(10, 30),
                "数学", null, null, null, null, null, null,
                false, null, false, null, false);
        given(service.getTimetableToday(eq(USER_ID), any(LocalDate.class)))
                .willReturn(new DashboardTimetableTodayResponse(
                        LocalDate.now(), "EVERY", List.of(item)));
        mockMvc.perform(get("/api/v1/me/dashboard/timetable-today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].source_kind").value("PERSONAL"))
                .andExpect(jsonPath("$.data.items[0].subject_name").value("数学"));
    }
}
