package com.mannschaft.app.errorreport.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.errorreport.ErrorReportMapper;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.ErrorReportWorkflowStage;
import com.mannschaft.app.errorreport.dto.ErrorReportAssigneeRequest;
import com.mannschaft.app.errorreport.dto.ErrorReportCommentRequest;
import com.mannschaft.app.errorreport.dto.ErrorReportResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportTimelineResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportWorkflowStageRequest;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.service.ErrorReportKanbanService;
import com.mannschaft.app.errorreport.service.ErrorReportQueryService;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import com.mannschaft.app.errorreport.service.ErrorReportTimelineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * F12.5 Phase 2 — {@link SystemAdminErrorReportController} の単体テスト。
 * P2-B 範囲の 4 エンドポイントを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SystemAdminErrorReportController Phase2 単体テスト")
class SystemAdminErrorReportControllerTest {

    @Mock
    private ErrorReportService errorReportService;
    @Mock
    private ErrorReportQueryService errorReportQueryService;
    @Mock
    private ErrorReportKanbanService errorReportKanbanService;
    @Mock
    private ErrorReportTimelineService errorReportTimelineService;
    @Mock
    private ErrorReportMapper errorReportMapper;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private com.mannschaft.app.errorreport.service.ErrorReportAiAnalysisService aiAnalysisService;
    @Mock
    private com.mannschaft.app.errorreport.repository.ErrorReportAiAnalysisRepository aiAnalysisRepository;
    @Mock
    private com.mannschaft.app.errorreport.service.GitHubIssueService gitHubIssueService;
    @Mock
    private com.mannschaft.app.errorreport.ErrorReportProperties errorReportProperties;
    @Mock
    private MediaUrlResolver mediaUrlResolver;

    @InjectMocks
    private SystemAdminErrorReportController controller;

