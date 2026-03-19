package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 個人ダッシュボード一括取得レスポンス。
 * is_visible=false のウィジェットはキー自体を含めない（JsonInclude.NON_NULL）。
 */
@Getter
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PersonalDashboardResponse {

    private final GreetingResponse greeting;
    private final List<Map<String, Object>> platformAnnouncements;
    private final Map<String, Object> notices;
    private final List<Map<String, Object>> upcomingEvents;
    private final List<Map<String, Object>> myPosts;
    private final Map<String, Object> unreadThreads;
    private final List<Map<String, Object>> recentActivity;
    private final Map<String, Object> personalCalendar;
    private final Map<String, Object> personalTodo;
    private final List<Map<String, Object>> personalProjectProgress;
    private final Map<String, Object> performanceSummary;
    private final Map<String, Object> chatHub;
    private final List<WidgetSettingResponse> widgetSettings;
    private final ScopeCoverageResponse scopeCoverage;
}
