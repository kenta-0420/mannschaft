package com.mannschaft.app.timetable.personal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.timetable.personal.controller.PersonalTimetableShareTargetController;
import com.mannschaft.app.timetable.personal.dto.AddShareTargetRequest;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableShareTargetEntity;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableShareTargetService;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.15 Phase 5 PersonalTimetableShareTargetController WebMvc テスト。
 */
@WebMvcTest(PersonalTimetableShareTargetController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PersonalTimetableShareTargetController WebMvc テスト")
class PersonalTimetableShareTargetControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long TIMETABLE_ID = 1L;
    private static final Long FAMILY_TEAM_ID = 50L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private PersonalTimetableShareTargetService service;
    @MockitoBean private AuthTokenService authTokenService;
    @MockitoBean private UserLocaleCache userLocaleCache;
    @MockitoBean private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    private static PersonalTimetableShareTargetEntity buildEntity(Long id, Long teamId) {
        PersonalTimetableShareTargetEntity e = PersonalTimetableShareTargetEntity.builder()
                .personalTimetableId(TIMETABLE_ID)
                .teamId(teamId)
                .build();
        try {
            Field f = e.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(e, id);
            Field cf = e.getClass().getDeclaredField("createdAt");
            cf.setAccessible(true);
            cf.set(e, LocalDateTime.of(2026, 5, 3, 12, 0));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return e;
    }

    @Test
    @DisplayName("GET 共有先一覧: 200 + team_name 込み")
    void list_200() throws Exception {
        given(service.list(TIMETABLE_ID, USER_ID)).willReturn(List.of(
                buildEntity(11L, FAMILY_TEAM_ID)));
        given(service.resolveTeamName(FAMILY_TEAM_ID)).willReturn("我が家");

        mockMvc.perform(get("/api/v1/me/personal-timetables/{id}/share-targets", TIMETABLE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(11))
                .andExpect(jsonPath("$.data[0].team_id").value(FAMILY_TEAM_ID))
                .andExpect(jsonPath("$.data[0].team_name").value("我が家"));
    }

    @Test
    @DisplayName("POST 共有先追加: 201")
    void add_201() throws Exception {
        AddShareTargetRequest req = new AddShareTargetRequest(FAMILY_TEAM_ID);
        given(service.add(eq(TIMETABLE_ID), eq(USER_ID), eq(FAMILY_TEAM_ID)))
                .willReturn(buildEntity(11L, FAMILY_TEAM_ID));
        given(service.resolveTeamName(FAMILY_TEAM_ID)).willReturn("我が家");

        mockMvc.perform(post("/api/v1/me/personal-timetables/{id}/share-targets", TIMETABLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.team_id").value(FAMILY_TEAM_ID))
                .andExpect(jsonPath("$.data.team_name").value("我が家"));
    }

    @Test
    @DisplayName("POST 上限超過: 409")
    void add_上限_409() throws Exception {
        AddShareTargetRequest req = new AddShareTargetRequest(FAMILY_TEAM_ID);
        willThrow(new BusinessException(
                PersonalTimetableErrorCode.SHARE_TARGET_LIMIT_EXCEEDED))
                .given(service).add(eq(TIMETABLE_ID), eq(USER_ID), eq(FAMILY_TEAM_ID));

        mockMvc.perform(post("/api/v1/me/personal-timetables/{id}/share-targets", TIMETABLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST 家族テンプレ以外: 422")
    void add_非家族_422() throws Exception {
        AddShareTargetRequest req = new AddShareTargetRequest(FAMILY_TEAM_ID);
        willThrow(new BusinessException(
                PersonalTimetableErrorCode.SHARE_TARGET_NOT_FAMILY_TEAM))
                .given(service).add(eq(TIMETABLE_ID), eq(USER_ID), eq(FAMILY_TEAM_ID));

        mockMvc.perform(post("/api/v1/me/personal-timetables/{id}/share-targets", TIMETABLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("POST 非メンバー: 403")
    void add_非メンバー_403() throws Exception {
        AddShareTargetRequest req = new AddShareTargetRequest(FAMILY_TEAM_ID);
        willThrow(new BusinessException(
                PersonalTimetableErrorCode.SHARE_TARGET_NOT_TEAM_MEMBER))
                .given(service).add(eq(TIMETABLE_ID), eq(USER_ID), eq(FAMILY_TEAM_ID));

        mockMvc.perform(post("/api/v1/me/personal-timetables/{id}/share-targets", TIMETABLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE 共有先解除: 204")
    void remove_204() throws Exception {
        mockMvc.perform(delete(
                        "/api/v1/me/personal-timetables/{id}/share-targets/{teamId}",
                        TIMETABLE_ID, FAMILY_TEAM_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE 対象なし: 404")
    void remove_対象なし_404() throws Exception {
        willThrow(new BusinessException(PersonalTimetableErrorCode.SHARE_TARGET_NOT_FOUND))
                .given(service).remove(eq(TIMETABLE_ID), eq(USER_ID), eq(FAMILY_TEAM_ID));

        mockMvc.perform(delete(
                        "/api/v1/me/personal-timetables/{id}/share-targets/{teamId}",
                        TIMETABLE_ID, FAMILY_TEAM_ID))
                .andExpect(status().isNotFound());
    }
}
