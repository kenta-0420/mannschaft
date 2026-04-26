package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.dashboard.ViewerRole;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * チームダッシュボード一括取得レスポンス。
 *
 * <p>F02.2.1 で {@code viewerRole}（閲覧者ロール）と {@code widgetVisibility}
 * （ウィジェット可視性配列）を追加。{@code viewerRole.isAtLeast(min_role)} を満たさない
 * ウィジェットはレスポンス本体のフィールドが null になる。フロントエンドは
 * {@code viewerRole} を見て「自分はこのチームでは PUBLIC 扱いだ」のような判定を行う。</p>
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

    // ========================================
    // F02.2.1 追加フィールド
    // ========================================

    /** 閲覧者の本スコープでのロール（SYSTEM_ADMIN / ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER / PUBLIC） */
    @JsonProperty("viewer_role")
    private final ViewerRole viewerRole;

    /** ウィジェット可視性マップ（min_role 管理対象ウィジェットのみ含む。ADMIN 限定ウィジェットは除外） */
    @JsonProperty("widget_visibility")
    private final List<WidgetVisibilityRowDto> widgetVisibility;
}
