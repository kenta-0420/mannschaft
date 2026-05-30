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

    // ========================================
    // F22.1 第二波 追加フィールド（02 §3.3 サマリ追加フィールド）
    // is_visible=FALSE のウィジェットはサーバーでスキップ（null）し @JsonInclude(NON_NULL) で省略される
    // ========================================

    /** ④ブログ: 直近 3 件（id/title/author/published_at）。 */
    @JsonProperty("teamLatestBlogPosts")
    private final List<Map<String, Object>> teamLatestBlogPosts;

    /** ⑤チャット: { total_unread, channels:[{id,name,unread_count,last_message_preview}]（直近3）}。 */
    @JsonProperty("teamChatSummary")
    private final Map<String, Object> teamChatSummary;

    /** ⑥カレンダー: { events_today, events_this_week, next_event, days_with_events }。 */
    @JsonProperty("teamCalendarSummary")
    private final Map<String, Object> teamCalendarSummary;

    /** ⑧要対応: 統合「要対応」集計（回覧板/アンケート/出欠）。 */
    @JsonProperty("teamActionRequired")
    private final ActionRequiredSummaryResponse teamActionRequired;
}
