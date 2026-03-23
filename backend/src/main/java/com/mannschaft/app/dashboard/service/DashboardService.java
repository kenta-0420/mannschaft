package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.admin.entity.PlatformAnnouncementEntity;
import com.mannschaft.app.admin.repository.PlatformAnnouncementRepository;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.dto.ActivityFeedResponse;
import com.mannschaft.app.dashboard.dto.GreetingResponse;
import com.mannschaft.app.dashboard.dto.OrgDashboardResponse;
import com.mannschaft.app.dashboard.dto.PersonalDashboardResponse;
import com.mannschaft.app.dashboard.dto.ScopeCoverageResponse;
import com.mannschaft.app.dashboard.dto.TeamDashboardResponse;
import com.mannschaft.app.dashboard.dto.WidgetSettingResponse;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ダッシュボードデータ集約サービス。
 * 個人・チーム・組織ダッシュボードの一括取得を担当する。
 * 各ウィジェットのデータは将来的にCompletableFuture（Virtual Threads）で並行取得するが、
 * 現時点では各リポジトリから実データを取得する。他機能のServiceが実装され次第、段階的に連携する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private final DashboardWidgetService widgetService;
    private final NameResolverService nameResolverService;
    private final AccessControlService accessControlService;
    private final ActivityFeedService activityFeedService;
    private final NotificationRepository notificationRepository;
    private final ScheduleRepository scheduleRepository;
    private final TodoRepository todoRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final BulletinThreadRepository bulletinThreadRepository;
    private final BulletinReadStatusRepository bulletinReadStatusRepository;
    private final ChatChannelMemberRepository chatChannelMemberRepository;
    private final PlatformAnnouncementRepository platformAnnouncementRepository;
    private final UserRoleRepository userRoleRepository;

    /** スコープ横断取得の上限スコープ数 */
    private static final int MAX_DISPLAY_SCOPES = 20;
    /** ダッシュボード表示用の最新件数 */
    private static final int DASHBOARD_ITEM_LIMIT = 5;

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

        // notifications 連携: 未読数 + 最新5件
        long unreadCount = notificationRepository.countByUserIdAndIsReadFalse(userId);
        long totalCount = notificationRepository.countByUserId(userId);
        Page<NotificationEntity> notificationPage = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, DASHBOARD_ITEM_LIMIT));
        List<Map<String, Object>> notificationItems = notificationPage.getContent().stream()
                .map(this::toNotificationMap)
                .toList();
        builder.notices(Map.of("items", notificationItems, "unread_count", unreadCount, "total_count", totalCount));

        // schedules 連携: 今日〜7日後のスケジュール
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime weekLater = now.plusDays(7);
        List<ScheduleEntity> personalSchedules = scheduleRepository
                .findByUserIdAndStartAtBetweenOrderByStartAtAsc(userId, now, weekLater);
        // 所属チームのスケジュールも取得
        List<UserRoleEntity> teamRoles = userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId);
        List<ScheduleEntity> teamSchedules = teamRoles.stream()
                .flatMap(role -> scheduleRepository
                        .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(role.getTeamId(), now, weekLater).stream())
                .toList();
        List<Map<String, Object>> upcomingItems = new java.util.ArrayList<>();
        personalSchedules.stream().map(this::toScheduleMap).forEach(upcomingItems::add);
        teamSchedules.stream().map(this::toScheduleMap).forEach(upcomingItems::add);
        upcomingItems.sort((a, b) -> ((LocalDateTime) a.get("start_at")).compareTo((LocalDateTime) b.get("start_at")));
        if (upcomingItems.size() > 10) {
            upcomingItems = upcomingItems.subList(0, 10);
        }
        builder.upcomingEvents(upcomingItems);

        // todos 連携: 自分に割り当てられた未完了TODO
        List<TodoEntity> myTodos = todoRepository.findMyTodos(userId);
        List<TodoEntity> incompleteTodos = myTodos.stream()
                .filter(t -> t.getStatus() != TodoStatus.COMPLETED)
                .toList();
        long overdueCount = incompleteTodos.stream()
                .filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(LocalDate.now()))
                .count();
        List<Map<String, Object>> todoItems = incompleteTodos.stream()
                .limit(DASHBOARD_ITEM_LIMIT)
                .map(this::toTodoMap)
                .toList();
        builder.personalTodo(Map.of("items", todoItems, "overdue_count", overdueCount, "total_incomplete", (long) incompleteTodos.size()));

        // platform_announcements 連携: 有効な告知を取得
        List<PlatformAnnouncementEntity> announcements = platformAnnouncementRepository
                .findActiveAnnouncements(LocalDateTime.now());
        List<Map<String, Object>> announcementItems = announcements.stream()
                .limit(DASHBOARD_ITEM_LIMIT)
                .map(this::toAnnouncementMap)
                .toList();
        builder.platformAnnouncements(announcementItems);

        if (!criticalOnly) {
            // 第2段階ウィジェット

            // timeline_posts 連携: 自分の最新投稿5件
            List<TimelinePostEntity> myPosts = timelinePostRepository
                    .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, DASHBOARD_ITEM_LIMIT));
            builder.myPosts(myPosts.stream().map(this::toTimelinePostMap).toList());

            // bulletin_threads + chat_channel_members 連携
            // 掲示板: 所属チームのスレッドのうち未読のものをカウント
            long totalUnreadBulletin = 0;
            for (UserRoleEntity role : teamRoles) {
                Page<com.mannschaft.app.bulletin.entity.BulletinThreadEntity> threads =
                        bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                                com.mannschaft.app.bulletin.ScopeType.TEAM, role.getTeamId(), PageRequest.of(0, 100));
                for (var thread : threads.getContent()) {
                    boolean isRead = bulletinReadStatusRepository.existsByThreadIdAndUserId(thread.getId(), userId);
                    if (!isRead) {
                        totalUnreadBulletin++;
                    }
                }
            }
            // チャット: ユーザーが参加しているチャンネルの未読数合計
            List<ChatChannelMemberEntity> chatMemberships = chatChannelMemberRepository.findByUserId(userId);
            long totalUnreadChat = chatMemberships.stream()
                    .mapToInt(ChatChannelMemberEntity::getUnreadCount)
                    .sum();
            builder.unreadThreads(Map.of(
                    "bulletin_threads", List.of(),
                    "chat_channels", List.of(),
                    "total_unread_bulletin", totalUnreadBulletin,
                    "total_unread_chat", totalUnreadChat
            ));

            // activity_feed 連携: ActivityFeedServiceに委譲
            List<Long> scopeIds = teamRoles.stream().map(UserRoleEntity::getTeamId).toList();
            List<ActivityFeedResponse> recentActivity = activityFeedService
                    .getActivityFeed(userId, null, DASHBOARD_ITEM_LIMIT, scopeIds);
            builder.recentActivity(recentActivity.stream()
                    .map(a -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("id", a.getId());
                        map.put("type", a.getType());
                        map.put("actor", a.getActor());
                        map.put("scope_name", a.getScopeName());
                        map.put("created_at", a.getCreatedAt());
                        return map;
                    })
                    .toList());

            // schedules 個人 + チーム公開イベント連携: 件数集計
            LocalDateTime todayStart = LocalDate.now().atStartOfDay();
            LocalDateTime todayEnd = LocalDate.now().atTime(LocalTime.MAX);
            LocalDateTime weekStart = todayStart;
            LocalDateTime weekEnd = todayStart.plusDays(7);
            LocalDateTime monthEnd = todayStart.plusMonths(1);

            long eventsToday = scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(userId, todayStart, todayEnd).size();
            long eventsThisWeek = scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(userId, weekStart, weekEnd).size();
            long eventsThisMonth = scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(userId, weekStart, monthEnd).size();
            // チーム公開イベントも加算
            for (UserRoleEntity role : teamRoles) {
                eventsToday += scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(role.getTeamId(), todayStart, todayEnd).size();
                eventsThisWeek += scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(role.getTeamId(), weekStart, weekEnd).size();
                eventsThisMonth += scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(role.getTeamId(), weekStart, monthEnd).size();
            }
            builder.personalCalendar(Map.of(
                    "events_today", eventsToday,
                    "events_this_week", eventsThisWeek,
                    "events_this_month", eventsThisMonth
            ));

            // パフォーマンス管理・プロジェクト進捗・チャットハブはウィジェット設定のモジュール有効判定で制御
            // データ取得は各モジュール実装完了後に連携予定
            builder.performanceSummary(null);
            builder.personalProjectProgress(null);
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
        accessControlService.checkMembership(userId, teamId, "TEAM");
        boolean isAdmin = accessControlService.isAdminOrAbove(userId, teamId, "TEAM");

        List<WidgetSettingResponse> widgetSettings = widgetService.getWidgetSettings(userId, ScopeType.TEAM, teamId, isAdmin);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime periodStart = resolvePeriodStart(now, statsPeriod);

        // チームお知らせ: チームスコープの通知最新5件
        // (通知はユーザー単位のためチーム共有お知らせはスケジュール等で代替)
        // ここではチームのスケジュールを今後7日間取得
        List<ScheduleEntity> teamUpcoming = scheduleRepository
                .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(teamId, now, now.plusDays(7));
        List<Map<String, Object>> teamUpcomingItems = teamUpcoming.stream()
                .limit(10)
                .map(this::toScheduleMap)
                .toList();

        // チームTODO: チームスコープの未完了TODO
        Page<TodoEntity> teamTodos = todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                com.mannschaft.app.todo.TodoScopeType.TEAM, teamId, PageRequest.of(0, 100));
        List<TodoEntity> incompleteTeamTodos = teamTodos.getContent().stream()
                .filter(t -> t.getStatus() != TodoStatus.COMPLETED)
                .toList();
        long teamOverdue = incompleteTeamTodos.stream()
                .filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(LocalDate.now()))
                .count();
        List<Map<String, Object>> teamTodoItems = incompleteTeamTodos.stream()
                .limit(DASHBOARD_ITEM_LIMIT)
                .map(this::toTodoMap)
                .toList();

        // チームアクティビティ統計
        List<TimelinePostEntity> teamPosts = timelinePostRepository
                .findFeedByScopeType("TEAM", teamId, PageRequest.of(0, 100));
        long postsThisWeek = teamPosts.stream()
                .filter(p -> p.getCreatedAt() != null && p.getCreatedAt().isAfter(periodStart))
                .count();
        long eventsThisWeek = scheduleRepository
                .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(teamId, periodStart, now).size();
        long totalMembers = userRoleRepository.countByTeamId(teamId);

        // チーム最新投稿
        List<TimelinePostEntity> latestPosts = timelinePostRepository
                .findFeedByScopeType("TEAM", teamId, PageRequest.of(0, DASHBOARD_ITEM_LIMIT));

        // チーム未読スレッド
        Page<com.mannschaft.app.bulletin.entity.BulletinThreadEntity> teamThreads =
                bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                        com.mannschaft.app.bulletin.ScopeType.TEAM, teamId, PageRequest.of(0, 100));
        long unreadBulletinCount = 0;
        for (var thread : teamThreads.getContent()) {
            if (!bulletinReadStatusRepository.existsByThreadIdAndUserId(thread.getId(), userId)) {
                unreadBulletinCount++;
            }
        }

        // チャットチャンネル未読(チーム内)
        List<ChatChannelMemberEntity> chatMembers = chatChannelMemberRepository.findByUserId(userId);
        long unreadChatCount = chatMembers.stream()
                .mapToInt(ChatChannelMemberEntity::getUnreadCount)
                .sum();

        // platform_announcements
        List<PlatformAnnouncementEntity> announcements = platformAnnouncementRepository
                .findActiveAnnouncements(LocalDateTime.now());
        List<Map<String, Object>> announcementItems = announcements.stream()
                .limit(DASHBOARD_ITEM_LIMIT)
                .map(this::toAnnouncementMap)
                .toList();

        return TeamDashboardResponse.builder()
                .teamNotices(List.of())
                .teamUpcomingEvents(teamUpcomingItems)
                .teamTodo(Map.of("items", teamTodoItems, "overdue_count", teamOverdue, "total_incomplete", (long) incompleteTeamTodos.size()))
                .teamProjectProgress(List.of())
                .teamActivity(Map.of(
                        "posts_this_week", postsThisWeek,
                        "events_this_week", eventsThisWeek,
                        "active_members_this_week", 0,
                        "total_members", totalMembers
                ))
                .teamLatestPosts(latestPosts.stream().map(this::toTimelinePostMap).toList())
                .teamUnreadThreads(Map.of("bulletin_count", unreadBulletinCount, "chat_count", unreadChatCount))
                .teamMemberAttendance(Map.of("attending", 0, "absent", 0, "pending", 0))
                .teamBilling(isAdmin ? Map.of() : null)
                .teamPageViews(null)
                .widgetSettings(widgetSettings)
                .platformAnnouncements(announcementItems)
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
        accessControlService.checkMembership(userId, orgId, "ORGANIZATION");
        boolean isAdmin = accessControlService.isAdminOrAbove(userId, orgId, "ORGANIZATION");

        List<WidgetSettingResponse> widgetSettings = widgetService.getWidgetSettings(userId, ScopeType.ORGANIZATION, orgId, isAdmin);

        // 組織TODO: 組織スコープの未完了TODO
        Page<TodoEntity> orgTodos = todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                com.mannschaft.app.todo.TodoScopeType.ORGANIZATION, orgId, PageRequest.of(0, 100));
        List<TodoEntity> incompleteOrgTodos = orgTodos.getContent().stream()
                .filter(t -> t.getStatus() != TodoStatus.COMPLETED)
                .toList();
        long orgOverdue = incompleteOrgTodos.stream()
                .filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(LocalDate.now()))
                .count();
        List<Map<String, Object>> orgTodoItems = incompleteOrgTodos.stream()
                .limit(DASHBOARD_ITEM_LIMIT)
                .map(this::toTodoMap)
                .toList();

        // 組織統計
        long totalMembers = userRoleRepository.countByOrganizationId(orgId);

        // 組織お知らせ: 組織スコープのスケジュール
        LocalDateTime now = LocalDateTime.now();
        List<ScheduleEntity> orgSchedules = scheduleRepository
                .findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(orgId, now, now.plusDays(7));
        List<Map<String, Object>> orgNoticeItems = orgSchedules.stream()
                .limit(DASHBOARD_ITEM_LIMIT)
                .map(this::toScheduleMap)
                .toList();

        // platform_announcements
        List<PlatformAnnouncementEntity> announcements = platformAnnouncementRepository
                .findActiveAnnouncements(LocalDateTime.now());
        List<Map<String, Object>> announcementItems = announcements.stream()
                .limit(DASHBOARD_ITEM_LIMIT)
                .map(this::toAnnouncementMap)
                .toList();

        return OrgDashboardResponse.builder()
                .orgTeamList(List.of())
                .orgNotices(orgNoticeItems)
                .orgTodo(Map.of("items", orgTodoItems, "overdue_count", orgOverdue, "total_incomplete", (long) incompleteOrgTodos.size()))
                .orgProjectProgress(List.of())
                .orgStats(Map.of(
                        "total_teams", 0,
                        "total_members", totalMembers,
                        "new_members_this_month", 0,
                        "active_rate", 0.0
                ))
                .orgBilling(isAdmin ? Map.of() : null)
                .widgetSettings(widgetSettings)
                .platformAnnouncements(announcementItems)
                .build();
    }

    /**
     * 挨拶ヘッダーを生成する。時間帯に応じた挨拶文とサマリーを返す。
     */
    private GreetingResponse buildGreeting(Long userId) {
        String displayName = nameResolverService.resolveUserDisplayName(userId);

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

        // 未読通知数に基づくサマリー構築
        long unreadNotifications = notificationRepository.countByUserIdAndIsReadFalse(userId);
        String summary;
        if (unreadNotifications > 0) {
            summary = "未読のお知らせが" + unreadNotifications + "件あります";
        } else {
            summary = "新しいお知らせはありません";
        }

        return new GreetingResponse(message, summary);
    }

    /**
     * スコープカバレッジ情報を構築する。
     */
    private ScopeCoverageResponse buildScopeCoverage(Long userId) {
        // チーム所属 + 組織所属の合計をスコープ数とする
        List<UserRoleEntity> teamRoles = userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId);
        List<UserRoleEntity> orgRoles = userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(userId);
        int totalScopes = teamRoles.size() + orgRoles.size();
        int displayedScopes = Math.min(totalScopes, MAX_DISPLAY_SCOPES);
        boolean hasHiddenScopes = totalScopes > MAX_DISPLAY_SCOPES;

        return new ScopeCoverageResponse(totalScopes, displayedScopes, hasHiddenScopes);
    }

    /**
     * 統計期間の開始日時を解決する。
     */
    private LocalDateTime resolvePeriodStart(LocalDateTime now, String statsPeriod) {
        return switch (statsPeriod != null ? statsPeriod.toUpperCase() : "WEEK") {
            case "TODAY" -> now.toLocalDate().atStartOfDay();
            case "MONTH" -> now.minusMonths(1);
            default -> now.minusWeeks(1);
        };
    }

    /**
     * 通知エンティティをMap表現に変換する。
     */
    private Map<String, Object> toNotificationMap(NotificationEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId());
        map.put("type", entity.getNotificationType());
        map.put("title", entity.getTitle());
        map.put("body", entity.getBody());
        map.put("is_read", entity.getIsRead());
        map.put("action_url", entity.getActionUrl());
        map.put("created_at", entity.getCreatedAt());
        return map;
    }

    /**
     * スケジュールエンティティをMap表現に変換する。
     */
    private Map<String, Object> toScheduleMap(ScheduleEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId());
        map.put("title", entity.getTitle());
        map.put("start_at", entity.getStartAt());
        map.put("end_at", entity.getEndAt());
        map.put("location", entity.getLocation());
        map.put("all_day", entity.getAllDay());
        return map;
    }

    /**
     * TODOエンティティをMap表現に変換する。
     */
    private Map<String, Object> toTodoMap(TodoEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId());
        map.put("title", entity.getTitle());
        map.put("status", entity.getStatus().name());
        map.put("priority", entity.getPriority().name());
        map.put("due_date", entity.getDueDate());
        return map;
    }

    /**
     * タイムライン投稿エンティティをMap表現に変換する。
     */
    private Map<String, Object> toTimelinePostMap(TimelinePostEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId());
        map.put("content", entity.getContent());
        map.put("created_at", entity.getCreatedAt());
        return map;
    }

    /**
     * プラットフォームお知らせエンティティをMap表現に変換する。
     */
    private Map<String, Object> toAnnouncementMap(PlatformAnnouncementEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId());
        map.put("title", entity.getTitle());
        map.put("body", entity.getBody());
        map.put("priority", entity.getPriority());
        return map;
    }
}
