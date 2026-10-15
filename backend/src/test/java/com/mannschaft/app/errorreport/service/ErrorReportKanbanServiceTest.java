package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.ErrorReportWorkflowStage;
import com.mannschaft.app.errorreport.dto.KanbanResponse;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportAiAnalysisRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * F12.5 Phase 2-E — {@link ErrorReportKanbanService} の Kanban 機能単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorReportKanbanService 単体テスト")
class ErrorReportKanbanServiceTest {

    @Mock
    private ErrorReportRepository errorReportRepository;
    @Mock
    private ErrorReportAiAnalysisRepository aiAnalysisRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ErrorReportKanbanService service;

    private ErrorReportEntity createReport(ErrorReportStatus status, ErrorReportWorkflowStage stage) {
        return ErrorReportEntity.builder()
                .errorMessage("test error")
                .pageUrl("/foo")
                .occurredAt(LocalDateTime.now())
                .status(status)
                .severity(ErrorReportSeverity.MEDIUM)
                .errorHash("hash")
                .occurrenceCount(1)
                .affectedUserCount(1)
                .firstOccurredAt(LocalDateTime.now())
                .lastOccurredAt(LocalDateTime.now())
                .workflowStage(stage)
                .build();
    }

    // ========================================
    // F12.5 Phase 2-E — Kanban
    // ========================================

    @Nested
    @DisplayName("fetchKanban")
    class FetchKanban {

        @Test
        @DisplayName("6 カラム返り、NULL カラムには status IN (NEW,INVESTIGATING,REOPENED) AND stage IS NULL のカードが入る")
        void returnsSixColumns() {
            ErrorReportEntity nullStaged = createReport(ErrorReportStatus.NEW, null);
            // setId を直接呼べないため、リフレクションを介さず id 未設定のまま検証する
            Page<ErrorReportEntity> nullPage = new PageImpl<>(
                    List.of(nullStaged), PageRequest.of(0, 50), 1);
            Page<ErrorReportEntity> emptyPage = new PageImpl<>(
                    Collections.emptyList(), PageRequest.of(0, 50), 0);

            given(errorReportRepository.findByStatusInAndWorkflowStageIsNullOrderByLastOccurredAtDesc(
                    eq(List.of(ErrorReportStatus.NEW,
                            ErrorReportStatus.INVESTIGATING,
                            ErrorReportStatus.REOPENED)), any()))
                    .willReturn(nullPage);
            given(errorReportRepository.findByWorkflowStageOrderByLastOccurredAtDesc(any(), any()))
                    .willReturn(emptyPage);
            given(aiAnalysisRepository.findIdsHavingSuccessfulAnalysis(any()))
                    .willReturn(Collections.emptyList());

            KanbanResponse response = service.fetchKanban();

            assertThat(response.getColumns()).hasSize(6);
            assertThat(response.getColumns().get(0).getStageKey()).isEqualTo("NULL");
            assertThat(response.getColumns().get(0).getCards()).hasSize(1);
            assertThat(response.getColumns().get(1).getStageKey()).isEqualTo("INVESTIGATION_STARTED");
            assertThat(response.getColumns().get(5).getStageKey()).isEqualTo("RELEASED");
        }

        @Test
        @DisplayName("空状態でも 6 カラムを返し、各カラムは hasMore=false")
        void returnsAllColumnsEvenWhenEmpty() {
            Page<ErrorReportEntity> emptyPage = new PageImpl<>(
                    Collections.emptyList(), PageRequest.of(0, 50), 0);
            given(errorReportRepository.findByStatusInAndWorkflowStageIsNullOrderByLastOccurredAtDesc(any(), any()))
                    .willReturn(emptyPage);
            given(errorReportRepository.findByWorkflowStageOrderByLastOccurredAtDesc(any(), any()))
                    .willReturn(emptyPage);
            // reportIds が空なら aiAnalysisRepository は呼ばれないため stub 不要

            KanbanResponse response = service.fetchKanban();

            assertThat(response.getColumns()).hasSize(6);
            response.getColumns().forEach(c -> {
                assertThat(c.getCards()).isEmpty();
                assertThat(c.isHasMore()).isFalse();
                assertThat(c.getTotalCount()).isZero();
            });
        }
    }
}
