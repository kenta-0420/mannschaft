package com.mannschaft.app.village.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.ReportCreateRequest;
import com.mannschaft.app.village.dto.ReportResolveRequest;
import com.mannschaft.app.village.dto.ReportResponse;
import com.mannschaft.app.village.entity.enums.VillageReportStatus;
import com.mannschaft.app.village.entity.enums.VillageReportTargetType;
import com.mannschaft.app.village.service.VillageReportService;
import com.mannschaft.app.village.service.VillageReportService.ReportActionTaken;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link VillageReportController} の MockMvc 結合テスト（F17.1 Phase 1 B7）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>各エンドポイントの HTTP ステータス + JSON 形状</li>
 *   <li>レスポンスに {@code reporter_user_id} が含まれないこと（通報者非開示）</li>
 *   <li>VILLAGE_041 → 429 / VILLAGE_024 → 403 / VILLAGE_042 → 409 / VILLAGE_040 → 404</li>
 * </ul>
 */
@WebMvcTest(VillageReportController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("VillageReportController 結合テスト")
class VillageReportControllerTest {

    private static final Long USER_ID = 100L;
    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000001");
    private static final UUID REPORT_ID = UUID.fromString("01956c00-0000-7000-8000-000000000aaa");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VillageReportService reportService;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    private ReportResponse sampleResponse() {
        return new ReportResponse(
                REPORT_ID,
                VillageReportTargetType.POST,
                "bulletin_post:01234567",
                "harassment",
                VillageReportStatus.PENDING,
                ReportResponse.ANONYMOUS_REPORTER,
                LocalDateTime.of(2026, 5, 14, 10, 0),
                null,
                null);
    }

    // ------------------------------------------------------------------
    // POST /reports
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /reports: 通報作成で 201 + 通報 JSON 返却（reporter_user_id は含まれない）")
    void create_201() throws Exception {
        given(reportService.createReport(eq(VILLAGE_ID), eq(USER_ID), any(ReportCreateRequest.class)))
                .willReturn(sampleResponse());

        String body = objectMapper.writeValueAsString(new ReportCreateRequest(
                VillageReportTargetType.POST, "bulletin_post:01234567", "harassment", "迷惑投稿"));

        mockMvc.perform(post("/api/v1/villages/{vid}/reports", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(REPORT_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.reporterDisplayName").value("ANONYMOUS_VILLAGER"))
                // 通報者ユーザーIDは絶対に含めない
                .andExpect(jsonPath("$.data.reporterUserId").doesNotExist())
                .andExpect(jsonPath("$.data.reporter_user_id").doesNotExist());
    }

    @Test
    @DisplayName("POST /reports: レートリミット超過で 429 VILLAGE_009")
    void create_rateLimited_429() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.VILLAGE_REPORT_RATE_LIMITED))
                .given(reportService).createReport(eq(VILLAGE_ID), eq(USER_ID), any(ReportCreateRequest.class));

        String body = objectMapper.writeValueAsString(new ReportCreateRequest(
                VillageReportTargetType.POST, "bulletin_post:01234567", "harassment", null));

        mockMvc.perform(post("/api/v1/villages/{vid}/reports", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_009"));
    }

    // ------------------------------------------------------------------
    // GET /reports
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /reports: HEADMAN/ELDER が一覧取得で 200・通報者は ANONYMOUS_VILLAGER 固定")
    void list_200() throws Exception {
        given(reportService.listReports(eq(VILLAGE_ID), eq(USER_ID), eq(VillageReportStatus.PENDING), eq(0), eq(50)))
                .willReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/villages/{vid}/reports?status=PENDING", VILLAGE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(REPORT_ID.toString()))
                .andExpect(jsonPath("$.data[0].reporterDisplayName").value("ANONYMOUS_VILLAGER"))
                .andExpect(jsonPath("$.data[0].reporterUserId").doesNotExist());
    }

    @Test
    @DisplayName("GET /reports: 非モデレーターは 403 VILLAGE_024")
    void list_forbidden_403() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN))
                .given(reportService).listReports(eq(VILLAGE_ID), eq(USER_ID), eq(null), eq(0), eq(50));

        mockMvc.perform(get("/api/v1/villages/{vid}/reports", VILLAGE_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_024"));
    }

    // ------------------------------------------------------------------
    // POST /reports/{id}/resolve
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /reports/{id}/resolve: HEADMAN が RESOLVED へ遷移で 200")
    void resolve_200() throws Exception {
        ReportResponse resolved = new ReportResponse(
                REPORT_ID,
                VillageReportTargetType.POST,
                "bulletin_post:01234567",
                "harassment",
                VillageReportStatus.RESOLVED,
                ReportResponse.ANONYMOUS_REPORTER,
                LocalDateTime.of(2026, 5, 14, 10, 0),
                "CONTENT_REMOVED",
                LocalDateTime.of(2026, 5, 14, 11, 0));
        given(reportService.resolveReport(eq(VILLAGE_ID), eq(REPORT_ID), eq(USER_ID), any(ReportResolveRequest.class)))
                .willReturn(resolved);

        String body = objectMapper.writeValueAsString(new ReportResolveRequest(
                VillageReportStatus.RESOLVED, ReportActionTaken.CONTENT_REMOVED, "投稿削除済"));

        mockMvc.perform(post("/api/v1/villages/{vid}/reports/{rid}/resolve", VILLAGE_ID, REPORT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.handlerAction").value("CONTENT_REMOVED"))
                .andExpect(jsonPath("$.data.reporterUserId").doesNotExist());
    }

    @Test
    @DisplayName("POST /reports/{id}/resolve: 既解決の通報は 409 VILLAGE_043")
    void resolve_alreadyResolved_409() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.VILLAGE_REPORT_ALREADY_RESOLVED))
                .given(reportService).resolveReport(eq(VILLAGE_ID), eq(REPORT_ID), eq(USER_ID),
                        any(ReportResolveRequest.class));

        String body = objectMapper.writeValueAsString(new ReportResolveRequest(
                VillageReportStatus.RESOLVED, ReportActionTaken.NONE, null));

        mockMvc.perform(post("/api/v1/villages/{vid}/reports/{rid}/resolve", VILLAGE_ID, REPORT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_043"));
    }
}
