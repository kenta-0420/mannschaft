package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.ErrorReportWorkflowStage;
import com.mannschaft.app.errorreport.dto.KanbanResponse;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportAiAnalysisRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * エラーレポートの Kanban 表示用変換・フォーマット・ビルドを担当するサービス。
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ErrorReportKanbanService {

    private final ErrorReportRepository errorReportRepository;
    private final ErrorReportAiAnalysisRepository aiAnalysisRepository;
    private final UserRepository userRepository;

    /** Kanban カラムあたりの最大カード件数。 */
    private static final int KANBAN_COLUMN_CARD_LIMIT = 50;
    /** Kanban カードのエラーメッセージ表示上限。 */
    private static final int KANBAN_MESSAGE_MAX_LENGTH = 80;
    /** Kanban カードのページURL表示上限。 */
    private static final int KANBAN_PAGE_URL_MAX_LENGTH = 80;

    /**
     * F12.5 Phase 2-E — Kanban ビュー用の 6 カラムを取得する。
     *
     * <p>カラム順:</p>
     * <ol>
     *   <li>NULL（未着手） — status IN (NEW, INVESTIGATING, REOPENED) AND workflow_stage IS NULL</li>
     *   <li>INVESTIGATION_STARTED</li>
     *   <li>ROOT_CAUSE_IDENTIFIED</li>
     *   <li>FIX_IN_PROGRESS</li>
     *   <li>TEST_COMPLETED</li>
     *   <li>RELEASED</li>
     * </ol>
     *
     * <p>各カラム最大 50 件、{@code last_occurred_at DESC}。
     * IGNORED は対象外。assignee 名と AI 分析の有無はバルク解決して N+1 を防ぐ。</p>
     */
    public KanbanResponse fetchKanban() {
        // 各カラムごとに Page を取得し、key→content と totalCount を保持する
        Pageable pageable = PageRequest.of(0, KANBAN_COLUMN_CARD_LIMIT);

        // NULL（未着手）カラム
        Page<ErrorReportEntity> nullPage = errorReportRepository
                .findByStatusInAndWorkflowStageIsNullOrderByLastOccurredAtDesc(
                        List.of(ErrorReportStatus.NEW,
                                ErrorReportStatus.INVESTIGATING,
                                ErrorReportStatus.REOPENED),
                        pageable);

        // 各 workflow_stage カラム
        Map<ErrorReportWorkflowStage, Page<ErrorReportEntity>> stagePages = new HashMap<>();
        for (ErrorReportWorkflowStage stage : ErrorReportWorkflowStage.values()) {
            stagePages.put(stage, errorReportRepository
                    .findByWorkflowStageOrderByLastOccurredAtDesc(stage, pageable));
        }

        // バルク解決のため、全カードのレポートを集める
        List<ErrorReportEntity> allReports = new ArrayList<>(nullPage.getContent());
        for (ErrorReportWorkflowStage stage : ErrorReportWorkflowStage.values()) {
            allReports.addAll(stagePages.get(stage).getContent());
        }

        Set<Long> assigneeIds = new HashSet<>();
        List<Long> reportIds = new ArrayList<>(allReports.size());
        for (ErrorReportEntity r : allReports) {
            reportIds.add(r.getId());
            if (r.getAssigneeId() != null) {
                assigneeIds.add(r.getAssigneeId());
            }
        }
        Map<Long, String> assigneeNames = resolveUserNames(assigneeIds);
        Set<Long> aiAnalyzedIds = reportIds.isEmpty()
                ? Set.of()
                : new HashSet<>(aiAnalysisRepository.findIdsHavingSuccessfulAnalysis(reportIds));

        // カラム組み立て
        List<KanbanResponse.KanbanColumn> columns = new ArrayList<>();
        columns.add(buildColumn("NULL",
                nullPage.getTotalElements(),
                nullPage.getTotalElements() > KANBAN_COLUMN_CARD_LIMIT,
                nullPage.getContent(),
                assigneeNames,
                aiAnalyzedIds));
        for (ErrorReportWorkflowStage stage : ErrorReportWorkflowStage.values()) {
            Page<ErrorReportEntity> p = stagePages.get(stage);
            columns.add(buildColumn(stage.name(),
                    p.getTotalElements(),
                    p.getTotalElements() > KANBAN_COLUMN_CARD_LIMIT,
                    p.getContent(),
                    assigneeNames,
                    aiAnalyzedIds));
        }

        return KanbanResponse.builder().columns(columns).build();
    }

    /**
     * Kanban カラムを組み立てる。エンティティ → カードに変換し、
     * バルク解決済みの assignee 名と AI 分析判定を埋め込む。
     */
    private KanbanResponse.KanbanColumn buildColumn(String stageKey,
                                                     long totalCount,
                                                     boolean hasMore,
                                                     List<ErrorReportEntity> reports,
                                                     Map<Long, String> assigneeNames,
                                                     Set<Long> aiAnalyzedIds) {
        List<KanbanResponse.KanbanCard> cards = new ArrayList<>(reports.size());
        for (ErrorReportEntity r : reports) {
            cards.add(KanbanResponse.KanbanCard.builder()
                    .id(r.getId())
                    .errorMessage(truncate(r.getErrorMessage(), KANBAN_MESSAGE_MAX_LENGTH))
                    .severity(r.getSeverity().name())
                    .status(r.getStatus().name())
                    .occurrenceCount(r.getOccurrenceCount() != null ? r.getOccurrenceCount() : 0)
                    .affectedUserCount(r.getAffectedUserCount() != null ? r.getAffectedUserCount() : 0)
                    .lastOccurredAt(r.getLastOccurredAt())
                    .assigneeId(r.getAssigneeId())
                    .assigneeName(r.getAssigneeId() != null ? assigneeNames.get(r.getAssigneeId()) : null)
                    .pageUrl(truncate(r.getPageUrl(), KANBAN_PAGE_URL_MAX_LENGTH))
                    .hasGithubIssue(r.getGithubIssueUrl() != null && !r.getGithubIssueUrl().isBlank())
                    .hasAiAnalysis(aiAnalyzedIds.contains(r.getId()))
                    .build());
        }
        return KanbanResponse.KanbanColumn.builder()
                .stageKey(stageKey)
                .totalCount(totalCount)
                .hasMore(hasMore)
                .cards(cards)
                .build();
    }

    /**
     * ユーザーIDから表示名を一括解決する（N+1 防止）。
     */
    private Map<Long, String> resolveUserNames(Set<Long> userIds) {
        if (userIds.isEmpty()) return Map.of();
        Map<Long, String> result = new HashMap<>();
        List<UserEntity> users = userRepository.findByIdIn(userIds);
        for (UserEntity u : users) {
            result.put(u.getId(), u.getLastName() + " " + u.getFirstName());
        }
        return result;
    }

    /**
     * 文字列を指定長に切り詰める。
     */
    static String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() <= maxLength ? str : str.substring(0, maxLength) + "...";
    }
}
