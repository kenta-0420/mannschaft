package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 組織ダッシュボード一括取得レスポンス。
 */
@Getter
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrgDashboardResponse {

    private final List<Map<String, Object>> orgTeamList;
    private final List<Map<String, Object>> orgNotices;
    private final Map<String, Object> orgTodo;
    private final List<Map<String, Object>> orgProjectProgress;
    private final Map<String, Object> orgStats;
    private final Map<String, Object> orgBilling;
    private final List<WidgetSettingResponse> widgetSettings;
    private final List<Map<String, Object>> platformAnnouncements;
}