    private static final Long ACTOR_ID = 1L;
    private static final Long REPORT_ID = 100L;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(ACTOR_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    private ErrorReportEntity sampleEntity() {
        return ErrorReportEntity.builder()
                .errorMessage("test")
                .pageUrl("/x")
                .occurredAt(LocalDateTime.now())
                .status(ErrorReportStatus.NEW)
                .severity(ErrorReportSeverity.MEDIUM)
                .errorHash("h")
                .occurrenceCount(1)
                .affectedUserCount(1)
                .firstOccurredAt(LocalDateTime.now())
                .lastOccurredAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("PATCH /workflow-stage: TimelineService.updateWorkflowStage を呼び出す")
    void updateWorkflowStage_callsService() {
        ErrorReportWorkflowStageRequest req = new ErrorReportWorkflowStageRequest();
        req.setWorkflowStage(ErrorReportWorkflowStage.INVESTIGATION_STARTED);
        ErrorReportEntity entity = sampleEntity();
        ErrorReportResponse response = ErrorReportResponse.builder().id(REPORT_ID).build();

        given(errorReportTimelineService.updateWorkflowStage(eq(REPORT_ID),
                eq(ErrorReportWorkflowStage.INVESTIGATION_STARTED), eq(ACTOR_ID)))
                .willReturn(entity);
        given(errorReportMapper.toResponse(entity)).willReturn(response);

        controller.updateWorkflowStage(REPORT_ID, req);

        verify(errorReportTimelineService).updateWorkflowStage(REPORT_ID,
                ErrorReportWorkflowStage.INVESTIGATION_STARTED, ACTOR_ID);
    }

    @Test
    @DisplayName("PATCH /assignee: TimelineService.assign を呼び出す")
    void assign_callsService() {
        ErrorReportAssigneeRequest req = new ErrorReportAssigneeRequest();
        req.setAssigneeId(99L);
        ErrorReportEntity entity = sampleEntity();
        ErrorReportResponse response = ErrorReportResponse.builder().id(REPORT_ID).build();

        given(errorReportTimelineService.assign(REPORT_ID, 99L, ACTOR_ID)).willReturn(entity);
        given(errorReportMapper.toResponse(entity)).willReturn(response);

        controller.assign(REPORT_ID, req);

        verify(errorReportTimelineService).assign(REPORT_ID, 99L, ACTOR_ID);
    }

    @Test
    @DisplayName("POST /comments: TimelineService.addComment を呼び出す")
    void addComment_callsService() {
        ErrorReportCommentRequest req = new ErrorReportCommentRequest();
        req.setContent("コメント本文");

        controller.addComment(REPORT_ID, req);

        verify(errorReportTimelineService).addComment(REPORT_ID, "コメント本文", ACTOR_ID);
    }

    @Test
    @DisplayName("GET /kanban: 認可チェックの上で KanbanService.fetchKanban を呼び出す")
    void kanban_callsService() {
        com.mannschaft.app.errorreport.dto.KanbanResponse stub =
                com.mannschaft.app.errorreport.dto.KanbanResponse.builder()
                        .columns(List.of())
                        .build();
        given(errorReportKanbanService.fetchKanban()).willReturn(stub);

        ResponseEntity<?> entity = controller.kanban();

        verify(accessControlService).checkSystemAdmin(ACTOR_ID);
        verify(errorReportKanbanService).fetchKanban();
        assertThat(entity.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("GET /timeline: 認可チェックの上で TimelineService.fetchTimeline を呼び出す")
    void timeline_callsService() {
        ErrorReportTimelineResponse stub = ErrorReportTimelineResponse.builder()
                .items(List.of())
                .hasMore(false)
                .build();
        given(errorReportTimelineService.fetchTimeline(eq(REPORT_ID), any(), eq(50))).willReturn(stub);

        ResponseEntity<?> entity = controller.timeline(REPORT_ID, null, 50);

        verify(accessControlService).checkSystemAdmin(ACTOR_ID);
        verify(errorReportTimelineService).fetchTimeline(REPORT_ID, null, 50);
        assertThat(entity.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("GET /timeline: limit が 100 を超えた場合は 100 にキャップされる")
    void timeline_capsLimitTo100() {
        ErrorReportTimelineResponse stub = ErrorReportTimelineResponse.builder()
                .items(List.of()).hasMore(false).build();
        given(errorReportTimelineService.fetchTimeline(eq(REPORT_ID), any(), eq(100))).willReturn(stub);

        controller.timeline(REPORT_ID, null, 9999);

        verify(errorReportTimelineService).fetchTimeline(REPORT_ID, null, 100);
    }

    // ========================================
    // F12.5 Phase 2-D — GitHub Issue / config
    // ========================================

    @Test
    @DisplayName("POST /github-issue: 認可チェックの上で GitHubIssueService.createIssue を呼び出す")
    void createGithubIssue_callsService() {
        String issueUrl = "https://github.com/octocat/hello-world/issues/42";
        given(gitHubIssueService.createIssue(REPORT_ID, ACTOR_ID)).willReturn(issueUrl);

        ResponseEntity<?> entity = controller.createGithubIssue(REPORT_ID);

        verify(accessControlService).checkSystemAdmin(ACTOR_ID);
        verify(gitHubIssueService).createIssue(REPORT_ID, ACTOR_ID);
        assertThat(entity.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("GET /config: 認可チェック + GitHub/AI 有効状態を返す")
    void config_returnsConfigStatus() {
        com.mannschaft.app.errorreport.ErrorReportProperties.Ai aiProps =
                new com.mannschaft.app.errorreport.ErrorReportProperties.Ai();
        aiProps.setEnabled(true);
        aiProps.setModel("claude-haiku-4-5");
        aiProps.setMonthlyBudgetJpy(5000);
        given(errorReportProperties.getAi()).willReturn(aiProps);
        given(gitHubIssueService.isAvailable()).willReturn(true);

        // claude-api-key を設定（@Value 経由）
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "claudeApiKey", "sk-ant-dummy");

        ResponseEntity<?> entity = controller.config();

        verify(accessControlService).checkSystemAdmin(ACTOR_ID);
        assertThat(entity.getStatusCode().value()).isEqualTo(200);
    }
}
