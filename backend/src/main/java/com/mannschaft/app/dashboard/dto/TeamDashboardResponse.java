package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * チームダッシュボード一括取得レスポンス。
 *
 * <p>F02.2.1 で {@code viewerRole}（閲覧者ロール）と {@code widgetVisibility}
 * （ウィジェット可視性配列）を追加。{@code viewerRole.isAtLeast(min_role)} を満たさない
 * ウィジェットはレスポンス本体のフィールドが null になる。</p>
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
    /** F02.2.1: 本リクエスト閲覧者のロール（{@code PUBLIC} / {@code SUPPORTER} / {@code MEMBER}
     *  / {@code DEPUTY_ADMIN} / {@code ADMIN} / {@code SYSTEM_ADMIN}） */
    private final String viewerRole;
    /** F02.2.1: ウィジェット可視性配列。各要素は {@code widget_key / min_role / is_visible} を持つ */
    private final List<Map<String, Object>> widgetVisibility;
}
