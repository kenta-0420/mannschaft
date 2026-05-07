package com.mannschaft.app.errorreport.dto;

import com.mannschaft.app.errorreport.ErrorReportActivityType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * F12.5 Phase 2 — タイムラインレスポンス。
 * {@code error_report_occurrences} と {@code error_report_activities} をマージし、
 * {@code occurredAt} 降順で返す。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorReportTimelineResponse {

    private List<TimelineItem> items;
    private boolean hasMore;
    private String nextCursor;

    /**
     * タイムライン上の 1 アイテム（OCCURRENCE / ACTIVITY 両用）。
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimelineItem {
        /** "OCCURRENCE" or "ACTIVITY" */
        private String type;

        private LocalDateTime occurredAt;

        // ===== OCCURRENCE 用 =====
        private String pageUrl;
        private Long userId;
        private String userAgent;

        // ===== ACTIVITY 用 =====
        private ErrorReportActivityType activityType;
        private Long actorId;
        /** null = 退会した管理者 / システム自動 */
        private String actorName;
        /** {@code metadata.system == true} なら true */
        private boolean systemActor;
        private String content;
        private Map<String, Object> metadata;
    }
}
