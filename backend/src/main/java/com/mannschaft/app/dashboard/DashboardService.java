package com.mannschaft.app.dashboard;

import com.mannschaft.app.dashboard.dto.GreetingResponse;
import com.mannschaft.app.dashboard.dto.OrgDashboardResponse;
import com.mannschaft.app.dashboard.dto.PersonalDashboardResponse;
import com.mannschaft.app.dashboard.dto.ScopeCoverageResponse;
import com.mannschaft.app.dashboard.dto.TeamDashboardResponse;
import com.mannschaft.app.dashboard.dto.WidgetSettingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * ダッシュボードデータ集約サービス。
 * 個人・チーム・組織ダッシュボードの一括取得を担当する。
 * 各ウィジェットのデータは将来的にCompletableFuture（Virtual Threads）で並行取得するが、
 * 現時点ではスタブデータを返却する。他機能のServiceが実装され次第、段階的に連携する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private final DashboardWidgetService widgetService;

    /** スコープ横断取得の上限スコープ数 */
    private static final int MAX_DISPLAY_SCOPES = 20;

    /**
     * 個人ダッシュボードを一括取得する。
     *
     * @param userId   ユーザーID
     * @param priority 取得優先度（CRITICAL: 第1段階のみ / ALL: 全ウィジェット）
     */
    public PersonalDashboardResponse getPersonalDashboard(Long userId, String priority) {
        List<WidgetSettingResponse> widgetSettings = widgetService.getWidgetSettings(userId, ScopeType.PERSONAL, 0L, false);

        GreetingResponse greeting = buildGreeting(userId);
        ScopeCoverageResponse scopeCoverage = buildScopeCoverage(userId);

        PersonalDashboardResponse.PersonalDashboardResponseBuilder builder = PersonalDashboardResponse.builder()
                .greeting(greeting)
                .widgetSettings(widgetSettings)
                .scopeCoverage(scopeCoverage);

        boolean criticalOnly = "CRITICAL".equalsIgnoreCase(priority);

        // 第1段階ウィジェット（常に取得）
        // TODO: notifications テーブル連携
        builder.notices(Map.of("items", List.of(), "unread_count", 0, "total_count", 0));
        // TODO: schedules + schedule_attendances 連携
        builder.upcomingEvents(List.of());
        // TODO: todos + todo_assignees 連携
        builder.personalTodo(Map.of("items", List.of(), "overdue_count", 0, "total_incomplete", 0));
        // TODO: platform_announcements 連携
        builder.platformAnnouncements(List.of());

        if (!criticalOnly) {
            // 第2段階ウィジェット
            // TODO: timeline_posts 連携
            builder.myPosts(List.of());
            // TODO: bulletin_threads + chat_channel_members 連携
            builder.unreadThreads(Map.of(
                    "bulletin_threads", List.of(),
                    "chat_channels", List.of(),
                    "total_unread_bulletin", 0,
                    "total_unread_chat", 0
            ));
            // TODO: activity_feed 連携
            builder.recentActivity(List.of());
            // TODO: schedules 個人 + チーム公開イベント連携
            builder.personalCalendar(Map.of(
                    "events_today", 0,
                    "events_this_week", 0,
                    "events_this_month", 0
            ));
            // TODO: パフォーマンスデータ連携
            builder.performanceSummary(null);
            // TODO: プロジェクト進捗連携
            builder.personalProjectProgress(null);
            // TODO: チャットハブ連携
            builder.chatHub(null);
        }

        return builder.build();
    }

    /**
     * チームダッシュボードを一括取得する。
     *
     * @param userId     ユーザーID
     * @param teamId     チームID
     * @param statsPeriod 統計期間（TODAY / WEEK / MONTH）
     */
    public TeamDashboardResponse getTeamDashboard(Long userId, Long teamId, String statsPeriod) {
        // TODO: チームメンバーシップ検証（非メンバーは403）
        // TODO: ロール・権限グループ判定

        List<WidgetSettingResponse> widgetSettings = widgetService.getWidgetSettings(userId, ScopeType.TEAM, teamId, true);

        return TeamDashboardResponse.builder()
                .teamNotices(List.of())
                .teamUpcomingEvents(List.of())
                .teamTodo(Map.of("items", List.of(), "overdue_count", 0, "total_incomplete", 0))
                .teamProjectProgress(List.of())
                .teamActivity(Map.of(
                        "posts_this_week", 0,
                        "events_this_week", 0,
                        "active_members_this_week", 0,
                        "total_members", 0
                ))
                .teamLatestPosts(List.of())
                .teamUnreadThreads(Map.of("bulletin_count", 0, "chat_count", 0))
                .teamMemberAttendance(Map.of("attending", 0, "absent", 0, "pending", 0))
                // TODO: ADMIN/DEPUTY_ADMINの場合のみ課金サマリーを返却
                .teamBilling(null)
                .teamPageViews(null)
                .widgetSettings(widgetSettings)
                .platformAnnouncements(List.of())
                .build();
    }

    /**
     * 組織ダッシュボードを一括取得する。
     *
     * @param userId     ユーザーID
     * @param orgId      組織ID
     * @param statsPeriod 統計期間（TODAY / WEEK / MONTH）
     */
    public OrgDashboardResponse getOrgDashboard(Long userId, Long orgId, String statsPeriod) {
        // TODO: 組織メンバーシップ検証（非メンバーは403）
        // TODO: ロール・権限グループ判定

        List<WidgetSettingResponse> widgetSettings = widgetService.getWidgetSettings(userId, ScopeType.ORGANIZATION, orgId, true);

        return OrgDashboardResponse.builder()
                .orgTeamList(List.of())
                .orgNotices(List.of())
                .orgTodo(Map.of("items", List.of(), "overdue_count", 0, "total_incomplete", 0))
                .orgProjectProgress(List.of())
                .orgStats(Map.of(
                        "total_teams", 0,
                        "total_members", 0,
                        "new_members_this_month", 0,
                        "active_rate", 0.0
                ))
                // TODO: ADMIN/DEPUTY_ADMINの場合のみ課金サマリーを返却
                .orgBilling(null)
                .widgetSettings(widgetSettings)
                .platformAnnouncements(List.of())
                .build();
    }

    /**
     * 挨拶ヘッダーを生成する。時間帯に応じた挨拶文とサマリーを返す。
     */
    private GreetingResponse buildGreeting(Long userId) {
        // TODO: ユーザーの display_name を users テーブルから取得
        String displayName = "ユーザー";

        LocalTime now = LocalTime.now();
        String greetingPrefix;
        int hour = now.getHour();
        if (hour >= 5 && hour < 12) {
            greetingPrefix = "おはようございます";
        } else if (hour >= 12 && hour < 18) {
            greetingPrefix = "こんにちは";
        } else {
            greetingPrefix = "こんばんは";
        }

        String message = greetingPrefix + "、" + displayName + "さん";
        // TODO: 実際のイベント件数・未読数を取得
        String summary = "新しいお知らせはありません";

        return new GreetingResponse(message, summary);
    }

    /**
     * スコープカバレッジ情報を構築する。
     */
    private ScopeCoverageResponse buildScopeCoverage(Long userId) {
        // TODO: team_memberships から所属スコープ数を取得
        int totalScopes = 0;
        int displayedScopes = Math.min(totalScopes, MAX_DISPLAY_SCOPES);
        boolean hasHiddenScopes = totalScopes > MAX_DISPLAY_SCOPES;

        return new ScopeCoverageResponse(totalScopes, displayedScopes, hasHiddenScopes);
    }
}
