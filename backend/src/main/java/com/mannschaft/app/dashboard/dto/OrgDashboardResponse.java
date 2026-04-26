package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 組織ダッシュボード一括取得レスポンス。
 *
 * <p>F02.2.1 で {@code viewerRole}（閲覧者ロール）と {@code widgetVisibility}
 * （ウィジェット可視性配列）を追加。{@code viewerRole.isAtLeast(min_role)} を満たさない
 * ウィジェットはレスポンス本体のフィールドが null になる。</p>
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
    /** F02.2.1: 本リクエスト閲覧者のロール */
    private final String viewerRole;
    /** F02.2.1: ウィジェット可視性配列。各要素は {@code widget_key / min_role / is_visible} を持つ */
    private final List<Map<String, Object>> widgetVisibility;
}
