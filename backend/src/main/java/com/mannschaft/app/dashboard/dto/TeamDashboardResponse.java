package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * チームダッシュボード一括取得レスポンス。
 */
@Getter
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TeamDashboardResponse {

    private final List<Map<String, Object>> teamNotices;
    private final List<Map<String, Object>> teamUpcomingEvents;
    private final Map<String, Object> teamTodo;
    private final List<Map<String, Object>> teamProjectProgress;
    private final Map<String, Object> teamActivity;
    private final List<Map<String, Object>> teamLatestPosts;
    private final Map<String, Object> teamUnreadThreads;
    private final Map<String, Object> teamMemberAttendance;
    private final Map<String, Object> teamBilling;
    private final Map<String, Object> teamPageViews;
    private final List<WidgetSettingResponse> widgetSettings;
    private final List<Map<String, Object>> platformAnnouncements;
}
