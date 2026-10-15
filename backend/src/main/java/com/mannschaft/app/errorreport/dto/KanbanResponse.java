package com.mannschaft.app.errorreport.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F12.5 Phase 2-E — Kanban ビュー用レスポンス。
 *
 * <p>6 カラム（NULL=未着手 / INVESTIGATION_STARTED / ROOT_CAUSE_IDENTIFIED /
 * FIX_IN_PROGRESS / TEST_COMPLETED / RELEASED）を返す。各カラム最大 50 件、
 * {@code last_occurred_at DESC}。{@code IGNORED} は対象外。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KanbanResponse {

    private List<KanbanColumn> columns;

    /**
     * カラム単位の集約情報。
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KanbanColumn {
        /** "NULL" または ErrorReportWorkflowStage の name() */
        private String stageKey;
        /** 総件数（カラム全体） */
        private long totalCount;
        /** 表示用カード（最大 50 件） */
        private List<KanbanCard> cards;
        /** 51 件目以降が存在するか */
        private boolean hasMore;
    }

    /**
     * Kanban 1 カードの表示要素。プロパティ名は P2-E 設計どおり。
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KanbanCard {
        private Long id;
        /** 80 字で切り詰め済みエラーメッセージ。 */
        private String errorMessage;
        /** "LOW" / "MEDIUM" / "HIGH" / "CRITICAL" */
        private String severity;
        /** "NEW" / "INVESTIGATING" / ... */
        private String status;
        private int occurrenceCount;
        private int affectedUserCount;
        private LocalDateTime lastOccurredAt;
        private Long assigneeId;
        private String assigneeName;
        /** 80 字で切り詰め済みページ URL。 */
        private String pageUrl;
        private boolean hasGithubIssue;
        private boolean hasAiAnalysis;
    }
}
