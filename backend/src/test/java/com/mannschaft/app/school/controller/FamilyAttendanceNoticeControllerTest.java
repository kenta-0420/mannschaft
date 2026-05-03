package com.mannschaft.app.school.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.school.dto.FamilyAttendanceNoticeResponse;
import com.mannschaft.app.school.dto.FamilyNoticeListResponse;
import com.mannschaft.app.school.entity.FamilyNoticeType;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.service.FamilyAttendanceNoticeService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.13: {@link FamilyAttendanceNoticeController} の MockMvc 結合テスト。
 *
 * <p>{@code @WebMvcTest} で Web レイヤーのみを起動し、Service 層は {@link MockitoBean} で差し替える。</p>
 */
@WebMvcTest(FamilyAttendanceNoticeController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("FamilyAttendanceNoticeController 結合テスト")
class FamilyAttendanceNoticeControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 100L;
    private static final Long NOTICE_ID = 200L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private FamilyAttendanceNoticeService noticeService;

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

    private FamilyAttendanceNoticeResponse buildNoticeResponse(String status) {
        return FamilyAttendanceNoticeResponse.builder()
                .id(NOTICE_ID)
                .teamId(TEAM_ID)
                .studentUserId(10L)
                .submitterUserId(USER_ID)
                .attendanceDate(LocalDate.of(2026, 5, 1))
                .noticeType(FamilyNoticeType.ABSENCE)
                .status(status)
                .appliedToRecord("APPLIED".equals(status))
                .attachedDownloadUrls(List.of())
                .build();
    }

    // ════════════════════════════════════════════════
    // POST /api/v1/me/attendance/notices
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/me/attendance/notices")
    class SubmitNotice {

        @Test
        @DisplayName("正常系: 連絡送信成功 → 201 Created + data")
        void 正常系_201() throws Exception {
            given(noticeService.submitNotice(eq(USER_ID), any()))
                    .willReturn(buildNoticeResponse("PENDING"));

            String body = """
                    {
                      "teamId": 100,
                      "studentUserId": 10,
                      "attendanceDate": "2026-05-01",
                      "noticeType": "ABSENCE"
                    }
                    """;

            mockMvc.perform(post("/api/v1/me/attendance/notices")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(NOTICE_ID))
                    .andExpect(jsonPath("$.data.status").value("PENDING"));
        }

        @Test
        @DisplayName("バリデーション失敗: studentUserId が null → 400")
        void バリデーション失敗_400() throws Exception {
            String body = """
                    {
                      "teamId": 100,
                      "attendanceDate": "2026-05-01",
                      "noticeType": "ABSENCE"
                    }
                    """;

            mockMvc.perform(post("/api/v1/me/attendance/notices")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ════════════════════════════════════════════════
    // GET /api/v1/teams/{teamId}/attendance/notices
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/teams/{teamId}/attendance/notices")
    class GetTeamNotices {

        @Test
        @DisplayName("正常系: 連絡一覧を返す → 200 + data")
        void 正常系_200() throws Exception {
            FamilyNoticeListResponse listResponse = FamilyNoticeListResponse.builder()
                    .teamId(TEAM_ID)
                    .attendanceDate(LocalDate.of(2026, 5, 1))
                    .records(List.of(buildNoticeResponse("PENDING")))
                    .totalCount(1)
                    .unacknowledgedCount(1)
                    .build();
            given(noticeService.getTeamNotices(eq(TEAM_ID), any())).willReturn(listResponse);

            mockMvc.perform(get("/api/v1/teams/{teamId}/attendance/notices", TEAM_ID)
                            .param("date", "2026-05-01"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalCount").value(1))
                    .andExpect(jsonPath("$.data.unacknowledgedCount").value(1))
                    .andExpect(jsonPath("$.data.records").isArray());
        }
    }

    // ════════════════════════════════════════════════
    // POST /api/v1/teams/{teamId}/attendance/notices/{id}/acknowledge
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/teams/{teamId}/attendance/notices/{id}/acknowledge")
    class AcknowledgeNotice {

        @Test
        @DisplayName("正常系: 確認済みレスポンスを返す → 200 + data")
        void 正常系_200() throws Exception {
            given(noticeService.acknowledgeNotice(eq(NOTICE_ID), eq(USER_ID)))
                    .willReturn(buildNoticeResponse("ACKNOWLEDGED"));

            mockMvc.perform(post("/api/v1/teams/{teamId}/attendance/notices/{noticeId}/acknowledge",
                            TEAM_ID, NOTICE_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"));
        }

        @Test
        @DisplayName("異常系: 連絡が見つからない → 404")
        void 異常系_404() throws Exception {
            willThrow(new BusinessException(SchoolErrorCode.FAMILY_NOTICE_NOT_FOUND))
                    .given(noticeService).acknowledgeNotice(eq(NOTICE_ID), eq(USER_ID));

            mockMvc.perform(post("/api/v1/teams/{teamId}/attendance/notices/{noticeId}/acknowledge",
                            TEAM_ID, NOTICE_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHOOL_FAMILY_NOTICE_NOT_FOUND"));
        }
    }

    // ════════════════════════════════════════════════
    // POST /api/v1/teams/{teamId}/attendance/notices/{id}/apply
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/teams/{teamId}/attendance/notices/{id}/apply")
    class ApplyToRecord {

        @Test
        @DisplayName("正常系: 反映済みレスポンスを返す → 200 + data")
        void 正常系_200() throws Exception {
            given(noticeService.applyToAttendanceRecord(eq(NOTICE_ID), eq(USER_ID)))
                    .willReturn(buildNoticeResponse("APPLIED"));

            mockMvc.perform(post("/api/v1/teams/{teamId}/attendance/notices/{noticeId}/apply",
                            TEAM_ID, NOTICE_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("APPLIED"))
                    .andExpect(jsonPath("$.data.appliedToRecord").value(true));
        }

        @Test
        @DisplayName("異常系: 既反映 → 409 Conflict")
        void 既反映_409() throws Exception {
            willThrow(new BusinessException(SchoolErrorCode.FAMILY_NOTICE_ALREADY_APPLIED))
                    .given(noticeService).applyToAttendanceRecord(eq(NOTICE_ID), eq(USER_ID));

            mockMvc.perform(post("/api/v1/teams/{teamId}/attendance/notices/{noticeId}/apply",
                            TEAM_ID, NOTICE_ID))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SCHOOL_FAMILY_NOTICE_ALREADY_APPLIED"));
        }
    }

    // ════════════════════════════════════════════════
    // GET /api/v1/me/attendance/notices
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/me/attendance/notices")
    class GetMyNotices {

        @Test
        @DisplayName("正常系: 送信履歴を返す → 200 + data 配列")
        void 正常系_200() throws Exception {
            given(noticeService.getMyNotices(eq(USER_ID), any(), any()))
                    .willReturn(List.of(buildNoticeResponse("ACKNOWLEDGED")));

            mockMvc.perform(get("/api/v1/me/attendance/notices")
                            .param("from", "2026-05-01")
                            .param("to", "2026-05-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].status").value("ACKNOWLEDGED"));
        }
    }
}
