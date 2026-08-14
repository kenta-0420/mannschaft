package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.admin.entity.PlatformAnnouncementEntity;
import com.mannschaft.app.admin.repository.PlatformAnnouncementRepository;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.SwipeWidgetKey;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.WidgetKey;
import com.mannschaft.app.dashboard.dto.ActionRequiredSummaryResponse;
import com.mannschaft.app.dashboard.dto.ActivityFeedResponse;
import com.mannschaft.app.dashboard.dto.GreetingResponse;
import com.mannschaft.app.dashboard.dto.OrgDashboardResponse;
import com.mannschaft.app.dashboard.dto.PersonalDashboardResponse;
import com.mannschaft.app.dashboard.dto.ScopeCoverageResponse;
import com.mannschaft.app.dashboard.dto.TeamDashboardResponse;
import com.mannschaft.app.dashboard.dto.WidgetSettingResponse;
import com.mannschaft.app.dashboard.dto.WidgetVisibilityRowDto;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.AnnouncementVisibility;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
    private final RoleResolver roleResolver;
    private final WidgetVisibilityResolver widgetVisibilityResolver;
    private final NotificationRepository notificationRepository;
    private final ScheduleRepository scheduleRepository;
    private final TodoRepository todoRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final BulletinThreadRepository bulletinThreadRepository;
    private final BulletinReadStatusRepository bulletinReadStatusRepository;
    private final ChatChannelMemberRepository chatChannelMemberRepository;
    private final PlatformAnnouncementRepository platformAnnouncementRepository;
    private final UserRoleRepository userRoleRepository;
    private final AnnouncementFeedQueryRepository announcementFeedQueryRepository;
    private final ContentVisibilityChecker contentVisibilityChecker;

    // F22.1 第二波: 厳選ウィジェットサマリ + 統合「要対応」集計 + SWIPE 可視性
    private final ScopeWidgetSummaryService scopeWidgetSummaryService;
    private final ScopeActionRequiredFacade scopeActionRequiredFacade;
    private final SwipeWidgetVisibilityResolver swipeWidgetVisibilityResolver;

    /** スコープ横断取得の上限スコープ数 */
    private static final int MAX_DISPLAY_SCOPES = 20;
    /** ダッシュボード表示用の最新件数 */
    private static final int DASHBOARD_ITEM_LIMIT = 5;
    /** 掲示板の直近スレッド一覧の表示件数（dashboard-scope-panel-content 第二陣） */
    private static final int DASHBOARD_ITEM_LIMIT_THREADS = 3;

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
        // 所属チームのスケジュールも取得（N+1 解消: チーム ID を IN 句で一括取得）
        // CMP-027: user_roles ∪ memberships の在籍チーム ID（素メンバー/応援者を取りこぼさない）
        List<Long> teamIds = userRoleRepository.findTeamIdsByUserId(userId);
        List<ScheduleEntity> teamSchedules = teamIds.isEmpty()
                ? List.of()
                : scheduleRepository.findByTeamIdInAndStartAtBetween(teamIds, now, weekLater);
        // F00 認可基盤連携（CMP-017b 第五隊）: チーム予定は所属チームIDだけで取得しており
        // min_view_role 等の可視性判定を通していなかった（title/location が丸見え）。
        // filterAccessible で可視なものだけに絞る。個人予定は本人所有のため常に可視。
        Set<Long> visibleTeamScheduleIds = teamSchedules.isEmpty()
                ? Set.of()
                : contentVisibilityChecker.filterAccessible(
                        ReferenceType.SCHEDULE,
                        teamSchedules.stream().map(ScheduleEntity::getId).toList(),
                        userId);
        List<Map<String, Object>> upcomingItems = new java.util.ArrayList<>();
        personalSchedules.stream().map(this::toScheduleMap).forEach(upcomingItems::add);
        teamSchedules.stream()
                .filter(s -> visibleTeamScheduleIds.contains(s.getId()))
                .map(this::toScheduleMap).forEach(upcomingItems::add);
        upcomingItems.sort((a, b) -> ((LocalDateTime) a.get("start_at")).compareTo((LocalDateTime) b.get("start_at")));
        if (upcomingItems.size() > 10) {
            upcomingItems = upcomingItems.subList(0, 10);
        }
        builder.upcomingEvents(upcomingItems);

        // todos 連携: 自分に割り当てられた未完了TODO
        List<TodoEntity> myTodos = todoRepository.findMyTodos(userId);
        List<TodoEntity> incompleteTodos = myTodos.stream()
                .filter(t -> t.getStatus() == TodoStatus.OPEN || t.getStatus() == TodoStatus.IN_PROGRESS)
                .toList();
        long overdueCount = incompleteTodos.stream()
                .filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(LocalDate.now(TimezoneContextHolder.get())))
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
            // 第2段階ウィジェット。
            //
            // 各ウィジェットは独立した読み取りで、相互に依存しないため CompletableFuture で
            // 並列取得する（チームダッシュボードの SWIPE サマリと同じ作法）。open-in-view=false の
            // ため、各 future 内では遅延ロードに依存せず、エンティティを Map / プリミティブへ
            // 即時変換して返すことで Hibernate セッション越境を避ける。
            final List<Long> finalTeamIds = teamIds;
            // TimezoneContextHolder は inheritable=false の ThreadLocal のため、リクエストスレッドで
            // 取得した ZoneId を future へ明示的に引き渡す（async ワーカーでは UTC に化けるのを防ぐ。
            // チームダッシュボードの buildCalendarSummary と同じ作法）。
            final java.time.ZoneId userZone = TimezoneContextHolder.get();

            CompletableFuture<List<Map<String, Object>>> myPostsFuture =
                    CompletableFuture.supplyAsync(() -> loadMyPosts(userId));
            CompletableFuture<Map<String, Object>> unreadThreadsFuture =
                    CompletableFuture.supplyAsync(() -> loadUnreadThreads(userId, finalTeamIds));
            CompletableFuture<List<Map<String, Object>>> recentActivityFuture =
                    CompletableFuture.supplyAsync(() -> loadRecentActivity(userId, finalTeamIds));
            CompletableFuture<Map<String, Object>> personalCalendarFuture =
                    CompletableFuture.supplyAsync(() -> loadPersonalCalendarCounts(userId, finalTeamIds, userZone));

            // すべて join（例外は CompletionException として伝播させ握り潰さない: 障害対応の原則）。
            builder.myPosts(myPostsFuture.join());
            builder.unreadThreads(unreadThreadsFuture.join());
            builder.recentActivity(recentActivityFuture.join());
            builder.personalCalendar(personalCalendarFuture.join());

            // パフォーマンス管理・プロジェクト進捗・チャットハブはウィジェット設定のモジュール有効判定で制御
            // データ取得は各モジュール実装完了後に連携予定
            builder.performanceSummary(null);
            builder.personalProjectProgress(null);
            builder.chatHub(null);
        }

        return builder.build();
    }

    /**
     * 第2段階: 自分の最新投稿（直近 {@value #DASHBOARD_ITEM_LIMIT} 件）を Map リストで取得する。
     */
    private List<Map<String, Object>> loadMyPosts(Long userId) {
        List<TimelinePostEntity> myPosts = timelinePostRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, DASHBOARD_ITEM_LIMIT));
        return myPosts.stream().map(this::toTimelinePostMap).toList();
    }

    /**
     * 第2段階: 掲示板・チャットの未読サマリを取得する（掲示板未読は N+1 を撲滅した 2 クエリ集計）。
     *
     * <p>掲示板: 所属チームのスレッド ID を 1 クエリで一括取得し（{@code findIdsByScopeTypeAndScopeIdIn}）、
     * そのうち既読のスレッド ID を 1 クエリで取得（{@code findReadThreadIds}）。未読数 =
     * 「対象スレッド ID 集合 − 既読スレッド ID 集合」の要素数。所属チームが無い場合は既読クエリを
     * 発行しない（{@code IN ()} 非発行）。</p>
     */
    private Map<String, Object> loadUnreadThreads(Long userId, List<Long> teamIds) {
        long totalUnreadBulletin = 0;
        if (!teamIds.isEmpty()) {
            List<Long> threadIds = bulletinThreadRepository.findIdsByScopeTypeAndScopeIdIn(
                    com.mannschaft.app.bulletin.ScopeType.TEAM, teamIds);
            if (!threadIds.isEmpty()) {
                Set<Long> readThreadIds = new java.util.HashSet<>(
                        bulletinReadStatusRepository.findReadThreadIds(threadIds, userId));
                // distinct なスレッド ID のうち未読のものを数える（重複スレッド ID は集合化で吸収）。
                totalUnreadBulletin = threadIds.stream()
                        .distinct()
                        .filter(id -> !readThreadIds.contains(id))
                        .count();
            }
        }
        // チャット: ユーザーが参加しているチャンネルの未読数合計。
        List<ChatChannelMemberEntity> chatMemberships = chatChannelMemberRepository.findByUserId(userId);
        long totalUnreadChat = chatMemberships.stream()
                .mapToInt(ChatChannelMemberEntity::getUnreadCount)
                .sum();
        return Map.of(
                "bulletin_threads", List.of(),
                "chat_channels", List.of(),
                "total_unread_bulletin", totalUnreadBulletin,
                "total_unread_chat", totalUnreadChat
        );
    }

    /**
     * 第2段階: アクティビティフィードを Map リストで取得する（ActivityFeedService に委譲）。
     */
    private List<Map<String, Object>> loadRecentActivity(Long userId, List<Long> teamIds) {
        List<ActivityFeedResponse> recentActivity = activityFeedService
                .getActivityFeed(userId, null, DASHBOARD_ITEM_LIMIT, teamIds);
        return recentActivity.stream()
                .map(a -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", a.getId());
                    map.put("type", a.getType());
                    map.put("actor", a.getActor());
                    map.put("scope_name", a.getScopeName());
                    map.put("created_at", a.getCreatedAt());
                    return map;
                })
                .toList();
    }

    /**
     * 第2段階: 個人 + チーム公開イベントの today/week/month 件数を集計する。
     *
     * <p>従来は期間（today/week/month）ごとに個人・チームのスケジュールを取得しており、
     * 同一テーブルへ計 6 クエリを発行していた。本メソッドは最大範囲（当日 0:00〜1か月後）を
     * <b>個人 1 クエリ + チーム 1 バッチクエリ</b>で取得し、アプリ層で today/week/month を集計する。
     * すべての期間が当日 0:00 起点で、境界は元の {@code BETWEEN}（両端含む）と同値になるよう
     * {@code <=} で判定する。所属チームが無い場合はチーム取得を発行しない（{@code IN ()} 非発行）。</p>
     */
    private Map<String, Object> loadPersonalCalendarCounts(Long userId, List<Long> teamIds, java.time.ZoneId userZone) {
        LocalDate today = LocalDate.now(userZone);
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.atTime(LocalTime.MAX);
        LocalDateTime weekEnd = todayStart.plusDays(7);
        LocalDateTime monthEnd = todayStart.plusMonths(1);

        // 最大範囲（todayStart〜monthEnd）を 1 回だけ取得（個人 1 クエリ + チーム 1 バッチクエリ）。
        List<ScheduleEntity> schedules = new ArrayList<>(
                scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(userId, todayStart, monthEnd));
        if (!teamIds.isEmpty()) {
            List<ScheduleEntity> teamSchedules =
                    scheduleRepository.findByTeamIdInAndStartAtBetween(teamIds, todayStart, monthEnd);
            // F00 認可基盤連携（CMP-017b 第五隊）: 件数集計であっても正規の可視性判定を通す
            // （件数専用の軽い判定を新設するのは二重実装＝今回の事故の再生産のため禁止）。
            Set<Long> visibleTeamScheduleIds = contentVisibilityChecker.filterAccessible(
                    ReferenceType.SCHEDULE,
                    teamSchedules.stream().map(ScheduleEntity::getId).toList(),
                    userId);
            teamSchedules.stream()
                    .filter(s -> visibleTeamScheduleIds.contains(s.getId()))
                    .forEach(schedules::add);
        }

        long eventsToday = 0;
        long eventsThisWeek = 0;
        long eventsThisMonth = 0;
        for (ScheduleEntity s : schedules) {
            LocalDateTime startAt = s.getStartAt();
            if (startAt == null) {
                continue;
            }
            // monthEnd までを取得済みなので全件が month に含まれる（境界含む）。
            eventsThisMonth++;
            if (!startAt.isAfter(weekEnd)) {
                eventsThisWeek++;
            }
            if (!startAt.isAfter(todayEnd)) {
                eventsToday++;
            }
        }
        return Map.of(
                "events_today", eventsToday,
                "events_this_week", eventsThisWeek,
                "events_this_month", eventsThisMonth
        );
    }

    /**
     * 個人TODOウィジェット用データを取得する。
     * 自分がアサインされた未完了TODOの一覧と期限切れ件数を返す。
     *
     * @param userId ユーザーID
     * @return items / overdue_count / total_incomplete を含む Map
     */
    public Map<String, Object> getPersonalTodos(Long userId) {
        List<TodoEntity> myTodos = todoRepository.findMyTodos(userId);
        List<TodoEntity> incompleteTodos = myTodos.stream()
                .filter(t -> t.getStatus() == TodoStatus.OPEN || t.getStatus() == TodoStatus.IN_PROGRESS)
                .toList();
        long overdueCount = incompleteTodos.stream()
                .filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(LocalDate.now(TimezoneContextHolder.get())))
                .count();
        List<Map<String, Object>> todoItems = incompleteTodos.stream()
                .limit(DASHBOARD_ITEM_LIMIT)
                .map(this::toTodoMap)
                .toList();
        return Map.of("items", todoItems, "overdue_count", overdueCount, "total_incomplete", (long) incompleteTodos.size());
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

        // F02.2.1: 閲覧者ロール解決と可視性マップ取得（管理者バイパスにも対応）
        ViewerRole viewerRole = roleResolver.resolveViewerRole(userId, "TEAM", teamId);
        Map<WidgetKey, MinRole> visibilityMap = widgetVisibilityResolver.resolve("TEAM", teamId);

        List<WidgetSettingResponse> widgetSettings = widgetService.getWidgetSettings(userId, ScopeType.TEAM, teamId, isAdmin);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime periodStart = resolvePeriodStart(now, statsPeriod);

        // チームお知らせ: チームスコープの通知最新5件
        // (通知はユーザー単位のためチーム共有お知らせはスケジュール等で代替)
        // ここではチームのスケジュールを今後7日間取得
        List<ScheduleEntity> teamUpcoming = scheduleRepository
                .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(teamId, now, now.plusDays(7));
        // F00 認可基盤連携（CMP-017b 第五隊）: チーム所属だけで取得しており min_view_role
        // 等の可視性判定を通していなかった（title/location が丸見え）。
        Set<Long> visibleTeamUpcomingIds = teamUpcoming.isEmpty()
                ? Set.of()
                : contentVisibilityChecker.filterAccessible(
                        ReferenceType.SCHEDULE,
                        teamUpcoming.stream().map(ScheduleEntity::getId).toList(),
                        userId);
        List<Map<String, Object>> teamUpcomingItems = teamUpcoming.stream()
                .filter(s -> visibleTeamUpcomingIds.contains(s.getId()))
                .limit(10)
                .map(this::toScheduleMap)
                .toList();

        // チームTODO: チームスコープの未完了TODO
        Page<TodoEntity> teamTodos = todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                com.mannschaft.app.todo.TodoScopeType.TEAM, teamId, PageRequest.of(0, 100));
        List<TodoEntity> incompleteTeamTodos = teamTodos.getContent().stream()
                .filter(t -> t.getStatus() == TodoStatus.OPEN || t.getStatus() == TodoStatus.IN_PROGRESS)
                .toList();
        long teamOverdue = incompleteTeamTodos.stream()
                .filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(LocalDate.now(TimezoneContextHolder.get())))
                .count();
        List<Map<String, Object>> teamTodoItems = incompleteTeamTodos.stream()
                .limit(DASHBOARD_ITEM_LIMIT)
                .map(this::toTodoMap)
                .toList();

        // チームアクティビティ統計
        List<TimelinePostEntity> teamPosts = timelinePostRepository
                .findFeedByScopeType(PostScopeType.TEAM, teamId, PageRequest.of(0, 100));
        long postsThisWeek = teamPosts.stream()
                .filter(p -> p.getCreatedAt() != null && p.getCreatedAt().isAfter(periodStart))
                .count();
        List<ScheduleEntity> teamPeriodSchedules = scheduleRepository
                .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(teamId, periodStart, now);
        Set<Long> visibleTeamPeriodIds = teamPeriodSchedules.isEmpty()
                ? Set.of()
                : contentVisibilityChecker.filterAccessible(
                        ReferenceType.SCHEDULE,
                        teamPeriodSchedules.stream().map(ScheduleEntity::getId).toList(),
                        userId);
        long eventsThisWeek = teamPeriodSchedules.stream()
                .filter(s -> visibleTeamPeriodIds.contains(s.getId()))
                .count();
        long totalMembers = userRoleRepository.countByTeamId(teamId);

        // チーム最新投稿
        List<TimelinePostEntity> latestPosts = timelinePostRepository
                .findFeedByScopeType(PostScopeType.TEAM, teamId, PageRequest.of(0, DASHBOARD_ITEM_LIMIT));

        // チーム未読スレッド
        Page<com.mannschaft.app.bulletin.entity.BulletinThreadEntity> teamThreads =
                bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                        com.mannschaft.app.bulletin.ScopeType.TEAM, teamId, PageRequest.of(0, 100));
        long unreadBulletinCount = 0;
        // dashboard-scope-panel-content 第二陣: 直近スレッド一覧（クエリ順の先頭3件）を件数集計と
        // 同一ループで構築する（各スレッドの is_read は existsByThreadIdAndUserId で判定）。
        List<Map<String, Object>> teamBulletinThreads = new ArrayList<>();
        for (var thread : teamThreads.getContent()) {
            boolean read = bulletinReadStatusRepository.existsByThreadIdAndUserId(thread.getId(), userId);
            if (!read) {
                unreadBulletinCount++;
            }
            if (teamBulletinThreads.size() < DASHBOARD_ITEM_LIMIT_THREADS) {
                Map<String, Object> threadMap = new HashMap<>();
                threadMap.put("id", thread.getId());
                threadMap.put("title", thread.getTitle());
                threadMap.put("updated_at", thread.getUpdatedAt());
                threadMap.put("is_read", read);
                teamBulletinThreads.add(threadMap);
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

        // F02.8: チームスコープの告知フィードを取得
        Set<String> allowedVisibilities = resolveVisibilityParam(viewerRole);
        List<AnnouncementFeedEntity> teamAnnouncementFeeds = announcementFeedQueryRepository
                .findByScope(AnnouncementScopeType.TEAM, teamId, allowedVisibilities, null, 10);

        // F02.8: 親組織の告知フィードを取得（target_team_ids フィルタ付き）
        // CMP-027: user_roles ∪ memberships の在籍組織 ID（素メンバー/応援者を取りこぼさない）
        List<Long> feedOrgIds = userRoleRepository.findOrganizationIdsByUserId(userId);
        // 多重 org ロール行に対する防御的な feedId 重複排除（findOrganizationIdsByUserId は既に DISTINCT だが
        // インボックス（AnnouncementInboxAdapter の feedById.putIfAbsent）と同等に feedId で先勝ち dedup する）。
        List<AnnouncementFeedEntity> orgAnnouncementFeeds = new ArrayList<>(feedOrgIds.stream()
                .flatMap(orgId -> announcementFeedQueryRepository
                        .findByOrgScopeForTeamDashboard(orgId, allowedVisibilities, 20).stream())
                .filter(feed -> isTargetedToTeam(feed, teamId))
                .collect(java.util.stream.Collectors.toMap(
                        AnnouncementFeedEntity::getId,
                        feed -> feed,
                        (existing, duplicate) -> existing,
                        java.util.LinkedHashMap::new))
                .values());

        // 結合して createdAt 降順で上位5件
        List<Map<String, Object>> teamNoticeItems = java.util.stream.Stream.concat(
                        teamAnnouncementFeeds.stream(), orgAnnouncementFeeds.stream())
                .sorted(java.util.Comparator.comparing(AnnouncementFeedEntity::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .limit(DASHBOARD_ITEM_LIMIT)
                .map(this::toAnnouncementFeedMap)
                .toList();

        // F02.2.1: 各ウィジェットを viewerRole.isAtLeast(min_role) で判定し、不可視は null にする
        // 管理者（DEPUTY_ADMIN/ADMIN/SYSTEM_ADMIN）は全ウィジェットをバイパスして閲覧可
        Map<String, Object> teamTodoData = Map.of("items", teamTodoItems, "overdue_count", teamOverdue,
                "total_incomplete", (long) incompleteTeamTodos.size());
        Map<String, Object> teamActivityData = Map.of(
                "posts_this_week", postsThisWeek,
                "events_this_week", eventsThisWeek,
                "active_members_this_week", 0,
                "total_members", totalMembers);
        Map<String, Object> teamUnreadData = Map.of(
                "bulletin_count", unreadBulletinCount,
                "chat_count", unreadChatCount,
                "bulletin_threads", teamBulletinThreads);
        Map<String, Object> teamAttendanceData = Map.of("attending", 0, "absent", 0, "pending", 0);

        // F22.1 第二波: 厳選ウィジェットサマリ（④ブログ/⑤チャット/⑥カレンダー/⑧要対応）を並行取得し、
        // SWIPE_* キーの可視性（min_role=MEMBER 既定 + DB 上書き）でフィルタする。
        Map<SwipeWidgetKey, MinRole> swipeVisibility =
                swipeWidgetVisibilityResolver.resolve("TEAM", teamId);
        final java.time.ZoneId userZone = TimezoneContextHolder.get();

        CompletableFuture<List<Map<String, Object>>> blogFuture =
                CompletableFuture.supplyAsync(() -> scopeWidgetSummaryService.buildLatestBlogPosts("TEAM", teamId));
        CompletableFuture<Map<String, Object>> chatFuture =
                CompletableFuture.supplyAsync(() -> scopeWidgetSummaryService.buildChatSummary("TEAM", teamId, userId));
        CompletableFuture<Map<String, Object>> calendarFuture =
                CompletableFuture.supplyAsync(() -> scopeWidgetSummaryService.buildCalendarSummary("TEAM", teamId, userZone, userId));
        CompletableFuture<ActionRequiredSummaryResponse> actionFuture =
                CompletableFuture.supplyAsync(() -> scopeActionRequiredFacade.getActionRequired(userId, "TEAM", teamId));

        List<Map<String, Object>> teamLatestBlogPosts = swipeWidgetVisibilityResolver.filterIfVisible(
                viewerRole, swipeVisibility, SwipeWidgetKey.SWIPE_TEAM_BLOG, joinSwipe(blogFuture));
        Map<String, Object> teamChatSummary = swipeWidgetVisibilityResolver.filterIfVisible(
                viewerRole, swipeVisibility, SwipeWidgetKey.SWIPE_TEAM_CHAT, joinSwipe(chatFuture));
        Map<String, Object> teamCalendarSummary = swipeWidgetVisibilityResolver.filterIfVisible(
                viewerRole, swipeVisibility, SwipeWidgetKey.SWIPE_TEAM_CALENDAR, joinSwipe(calendarFuture));
        ActionRequiredSummaryResponse teamActionRequired = swipeWidgetVisibilityResolver.filterIfVisible(
                viewerRole, swipeVisibility, SwipeWidgetKey.SWIPE_TEAM_ACTION_REQUIRED, joinSwipe(actionFuture));

        return TeamDashboardResponse.builder()
                .teamNotices(filterIfVisible(viewerRole, visibilityMap, WidgetKey.TEAM_NOTICES, teamNoticeItems))
                .teamUpcomingEvents(filterIfVisible(
                        viewerRole, visibilityMap, WidgetKey.TEAM_UPCOMING_EVENTS, teamUpcomingItems))
                .teamTodo(filterIfVisible(viewerRole, visibilityMap, WidgetKey.TEAM_TODO, teamTodoData))
                .teamProjectProgress(filterIfVisible(
                        viewerRole, visibilityMap, WidgetKey.TEAM_PROJECT_PROGRESS, List.of()))
                .teamActivity(filterIfVisible(
                        viewerRole, visibilityMap, WidgetKey.TEAM_ACTIVITY, teamActivityData))
                .teamLatestPosts(filterIfVisible(
                        viewerRole, visibilityMap, WidgetKey.TEAM_LATEST_POSTS,
                        latestPosts.stream().map(this::toTimelinePostMap).toList()))
                .teamUnreadThreads(filterIfVisible(
                        viewerRole, visibilityMap, WidgetKey.TEAM_UNREAD_THREADS, teamUnreadData))
                .teamMemberAttendance(filterIfVisible(
                        viewerRole, visibilityMap, WidgetKey.TEAM_MEMBER_ATTENDANCE, teamAttendanceData))
                // ADMIN 限定ウィジェットは F02.2 既存ルール（isAdmin）でフィルタ。本機能の対象外
                .teamBilling(isAdmin ? Map.of() : null)
                .teamPageViews(null)
                .widgetSettings(widgetSettings)
                .platformAnnouncements(announcementItems)
                .viewerRole(viewerRole)
                .widgetVisibility(buildVisibilityList(viewerRole, visibilityMap))
                // F22.1 第二波 追加フィールド
                .teamLatestBlogPosts(teamLatestBlogPosts)
                .teamChatSummary(teamChatSummary)
                .teamCalendarSummary(teamCalendarSummary)
                .teamActionRequired(teamActionRequired)
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

        // F02.2.1: 閲覧者ロール解決と可視性マップ取得
        ViewerRole viewerRole = roleResolver.resolveViewerRole(userId, "ORGANIZATION", orgId);
        Map<WidgetKey, MinRole> visibilityMap = widgetVisibilityResolver.resolve("ORGANIZATION", orgId);

        List<WidgetSettingResponse> widgetSettings = widgetService.getWidgetSettings(userId, ScopeType.ORGANIZATION, orgId, isAdmin);

        // 組織TODO: 組織スコープの未完了TODO
        Page<TodoEntity> orgTodos = todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                com.mannschaft.app.todo.TodoScopeType.ORGANIZATION, orgId, PageRequest.of(0, 100));
        List<TodoEntity> incompleteOrgTodos = orgTodos.getContent().stream()
                .filter(t -> t.getStatus() == TodoStatus.OPEN || t.getStatus() == TodoStatus.IN_PROGRESS)
                .toList();
        long orgOverdue = incompleteOrgTodos.stream()
                .filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(LocalDate.now(TimezoneContextHolder.get())))
                .count();
        List<Map<String, Object>> orgTodoItems = incompleteOrgTodos.stream()
                .limit(DASHBOARD_ITEM_LIMIT)
                .map(this::toTodoMap)
                .toList();

        // 組織統計
        long totalMembers = userRoleRepository.countByOrganizationId(orgId);

        // F02.8: 組織スコープの告知フィードを取得してお知らせウィジェットに連携
        LocalDateTime now = LocalDateTime.now();
        Set<String> orgAllowedVisibilities = resolveVisibilityParam(viewerRole);
        List<AnnouncementFeedEntity> orgAnnouncementFeeds = announcementFeedQueryRepository
                .findByScope(AnnouncementScopeType.ORGANIZATION, orgId, orgAllowedVisibilities, null, 10);
        List<Map<String, Object>> orgNoticeItems = orgAnnouncementFeeds.stream()
                .limit(DASHBOARD_ITEM_LIMIT)
                .map(this::toAnnouncementFeedMap)
                .toList();

        // platform_announcements
        List<PlatformAnnouncementEntity> announcements = platformAnnouncementRepository
                .findActiveAnnouncements(LocalDateTime.now());
        List<Map<String, Object>> announcementItems = announcements.stream()
                .limit(DASHBOARD_ITEM_LIMIT)
                .map(this::toAnnouncementMap)
                .toList();

        // F02.2.1: 各ウィジェットを viewerRole.isAtLeast(min_role) で判定し、不可視は null にする
        Map<String, Object> orgTodoData = Map.of("items", orgTodoItems, "overdue_count", orgOverdue,
                "total_incomplete", (long) incompleteOrgTodos.size());
        Map<String, Object> orgStatsData = Map.of(
                "total_teams", 0,
                "total_members", totalMembers,
                "new_members_this_month", 0,
                "active_rate", 0.0);

        // F22.1 第二波: 組織スコープの厳選ウィジェットを並行取得。
        // ①②③（今後の予定/タイムライン/掲示板）は F02.2 組織未実装のため新規実装、④⑤⑥⑧も新設。
        // SWIPE_* キーの可視性（min_role=MEMBER 既定 + DB 上書き）でフィルタする。
        Map<SwipeWidgetKey, MinRole> swipeVisibility =
                swipeWidgetVisibilityResolver.resolve("ORGANIZATION", orgId);
        final java.time.ZoneId userZone = TimezoneContextHolder.get();

        CompletableFuture<List<Map<String, Object>>> upcomingFuture =
                CompletableFuture.supplyAsync(() -> scopeWidgetSummaryService.buildOrgUpcomingEvents(orgId, userId));
        CompletableFuture<List<Map<String, Object>>> postsFuture =
                CompletableFuture.supplyAsync(() -> scopeWidgetSummaryService.buildOrgLatestPosts(orgId));
        CompletableFuture<Map<String, Object>> unreadFuture =
                CompletableFuture.supplyAsync(() -> scopeWidgetSummaryService.buildOrgUnreadThreads(orgId, userId));
        CompletableFuture<List<Map<String, Object>>> blogFuture =
                CompletableFuture.supplyAsync(() -> scopeWidgetSummaryService.buildLatestBlogPosts("ORGANIZATION", orgId));
        CompletableFuture<Map<String, Object>> chatFuture =
                CompletableFuture.supplyAsync(() -> scopeWidgetSummaryService.buildChatSummary("ORGANIZATION", orgId, userId));
        CompletableFuture<Map<String, Object>> calendarFuture =
                CompletableFuture.supplyAsync(() -> scopeWidgetSummaryService.buildCalendarSummary("ORGANIZATION", orgId, userZone, userId));
        CompletableFuture<ActionRequiredSummaryResponse> actionFuture =
                CompletableFuture.supplyAsync(() -> scopeActionRequiredFacade.getActionRequired(userId, "ORGANIZATION", orgId));

        List<Map<String, Object>> orgUpcomingEvents = swipeWidgetVisibilityResolver.filterIfVisible(
                viewerRole, swipeVisibility, SwipeWidgetKey.SWIPE_ORG_UPCOMING, joinSwipe(upcomingFuture));
        List<Map<String, Object>> orgLatestPosts = swipeWidgetVisibilityResolver.filterIfVisible(
                viewerRole, swipeVisibility, SwipeWidgetKey.SWIPE_ORG_TIMELINE, joinSwipe(postsFuture));
        Map<String, Object> orgUnreadThreads = swipeWidgetVisibilityResolver.filterIfVisible(
                viewerRole, swipeVisibility, SwipeWidgetKey.SWIPE_ORG_BULLETIN, joinSwipe(unreadFuture));
        List<Map<String, Object>> orgLatestBlogPosts = swipeWidgetVisibilityResolver.filterIfVisible(
                viewerRole, swipeVisibility, SwipeWidgetKey.SWIPE_ORG_BLOG, joinSwipe(blogFuture));
        Map<String, Object> orgChatSummary = swipeWidgetVisibilityResolver.filterIfVisible(
                viewerRole, swipeVisibility, SwipeWidgetKey.SWIPE_ORG_CHAT, joinSwipe(chatFuture));
        Map<String, Object> orgCalendarSummary = swipeWidgetVisibilityResolver.filterIfVisible(
                viewerRole, swipeVisibility, SwipeWidgetKey.SWIPE_ORG_CALENDAR, joinSwipe(calendarFuture));
        ActionRequiredSummaryResponse orgActionRequired = swipeWidgetVisibilityResolver.filterIfVisible(
                viewerRole, swipeVisibility, SwipeWidgetKey.SWIPE_ORG_ACTION_REQUIRED, joinSwipe(actionFuture));

        return OrgDashboardResponse.builder()
                .orgTeamList(filterIfVisible(viewerRole, visibilityMap, WidgetKey.ORG_TEAM_LIST, List.of()))
                .orgNotices(filterIfVisible(viewerRole, visibilityMap, WidgetKey.ORG_NOTICES, orgNoticeItems))
                .orgTodo(filterIfVisible(viewerRole, visibilityMap, WidgetKey.ORG_TODO, orgTodoData))
                .orgProjectProgress(filterIfVisible(
                        viewerRole, visibilityMap, WidgetKey.ORG_PROJECT_PROGRESS, List.of()))
                .orgStats(filterIfVisible(viewerRole, visibilityMap, WidgetKey.ORG_STATS, orgStatsData))
                // ADMIN 限定ウィジェットは F02.2 既存ルール（isAdmin）でフィルタ。本機能の対象外
                .orgBilling(isAdmin ? Map.of() : null)
                .widgetSettings(widgetSettings)
                .platformAnnouncements(announcementItems)
                .viewerRole(viewerRole)
                .widgetVisibility(buildVisibilityList(viewerRole, visibilityMap))
                // F22.1 第二波 追加フィールド（①②③ 組織新設 + ④⑤⑥⑧）
                .orgUpcomingEvents(orgUpcomingEvents)
                .orgLatestPosts(orgLatestPosts)
                .orgUnreadThreads(orgUnreadThreads)
                .orgLatestBlogPosts(orgLatestBlogPosts)
                .orgChatSummary(orgChatSummary)
                .orgCalendarSummary(orgCalendarSummary)
                .orgActionRequired(orgActionRequired)
                .build();
    }

    /**
     * F22.1 第二波: SWIPE サマリの並行取得結果を取り出す。
     *
     * <p>各サマリは独立した {@link CompletableFuture} で取得され、本メソッドで結果を join する。
     * 取得中の例外は握り潰さずログに出し、当該ウィジェットのみ null（＝レスポンスから省略）に縮退する
     * （対処療法禁止: 1 ウィジェットの一時障害でダッシュボード全体を 500 にしない）。</p>
     */
    private <T> T joinSwipe(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (RuntimeException ex) {
            log.warn("DashboardService: SWIPE ウィジェットサマリの取得に失敗。当該ウィジェットを省略します。", ex);
            return null;
        }
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
        // CMP-027: user_roles ∪ memberships の在籍スコープ数（素メンバー/応援者を取りこぼさない）
        List<Long> teamIds = userRoleRepository.findTeamIdsByUserId(userId);
        List<Long> orgIds = userRoleRepository.findOrganizationIdsByUserId(userId);
        int totalScopes = teamIds.size() + orgIds.size();
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
        map.put("parent_id", entity.getParentId());
        map.put("depth", entity.getDepth());
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
     * F02.2.1: ウィジェットが閲覧者ロールから可視であればデータをそのまま、不可視なら null を返す。
     *
     * <p>{@link ViewerRole#isAdminOrAbove()} が true の場合は可視性チェックをバイパスして
     * 常にデータを返す（管理者は全ウィジェットを閲覧できる）。
     * {@code visibilityMap} に該当キーが含まれない場合（管理対象外ウィジェット）は
     * 既存挙動を保持するため、データをそのまま返す。</p>
     */
    private static <T> T filterIfVisible(ViewerRole viewerRole,
                                         Map<WidgetKey, MinRole> visibilityMap,
                                         WidgetKey key,
                                         T data) {
        if (viewerRole.isAdminOrAbove()) {
            return data;
        }
        MinRole minRole = visibilityMap.get(key);
        if (minRole == null) {
            // 管理対象外ウィジェットは本機能のフィルタ対象外（既存挙動を維持）
            return data;
        }
        return viewerRole.isAtLeast(minRole) ? data : null;
    }

    /**
     * F02.2.1: レスポンス用の可視性配列を構築する。
     *
     * <p>各要素は {@link WidgetVisibilityRowDto} で {@code widget_key / min_role / is_visible} を持つ。
     * 管理者（ADMIN/DEPUTY_ADMIN/SYSTEM_ADMIN）の場合は全ウィジェットが {@code is_visible=true}。</p>
     */
    private static List<WidgetVisibilityRowDto> buildVisibilityList(ViewerRole viewerRole,
                                                                    Map<WidgetKey, MinRole> visibilityMap) {
        List<WidgetVisibilityRowDto> result = new ArrayList<>(visibilityMap.size());
        boolean adminBypass = viewerRole.isAdminOrAbove();
        for (Map.Entry<WidgetKey, MinRole> entry : visibilityMap.entrySet()) {
            result.add(WidgetVisibilityRowDto.builder()
                    .widgetKey(entry.getKey().name())
                    .minRole(entry.getValue())
                    .isVisible(adminBypass || viewerRole.isAtLeast(entry.getValue()))
                    .build());
        }
        return result;
    }

    /**
     * ダッシュボードウィジェット用: 有効なプラットフォームお知らせをFE期待の形式で返す。
     */
    public List<Map<String, Object>> getActivePlatformAnnouncements() {
        List<PlatformAnnouncementEntity> announcements =
                platformAnnouncementRepository.findActiveAnnouncements(LocalDateTime.now());
        return announcements.stream()
                .map(a -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", a.getId());
                    map.put("title", a.getTitle());
                    map.put("content", a.getBody());
                    map.put("severity", toSeverity(a.getPriority()));
                    map.put("isPinned", a.getIsPinned());
                    map.put("publishedAt", a.getPublishedAt());
                    return map;
                })
                .toList();
    }

    /**
     * priority 値を FE 期待の severity 値に変換する。
     */
    private String toSeverity(String priority) {
        if (priority == null) return "INFO";
        return switch (priority.toUpperCase()) {
            case "HIGH" -> "WARNING";
            case "URGENT" -> "URGENT";
            default -> "INFO";
        };
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

    /**
     * F02.8: ViewerRole から、その閲覧者が閲覧できる visibility 集合を解決する（可視性漏洩根治）。
     *
     * <p>{@link AnnouncementVisibility#allowedFor(String)} の正準マッピングに委譲する:
     * MEMBER 以上は {@code {PUBLIC, SUPPORTERS_AND_ABOVE, MEMBERS_ONLY}}（取りこぼしなし）、
     * SUPPORTER は {@code {PUBLIC, SUPPORTERS_AND_ABOVE}}（MEMBERS_ONLY を露出させない）、
     * PUBLIC / null は {@code {PUBLIC}}。従来の単一文字列方式が抱えていた漏洩・取りこぼしを是正する。</p>
     */
    private Set<String> resolveVisibilityParam(ViewerRole viewerRole) {
        return AnnouncementVisibility.allowedFor(viewerRole == null ? null : viewerRole.name());
    }

    /**
     * F02.8: 組織告知フィードがチームに向けられているか判定する。
     *
     * <p>target_team_ids IS NULL（全チーム対象）または teamId を含む場合に true を返す。</p>
     */
    private boolean isTargetedToTeam(AnnouncementFeedEntity feed, Long teamId) {
        String targetTeamIds = feed.getTargetTeamIds();
        if (targetTeamIds == null || targetTeamIds.isBlank() || "null".equals(targetTeamIds)) {
            return true; // 全チーム対象
        }
        // JSON 配列文字列から teamId が含まれるか判定
        // "[3,5,12]" から "3" を探す（前後の区切り文字を考慮）
        String needle = teamId.toString();
        return targetTeamIds.contains("\"" + needle + "\"")
                || targetTeamIds.matches(".*[\\[,]" + needle + "[,\\]].*");
    }

    /**
     * F02.8: AnnouncementFeedEntity をダッシュボード表示用 Map に変換する。
     */
    private Map<String, Object> toAnnouncementFeedMap(AnnouncementFeedEntity feed) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", feed.getId());
        map.put("source_type", feed.getSourceType() != null ? feed.getSourceType().name() : null);
        map.put("title_cache", feed.getTitleCache());
        map.put("excerpt_cache", feed.getExcerptCache());
        map.put("priority", feed.getPriority());
        map.put("is_pinned", feed.getIsPinned());
        map.put("expires_at", feed.getExpiresAt());
        map.put("created_at", feed.getCreatedAt());
        map.put("target_team_ids", feed.getTargetTeamIds());
        return map;
    }
}
