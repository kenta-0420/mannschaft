package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.timetable.personal.controller.PersonalTimetableSettingsController;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSettingsEntity;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableSettingsService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PersonalTimetableSettingsController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PersonalTimetableSettingsController 結合テスト")
class PersonalTimetableSettingsControllerTest {

    private static final Long USER_ID = 100L;

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PersonalTimetableSettingsService service;
    @MockitoBean private AuthTokenService authTokenService;
    @MockitoBean private UserLocaleCache userLocaleCache;
    @MockitoBean private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    private PersonalTimetableSettingsEntity sample() {
        return PersonalTimetableSettingsEntity.builder()
                .userId(USER_ID).build();
    }

    @Test
    @DisplayName("GET 正常系: デフォルト設定 200")
    void get_200() throws Exception {
        given(service.getOrCreate(USER_ID)).willReturn(sample());
        given(service.parseVisibleDefaultFields(any()))
                .willReturn(List.of("preparation", "review", "items_to_bring", "free_memo"));
        mockMvc.perform(get("/api/v1/me/personal-timetable-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auto_reflect_class_changes_to_calendar").value(true))
                .andExpect(jsonPath("$.data.notify_team_slot_note_updates").value(true))
                .andExpect(jsonPath("$.data.default_period_template").value("CUSTOM"))
                .andExpect(jsonPath("$.data.visible_default_fields[0]").value("preparation"));
    }

    @Test
    @DisplayName("PUT 正常系: 更新 200")
    void put_200() throws Exception {
        given(service.update(eq(USER_ID), any())).willReturn(sample());
        given(service.parseVisibleDefaultFields(any()))
                .willReturn(List.of("preparation"));
        String body = """
                {"auto_reflect_class_changes_to_calendar":false}
                """;
        mockMvc.perform(put("/api/v1/me/personal-timetable-settings")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
