package com.mannschaft.app.timetable.notes;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.timetable.notes.controller.TimetableSlotUserNoteFieldController;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteFieldEntity;
import com.mannschaft.app.timetable.notes.service.TimetableSlotUserNoteFieldService;
import com.mannschaft.app.timetable.personal.PersonalTimetableErrorCode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TimetableSlotUserNoteFieldController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("TimetableSlotUserNoteFieldController 結合テスト")
class TimetableSlotUserNoteFieldControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long FIELD_ID = 11L;

    @Autowired private MockMvc mockMvc;
    @MockitoBean private TimetableSlotUserNoteFieldService service;
    @MockitoBean private AuthTokenService authTokenService;
    @MockitoBean private UserLocaleCache userLocaleCache;
    @MockitoBean private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    private TimetableSlotUserNoteFieldEntity sample() {
        TimetableSlotUserNoteFieldEntity e = TimetableSlotUserNoteFieldEntity.builder()
                .userId(USER_ID).label("演習").placeholder("内容").sortOrder(0).maxLength(2000)
                .build();
        try {
            Field idField = e.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true); idField.set(e, FIELD_ID);
            Field createdAtField = e.getClass().getSuperclass().getDeclaredField("createdAt");
            createdAtField.setAccessible(true); createdAtField.set(e, LocalDateTime.now());
            Field updatedAtField = e.getClass().getSuperclass().getDeclaredField("updatedAt");
            updatedAtField.setAccessible(true); updatedAtField.set(e, LocalDateTime.now());
        } catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
        return e;
    }

    @Test
    @DisplayName("GET 正常系: 一覧 200")
    void list_200() throws Exception {
        given(service.listMine(USER_ID)).willReturn(List.of(sample()));
        mockMvc.perform(get("/api/v1/me/timetable-slot-note-fields"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].label").value("演習"));
    }

    @Test
    @DisplayName("POST 正常系: 作成 201")
    void create_201() throws Exception {
        given(service.create(eq(USER_ID), any())).willReturn(sample());
        String body = """
                {"label":"演習","placeholder":"内容","sortOrder":0,"maxLength":2000}
                """;
        mockMvc.perform(post("/api/v1/me/timetable-slot-note-fields")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.label").value("演習"));
    }

    @Test
    @DisplayName("POST 異常系: 上限到達で 409")
    void create_409() throws Exception {
        willThrow(new BusinessException(PersonalTimetableErrorCode.NOTE_FIELD_LIMIT_EXCEEDED))
                .given(service).create(eq(USER_ID), any());
        String body = """
                {"label":"X","maxLength":2000}
                """;
        mockMvc.perform(post("/api/v1/me/timetable-slot-note-fields")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PERSONAL_TIMETABLE_071"));
    }

    @Test
    @DisplayName("PATCH 正常系: 200")
    void patch_200() throws Exception {
        given(service.update(eq(FIELD_ID), eq(USER_ID), any())).willReturn(sample());
        String body = """
                {"label":"新ラベル"}
                """;
        mockMvc.perform(patch("/api/v1/me/timetable-slot-note-fields/{id}", FIELD_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE 正常系: 204")
    void delete_204() throws Exception {
        mockMvc.perform(delete("/api/v1/me/timetable-slot-note-fields/{id}", FIELD_ID))
                .andExpect(status().isNoContent());
    }
}
