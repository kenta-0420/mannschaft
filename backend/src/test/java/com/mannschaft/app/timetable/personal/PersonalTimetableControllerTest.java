package com.mannschaft.app.timetable.personal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.timetable.personal.controller.PersonalTimetableController;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

/**
 * F03.15 Phase 1 PersonalTimetableController の MockMvc 結合テスト。
 *
 * <p>9 エンドポイント全件の正常系 + 主要異常系を網羅する。</p>
 */
@WebMvcTest(PersonalTimetableController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PersonalTimetableController 結合テスト")
class PersonalTimetableControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long TIMETABLE_ID = 1L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private PersonalTimetableService service;
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

    // ===== ヘルパ: id・createdAt・updatedAt をリフレクションで埋めた Entity を返す =====
    private static PersonalTimetableEntity buildEntity(Long id, PersonalTimetableStatus status) {
        PersonalTimetableEntity e = PersonalTimetableEntity.builder()
                .userId(USER_ID)
                .name("テスト")
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .effectiveUntil(LocalDate.of(2026, 9, 30))
                .status(status)
                .visibility(PersonalTimetableVisibility.PRIVATE)
                .weekPatternEnabled(false)
                .build();
        try {
            Field idField = e.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(e, id);
            Field createdAtField = e.getClass().getSuperclass().getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(e, LocalDateTime.of(2026, 5, 3, 12, 0));
            Field updatedAtField = e.getClass().getSuperclass().getDeclaredField("updatedAt");
            updatedAtField.setAccessible(true);
            updatedAtField.set(e, LocalDateTime.of(2026, 5, 3, 12, 0));
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    // ============================================================
    @Nested
    @DisplayName("GET /api/v1/me/personal-timetables")
    class List_ {

        @Test
        @DisplayName("正常系: 200 で配列を返す")
        void 一覧_200() throws Exception {
            given(service.listMine(USER_ID)).willReturn(List.of(
                    buildEntity(TIMETABLE_ID, PersonalTimetableStatus.DRAFT)));

            mockMvc.perform(get("/api/v1/me/personal-timetables"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(TIMETABLE_ID))
                    .andExpect(jsonPath("$.data[0].status").value("DRAFT"));
        }
    }

    // ============================================================
    @Nested
    @DisplayName("POST /api/v1/me/personal-timetables")
    class Create_ {

        @Test
        @DisplayName("正常系: 201 で作成される")
        void 作成_201() throws Exception {
            given(service.create(eq(USER_ID), any())).willReturn(
                    buildEntity(TIMETABLE_ID, PersonalTimetableStatus.DRAFT));

            String body = """
                    {
                      "name": "2026年度 前期",
                      "effective_from": "2026-04-01",
                      "effective_until": "2026-09-30"
                    }
                    """;

            mockMvc.perform(post("/api/v1/me/personal-timetables")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(TIMETABLE_ID))
                    .andExpect(jsonPath("$.data.status").value("DRAFT"));
        }

        @Test
        @DisplayName("異常系: name 未指定で 400")
        void 作成_name必須_400() throws Exception {
            String body = """
                    { "effective_from": "2026-04-01" }
                    """;
            mockMvc.perform(post("/api/v1/me/personal-timetables")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("異常系: 上限到達で 409 (PERSONAL_TIMETABLE_010)")
        void 作成_上限到達_409() throws Exception {
            willThrow(new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_LIMIT_EXCEEDED))
                    .given(service).create(eq(USER_ID), any());

            String body = """
                    { "name": "X", "effective_from": "2026-04-01" }
                    """;
            mockMvc.perform(post("/api/v1/me/personal-timetables")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("PERSONAL_TIMETABLE_010"));
        }
    }

    // ============================================================
    @Nested
    @DisplayName("GET /api/v1/me/personal-timetables/{id}")
    class Get_ {

        @Test
        @DisplayName("正常系: 200")
        void 詳細取得_200() throws Exception {
            given(service.getMine(TIMETABLE_ID, USER_ID))
                    .willReturn(buildEntity(TIMETABLE_ID, PersonalTimetableStatus.ACTIVE));

            mockMvc.perform(get("/api/v1/me/personal-timetables/{id}", TIMETABLE_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(TIMETABLE_ID))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("異常系: 他人のIDなど不在で 404")
        void 詳細_404() throws Exception {
            willThrow(new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND))
                    .given(service).getMine(99L, USER_ID);

            mockMvc.perform(get("/api/v1/me/personal-timetables/{id}", 99L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PERSONAL_TIMETABLE_001"));
        }
    }

    // ============================================================
    @Nested
    @DisplayName("PATCH /api/v1/me/personal-timetables/{id}")
    class Update_ {

        @Test
        @DisplayName("正常系: 200")
        void 更新_200() throws Exception {
            given(service.update(eq(TIMETABLE_ID), eq(USER_ID), any()))
                    .willReturn(buildEntity(TIMETABLE_ID, PersonalTimetableStatus.DRAFT));

            String body = """
                    { "name": "改名" }
                    """;
            mockMvc.perform(patch("/api/v1/me/personal-timetables/{id}", TIMETABLE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(TIMETABLE_ID));
        }
    }

    // ============================================================
    @Nested
    @DisplayName("DELETE /api/v1/me/personal-timetables/{id}")
    class Delete_ {

        @Test
        @DisplayName("正常系: 204")
        void 削除_204() throws Exception {
            mockMvc.perform(delete("/api/v1/me/personal-timetables/{id}", TIMETABLE_ID))
                    .andExpect(status().isNoContent());
        }
    }

    // ============================================================
    @Nested
    @DisplayName("POST /{id}/activate")
    class Activate_ {

        @Test
        @DisplayName("正常系: 200 ACTIVE")
        void 有効化_200() throws Exception {
            given(service.activate(TIMETABLE_ID, USER_ID))
                    .willReturn(buildEntity(TIMETABLE_ID, PersonalTimetableStatus.ACTIVE));

            mockMvc.perform(post("/api/v1/me/personal-timetables/{id}/activate", TIMETABLE_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("異常系: DRAFT でないと 409")
        void 有効化_409() throws Exception {
            willThrow(new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_DRAFT))
                    .given(service).activate(TIMETABLE_ID, USER_ID);

            mockMvc.perform(post("/api/v1/me/personal-timetables/{id}/activate", TIMETABLE_ID))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("PERSONAL_TIMETABLE_020"));
        }
    }

    // ============================================================
    @Nested
    @DisplayName("POST /{id}/archive")
    class Archive_ {

        @Test
        @DisplayName("正常系: 200 ARCHIVED")
        void アーカイブ_200() throws Exception {
            given(service.archive(TIMETABLE_ID, USER_ID))
                    .willReturn(buildEntity(TIMETABLE_ID, PersonalTimetableStatus.ARCHIVED));

            mockMvc.perform(post("/api/v1/me/personal-timetables/{id}/archive", TIMETABLE_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ARCHIVED"));
        }

        @Test
        @DisplayName("異常系: ACTIVE でないと 409")
        void アーカイブ_409() throws Exception {
            willThrow(new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_ACTIVE))
                    .given(service).archive(TIMETABLE_ID, USER_ID);

            mockMvc.perform(post("/api/v1/me/personal-timetables/{id}/archive", TIMETABLE_ID))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("PERSONAL_TIMETABLE_021"));
        }
    }

    // ============================================================
    @Nested
    @DisplayName("POST /{id}/revert-to-draft")
    class Revert_ {

        @Test
        @DisplayName("正常系: 200 DRAFT")
        void 下書き戻し_200() throws Exception {
            given(service.revertToDraft(TIMETABLE_ID, USER_ID))
                    .willReturn(buildEntity(TIMETABLE_ID, PersonalTimetableStatus.DRAFT));

            mockMvc.perform(post("/api/v1/me/personal-timetables/{id}/revert-to-draft", TIMETABLE_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("DRAFT"));
        }
    }

    // ============================================================
    @Nested
    @DisplayName("POST /{id}/duplicate")
    class Duplicate_ {

        @Test
        @DisplayName("正常系: 201 ボディなしで複製")
        void 複製_ボディなし_201() throws Exception {
            given(service.duplicate(eq(TIMETABLE_ID), eq(USER_ID), any()))
                    .willReturn(buildEntity(2L, PersonalTimetableStatus.DRAFT));

            mockMvc.perform(post("/api/v1/me/personal-timetables/{id}/duplicate", TIMETABLE_ID))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(2L))
                    .andExpect(jsonPath("$.data.status").value("DRAFT"));
        }

        @Test
        @DisplayName("正常系: 201 リクエストボディで効力日付を上書き")
        void 複製_ボディあり_201() throws Exception {
            given(service.duplicate(eq(TIMETABLE_ID), eq(USER_ID), any()))
                    .willReturn(buildEntity(2L, PersonalTimetableStatus.DRAFT));

            String body = """
                    {
                      "name": "後期",
                      "effective_from": "2026-10-01",
                      "effective_until": "2027-03-31"
                    }
                    """;
            mockMvc.perform(post("/api/v1/me/personal-timetables/{id}/duplicate", TIMETABLE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }
    }
}
