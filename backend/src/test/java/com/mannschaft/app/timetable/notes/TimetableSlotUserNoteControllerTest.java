package com.mannschaft.app.timetable.notes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.timetable.notes.controller.TimetableSlotUserNoteController;
import com.mannschaft.app.timetable.notes.dto.TimetableSlotUserNoteResponse;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteEntity;
import com.mannschaft.app.timetable.notes.service.TimetableSlotUserNoteService;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.15 Phase 3 個人メモ Controller の MockMvc 結合テスト。
 */
@WebMvcTest(TimetableSlotUserNoteController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("TimetableSlotUserNoteController 結合テスト")
class TimetableSlotUserNoteControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long NOTE_ID = 7L;
    private static final Long SLOT_ID = 11L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private TimetableSlotUserNoteService service;
    @MockitoBean private AuthTokenService authTokenService;
    @MockitoBean private UserLocaleCache userLocaleCache;
    // F14.1: ProxyInputContextFilter の依存解決用
    @MockitoBean private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    private TimetableSlotUserNoteEntity sampleEntity() {
        TimetableSlotUserNoteEntity e = TimetableSlotUserNoteEntity.builder()
                .userId(USER_ID).slotKind(TimetableSlotKind.PERSONAL).slotId(SLOT_ID)
                .preparation("予習").review("復習").itemsToBring("持参").freeMemo("自由")
                .build();
        try {
            Field idField = e.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true); idField.set(e, NOTE_ID);
            Field updatedAtField = e.getClass().getSuperclass().getDeclaredField("updatedAt");
            updatedAtField.setAccessible(true);
            updatedAtField.set(e, LocalDateTime.of(2026, 5, 3, 12, 0));
            Field createdAtField = e.getClass().getSuperclass().getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(e, LocalDateTime.of(2026, 5, 3, 12, 0));
        } catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
        return e;
    }

    @Test
    @DisplayName("GET 正常系: PERSONAL スロットのメモ一覧 200")
    void list_200() throws Exception {
        var entity = sampleEntity();
        given(service.findNotes(eq(USER_ID), eq(TimetableSlotKind.PERSONAL),
                eq(SLOT_ID), eq(null), eq(false))).willReturn(List.of(entity));
        given(service.toResponse(eq(entity), eq(USER_ID))).willReturn(
                TimetableSlotUserNoteResponse.from(entity, List.of()));

        mockMvc.perform(get("/api/v1/me/timetable-slot-notes")
                        .param("slot_kind", "PERSONAL")
                        .param("slot_id", SLOT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(NOTE_ID))
                .andExpect(jsonPath("$.data[0].preparation").value("予習"));
    }

    @Test
    @DisplayName("PUT 正常系: メモのアップサート 200")
    void put_200() throws Exception {
        var entity = sampleEntity();
        given(service.upsert(eq(USER_ID), any(), any())).willReturn(entity);
        given(service.toResponse(eq(entity), eq(USER_ID))).willReturn(
                TimetableSlotUserNoteResponse.from(entity, List.of()));

        String body = """
                {"slot_kind":"PERSONAL","slot_id":11,"preparation":"予習する","items_to_bring":"電卓"}
                """;
        mockMvc.perform(put("/api/v1/me/timetable-slot-notes")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(NOTE_ID));
    }

    @Test
    @DisplayName("PUT 異常系: free_memo 文字数超で 422")
    void put_422_文字数超() throws Exception {
        willThrow(new BusinessException(PersonalTimetableErrorCode.NOTE_FIELD_TOO_LONG))
                .given(service).upsert(eq(USER_ID), any(), any());
        String body = """
                {"slot_kind":"PERSONAL","slot_id":11,"free_memo":"x"}
                """;
        mockMvc.perform(put("/api/v1/me/timetable-slot-notes")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PERSONAL_TIMETABLE_063"));
    }

    @Test
    @DisplayName("PUT 異常系: <script> XSS で 422")
    void put_422_XSS() throws Exception {
        willThrow(new BusinessException(PersonalTimetableErrorCode.NOTE_UNSAFE_MARKDOWN))
                .given(service).upsert(eq(USER_ID), any(), any());
        String body = """
                {"slot_kind":"PERSONAL","slot_id":11,"preparation":"<script>"}
                """;
        mockMvc.perform(put("/api/v1/me/timetable-slot-notes")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PERSONAL_TIMETABLE_062"));
    }

    @Test
    @DisplayName("PUT 異常系: If-Unmodified-Since 不一致で 412")
    void put_412_楽観排他() throws Exception {
        willThrow(new BusinessException(PersonalTimetableErrorCode.NOTE_PRECONDITION_FAILED))
                .given(service).upsert(eq(USER_ID), any(), any());
        String body = """
                {"slot_kind":"PERSONAL","slot_id":11,"preparation":"x"}
                """;
        mockMvc.perform(put("/api/v1/me/timetable-slot-notes")
                        .header("If-Unmodified-Since", "Wed, 21 Oct 2015 07:28:00 GMT")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error.code").value("PERSONAL_TIMETABLE_061"));
    }

    @Test
    @DisplayName("DELETE 正常系: 204")
    void delete_204() throws Exception {
        mockMvc.perform(delete("/api/v1/me/timetable-slot-notes/{id}", NOTE_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /today 正常系: 200")
    void today_200() throws Exception {
        var entity = sampleEntity();
        given(service.findForDate(eq(USER_ID), any())).willReturn(List.of(entity));
        given(service.toResponse(eq(entity), eq(USER_ID))).willReturn(
                TimetableSlotUserNoteResponse.from(entity, List.of()));
        mockMvc.perform(get("/api/v1/me/timetable-slot-notes/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(NOTE_ID));
    }

    @Test
    @DisplayName("GET /upcoming 正常系: 200")
    void upcoming_200() throws Exception {
        var entity = sampleEntity();
        given(service.findUpcoming(eq(USER_ID), any(), any())).willReturn(List.of(entity));
        given(service.toResponse(eq(entity), eq(USER_ID))).willReturn(
                TimetableSlotUserNoteResponse.from(entity, List.of()));
        mockMvc.perform(get("/api/v1/me/timetable-slot-notes/upcoming")
                        .param("from", "2026-05-03").param("to", "2026-05-09"))
                .andExpect(status().isOk());
    }
}
