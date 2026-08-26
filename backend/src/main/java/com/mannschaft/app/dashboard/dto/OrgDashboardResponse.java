package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.dashboard.ViewerRole;
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
    // F22.1 第二波 追加フィールド（02 §3.3 サマリ追加フィールド・組織スコープは①②③も新設）
    // is_visible=FALSE のウィジェットはサーバーでスキップ（null）し @JsonInclude(NON_NULL) で省略される
    // ========================================

    /** ①今後の予定（組織スコープ新設）: 今後 7 日間・最大 10 件。 */
    @JsonProperty("orgUpcomingEvents")
    private final List<Map<String, Object>> orgUpcomingEvents;

    /** ②タイムライン（組織スコープ新設）: 直近 3 件。実体なしは空配列。 */
    @JsonProperty("orgLatestPosts")
    private final List<Map<String, Object>> orgLatestPosts;

    /** ③掲示板（組織スコープ新設）: { bulletin_count, chat_count }。 */
    @JsonProperty("orgUnreadThreads")
    private final Map<String, Object> orgUnreadThreads;

    /** ④ブログ: 直近 3 件（id/title/author/published_at）。 */
    @JsonProperty("orgLatestBlogPosts")
    private final List<Map<String, Object>> orgLatestBlogPosts;

    /** ⑤チャット: { total_unread, channels:[...]（直近3）}。 */
    @JsonProperty("orgChatSummary")
    private final Map<String, Object> orgChatSummary;

    /** ⑥カレンダー: { events_today, events_this_week, next_event, days_with_events }。 */
    @JsonProperty("orgCalendarSummary")
    private final Map<String, Object> orgCalendarSummary;

    /** ⑧要対応: 統合「要対応」集計（回覧板/アンケート/出欠）。 */
    @JsonProperty("orgActionRequired")
    private final ActionRequiredSummaryResponse orgActionRequired;
}
