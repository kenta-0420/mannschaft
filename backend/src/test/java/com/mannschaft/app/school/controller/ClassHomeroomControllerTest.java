package com.mannschaft.app.school.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.school.dto.ClassHomeroomCreateRequest;
import com.mannschaft.app.school.dto.ClassHomeroomResponse;
import com.mannschaft.app.school.dto.ClassHomeroomUpdateRequest;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.service.ClassHomeroomService;
import org.junit.jupiter.api.AfterEach;
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

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.13: {@link ClassHomeroomController} の MockMvc 結合テスト。
 *
 * <p>{@code @WebMvcTest} で Web レイヤーのみを起動し、Service 層は {@link MockitoBean} で差し替える。
 * 認証戦略: {@code @AutoConfigureMockMvc(addFilters = false)} で Spring Security フィルタを無効化し、
 * {@link SecurityContextHolder} に直接テスト用の認証情報をセットする。</p>
 */
@WebMvcTest(ClassHomeroomController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ClassHomeroomController 結合テスト")
class ClassHomeroomControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 100L;
    private static final Long HOMEROOM_ID = 200L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ClassHomeroomService classHomeroomService;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    // F14.1: ProxyInputContextFilter の依存解決用（@WebMvcTest コンテキストで必要）
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDownSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ════════════════════════════════════════════════
    // GET /api/v1/teams/{teamId}/homerooms
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/teams/{teamId}/homerooms")
    class ListHomerooms {

        @Test
        @DisplayName("正常系: Service が一覧を返すとき → 200 + data 配列")
        void 正常系_200() throws Exception {
            ClassHomeroomResponse response = ClassHomeroomResponse.builder()
                    .id(HOMEROOM_ID)
                    .teamId(TEAM_ID)
                    .homeroomTeacherUserId(10L)
                    .assistantTeacherUserIds(List.of())
                    .academicYear(2026)
                    .effectiveFrom(LocalDate.of(2026, 4, 1))
                    .build();
            given(classHomeroomService.listHomerooms(eq(TEAM_ID), eq(2026), eq(USER_ID)))
                    .willReturn(List.of(response));

            mockMvc.perform(get("/api/v1/teams/{teamId}/homerooms", TEAM_ID)
                            .param("academicYear", "2026"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].teamId").value(TEAM_ID))
                    .andExpect(jsonPath("$.data[0].homeroomTeacherUserId").value(10));
        }
    }

    // ════════════════════════════════════════════════
    // POST /api/v1/teams/{teamId}/homerooms
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/teams/{teamId}/homerooms")
    class CreateHomeroom {

        @Test
        @DisplayName("正常系: 作成成功 → 201 Created + data")
        void 正常系_201() throws Exception {
            ClassHomeroomResponse response = ClassHomeroomResponse.builder()
                    .id(HOMEROOM_ID)
                    .teamId(TEAM_ID)
                    .homeroomTeacherUserId(10L)
                    .assistantTeacherUserIds(List.of())
                    .academicYear(2026)
                    .effectiveFrom(LocalDate.of(2026, 4, 1))
                    .createdBy(USER_ID)
                    .build();
            given(classHomeroomService.createHomeroom(eq(TEAM_ID), any(ClassHomeroomCreateRequest.class), eq(USER_ID)))
                    .willReturn(response);

            String body = """
                    {
                      "homeroomTeacherUserId": 10,
                      "academicYear": 2026,
                      "effectiveFrom": "2026-04-01"
                    }
                    """;

            mockMvc.perform(post("/api/v1/teams/{teamId}/homerooms", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.teamId").value(TEAM_ID))
                    .andExpect(jsonPath("$.data.homeroomTeacherUserId").value(10));
        }

        @Test
        @DisplayName("バリデーション失敗: homeroomTeacherUserId が null → 400")
        void バリデーション失敗_400() throws Exception {
            String body = """
                    {
                      "academicYear": 2026,
                      "effectiveFrom": "2026-04-01"
                    }
                    """;

            mockMvc.perform(post("/api/v1/teams/{teamId}/homerooms", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("重複エラー: HOMEROOM_ALREADY_EXISTS → 409 Conflict")
        void 重複エラー_409() throws Exception {
            willThrow(new BusinessException(SchoolErrorCode.HOMEROOM_ALREADY_EXISTS))
                    .given(classHomeroomService).createHomeroom(eq(TEAM_ID), any(ClassHomeroomCreateRequest.class), eq(USER_ID));

            String body = """
                    {
                      "homeroomTeacherUserId": 10,
                      "academicYear": 2026,
                      "effectiveFrom": "2026-04-01"
                    }
                    """;

            mockMvc.perform(post("/api/v1/teams/{teamId}/homerooms", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SCHOOL_HOMEROOM_ALREADY_EXISTS"));
        }
    }

    // ════════════════════════════════════════════════
    // PATCH /api/v1/teams/{teamId}/homerooms/{id}
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("PATCH /api/v1/teams/{teamId}/homerooms/{id}")
    class UpdateHomeroom {

        @Test
        @DisplayName("正常系: 更新成功 → 200 OK + 更新後 data")
        void 正常系_200() throws Exception {
            ClassHomeroomResponse response = ClassHomeroomResponse.builder()
                    .id(HOMEROOM_ID)
                    .teamId(TEAM_ID)
                    .homeroomTeacherUserId(20L)
                    .assistantTeacherUserIds(List.of())
                    .academicYear(2026)
                    .effectiveFrom(LocalDate.of(2026, 4, 1))
                    .build();
            given(classHomeroomService.updateHomeroom(eq(TEAM_ID), eq(HOMEROOM_ID),
                    any(ClassHomeroomUpdateRequest.class), eq(USER_ID)))
                    .willReturn(response);

            String body = """
                    {
                      "homeroomTeacherUserId": 20
                    }
                    """;

            mockMvc.perform(patch("/api/v1/teams/{teamId}/homerooms/{id}", TEAM_ID, HOMEROOM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.homeroomTeacherUserId").value(20));
        }

        @Test
        @DisplayName("未発見: HOMEROOM_NOT_FOUND → 404 Not Found")
        void 未発見_404() throws Exception {
            willThrow(new BusinessException(SchoolErrorCode.HOMEROOM_NOT_FOUND))
                    .given(classHomeroomService).updateHomeroom(eq(TEAM_ID), eq(HOMEROOM_ID),
                            any(ClassHomeroomUpdateRequest.class), eq(USER_ID));

            String body = """
                    {
                      "homeroomTeacherUserId": 20
                    }
                    """;

            mockMvc.perform(patch("/api/v1/teams/{teamId}/homerooms/{id}", TEAM_ID, HOMEROOM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHOOL_HOMEROOM_NOT_FOUND"));
        }
    }
}