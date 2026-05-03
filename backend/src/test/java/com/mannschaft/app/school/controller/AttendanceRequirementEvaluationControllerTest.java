package com.mannschaft.app.school.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.school.dto.AtRiskStudentResponse;
import com.mannschaft.app.school.dto.EvaluationResponse;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity.EvaluationStatus;
import com.mannschaft.app.school.service.AttendanceRequirementEvaluationService;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.13 Phase 12: {@link AttendanceRequirementEvaluationController} の MockMvc 結合テスト。
 */
@WebMvcTest(AttendanceRequirementEvaluationController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AttendanceRequirementEvaluationController 結合テスト")
class AttendanceRequirementEvaluationControllerTest {

    private static final Long USER_ID = 999L;
    private static final Long TEAM_ID = 100L;
    private static final Long STUDENT_ID = 200L;
    private static final Long RULE_ID = 1L;
    private static final Long EVALUATION_ID = 50L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AttendanceRequirementEvaluationService evaluationService;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    /** F14.1: ProxyInputContextFilter の依存解決用（@WebMvcTest コンテキストで JPA ロード防止） */
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUpSecurityContext() {
        // SecurityUtils.getCurrentUserId() が userId を取得できるようにセット
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDownSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ════════════════════════════════════════════════
    // GET /api/v1/students/{studentId}/attendance/requirements/evaluations
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/students/{studentId}/attendance/requirements/evaluations")
    class GetStudentEvaluations {

        @Test
        @DisplayName("正常系: 評価一覧を返す → 200 + data = []")
        void 正常系_評価一覧を返す() throws Exception {
            given(evaluationService.getStudentEvaluations(STUDENT_ID))
                    .willReturn(List.of());

            mockMvc.perform(get("/api/v1/students/{studentId}/attendance/requirements/evaluations",
                            STUDENT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("正常系: 評価データがある場合は一覧を返す → 200 + data に評価情報")
        void 正常系_評価データあり() throws Exception {
            EvaluationResponse response = new EvaluationResponse(
                    EVALUATION_ID, RULE_ID, STUDENT_ID, 10L,
                    EvaluationStatus.OK, new BigDecimal("90.00"), 10,
                    LocalDateTime.of(2026, 5, 1, 10, 0),
                    null, null, null);

            given(evaluationService.getStudentEvaluations(STUDENT_ID))
                    .willReturn(List.of(response));

            mockMvc.perform(get("/api/v1/students/{studentId}/attendance/requirements/evaluations",
                            STUDENT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].id").value(EVALUATION_ID))
                    .andExpect(jsonPath("$.data[0].status").value("OK"));
        }
    }

    // ════════════════════════════════════════════════
    // GET /api/v1/teams/{teamId}/attendance/requirements/at-risk
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/teams/{teamId}/attendance/requirements/at-risk")
    class GetAtRiskStudents {

        @Test
        @DisplayName("正常系: リスク生徒一覧を返す → 200 + data")
        void 正常系_リスク生徒一覧を返す() throws Exception {
            AtRiskStudentResponse response = new AtRiskStudentResponse(
                    STUDENT_ID, EvaluationStatus.RISK, RULE_ID,
                    new BigDecimal("75.00"), 5,
                    LocalDateTime.of(2026, 5, 1, 10, 0));

            given(evaluationService.getAtRiskStudents(eq(TEAM_ID), any()))
                    .willReturn(List.of(response));

            mockMvc.perform(get("/api/v1/teams/{teamId}/attendance/requirements/at-risk", TEAM_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].studentUserId").value(STUDENT_ID))
                    .andExpect(jsonPath("$.data[0].status").value("RISK"));
        }

        @Test
        @DisplayName("正常系: ステータスフィルターなし → デフォルトで RISK,VIOLATION を返す")
        void 正常系_フィルターなし() throws Exception {
            given(evaluationService.getAtRiskStudents(eq(TEAM_ID), any()))
                    .willReturn(List.of());

            mockMvc.perform(get("/api/v1/teams/{teamId}/attendance/requirements/at-risk", TEAM_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    // ════════════════════════════════════════════════
    // POST /api/v1/students/{studentId}/attendance/requirements/{ruleId}/evaluate
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/students/{studentId}/attendance/requirements/{ruleId}/evaluate")
    class Evaluate {

        @Test
        @DisplayName("正常系: 評価を実行して 201 を返す")
        void 正常系_評価実行で201を返す() throws Exception {
            EvaluationResponse response = new EvaluationResponse(
                    EVALUATION_ID, RULE_ID, STUDENT_ID, 10L,
                    EvaluationStatus.OK, new BigDecimal("90.00"), 10,
                    LocalDateTime.of(2026, 5, 1, 10, 0),
                    null, null, null);

            given(evaluationService.evaluate(STUDENT_ID, RULE_ID)).willReturn(response);

            mockMvc.perform(post("/api/v1/students/{studentId}/attendance/requirements/{ruleId}/evaluate",
                            STUDENT_ID, RULE_ID))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(EVALUATION_ID))
                    .andExpect(jsonPath("$.data.status").value("OK"));
        }
    }

    // ════════════════════════════════════════════════
    // POST /api/v1/attendance/requirements/evaluations/{evaluationId}/resolve
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/attendance/requirements/evaluations/{evaluationId}/resolve")
    class ResolveViolation {

        @Test
        @DisplayName("正常系: 違反を解消して 200 + 解消後の評価を返す")
        void 正常系_違反解消で200を返す() throws Exception {
            EvaluationResponse response = new EvaluationResponse(
                    EVALUATION_ID, RULE_ID, STUDENT_ID, 10L,
                    EvaluationStatus.VIOLATION, new BigDecimal("75.00"), 0,
                    LocalDateTime.of(2026, 5, 1, 10, 0),
                    LocalDateTime.of(2026, 5, 4, 14, 0),
                    "保護者と面談し指導完了",
                    USER_ID);

            given(evaluationService.resolveViolation(eq(EVALUATION_ID), eq(USER_ID), any()))
                    .willReturn(response);

            String requestBody = objectMapper.writeValueAsString(
                    Map.of("resolutionNote", "保護者と面談し指導完了"));

            mockMvc.perform(post("/api/v1/attendance/requirements/evaluations/{evaluationId}/resolve",
                            EVALUATION_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(EVALUATION_ID))
                    .andExpect(jsonPath("$.data.resolverUserId").value(USER_ID))
                    .andExpect(jsonPath("$.data.resolutionNote").value("保護者と面談し指導完了"));
        }

        @Test
        @DisplayName("異常系: resolutionNote が空 → 400 バリデーションエラー")
        void 異常系_resolutionNoteが空なら400() throws Exception {
            String requestBody = objectMapper.writeValueAsString(
                    Map.of("resolutionNote", ""));

            mockMvc.perform(post("/api/v1/attendance/requirements/evaluations/{evaluationId}/resolve",
                            EVALUATION_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());
        }
    }
}
