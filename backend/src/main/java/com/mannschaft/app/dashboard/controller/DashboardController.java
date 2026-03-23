package com.mannschaft.app.dashboard.controller;

import com.mannschaft.app.admin.entity.PlatformAnnouncementEntity;
import com.mannschaft.app.admin.repository.PlatformAnnouncementRepository;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.dto.ActivityFeedResponse;
import com.mannschaft.app.dashboard.dto.OrgDashboardResponse;
import com.mannschaft.app.dashboard.dto.PersonalDashboardResponse;
import com.mannschaft.app.dashboard.dto.TeamDashboardResponse;
import com.mannschaft.app.dashboard.dto.UpdateWidgetSettingsRequest;
import com.mannschaft.app.dashboard.dto.WidgetSettingResponse;
import com.mannschaft.app.dashboard.service.ActivityFeedService;
import com.mannschaft.app.dashboard.service.DashboardService;
import com.mannschaft.app.dashboard.service.DashboardWidgetService;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ダッシュボードコントローラー。
 * 個人・チーム・組織ダッシュボードの一括取得、個別ウィジェットデータ取得、
 * ウィジェット設定のCRUDを提供する。
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "ダッシュボード")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final DashboardWidgetService widgetService;
    private final ActivityFeedService activityFeedService;
    private final AccessControlService accessControlService;
    private final NotificationRepository notificationRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRoleRepository userRoleRepository;
    private final BulletinThreadRepository bulletinThreadRepository;
    private final BulletinReadStatusRepository bulletinReadStatusRepository;
    private final ChatChannelMemberRepository chatChannelMemberRepository;

    // ============================================
    // 個人ダッシュボード
    // ============================================

    /**
     * 個人ダッシュボードの全ウィジェットデータを一括取得する。
     */
    @GetMapping
    @Operation(summary = "個人ダッシュボード一括取得",
            description = "ログインユーザーの個人ダッシュボードを取得する。priority=CRITICALで第1段階ウィジェットのみ高速返却")
    public ResponseEntity<ApiResponse<PersonalDashboardResponse>> getPersonalDashboard(
            @Parameter(description = "取得優先度（CRITICAL / ALL）") @RequestParam(defaultValue = "ALL") String priority) {
        Long userId = SecurityUtils.getCurrentUserId();
        PersonalDashboardResponse response = dashboardService.getPersonalDashboard(userId, priority);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * お知らせ欄の詳細一覧（ページネーション対応）。
     */
    @GetMapping("/notices")
    @Operation(summary = "お知らせ一覧", description = "個人ダッシュボードのお知らせ欄（カーソルページネーション対応）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getNotices(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(required = false) Boolean isRead) {
        Long userId = SecurityUtils.getCurrentUserId();
        int resolvedLimit = Math.min(limit, 50);

        Page<NotificationEntity> page;
        if (Boolean.FALSE.equals(isRead)) {
            page = notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(
                    userId, PageRequest.of(0, resolvedLimit));
        } else {
            page = notificationRepository.findByUserIdOrderByCreatedAtDesc(
                    userId, PageRequest.of(0, resolvedLimit));
        }
        long totalCount = page.getTotalElements();
        boolean hasNext = page.hasNext();

        List<Map<String, Object>> items = page.getContent().stream()
                .map(n -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", n.getId());
                    map.put("type", n.getNotificationType());
                    map.put("title", n.getTitle());
                    map.put("body", n.getBody());
                    map.put("is_read", n.getIsRead());
                    map.put("action_url", n.getActionUrl());
                    map.put("created_at", n.getCreatedAt());
                    return map;
                })
                .toList();

        long nextCursor = items.isEmpty() ? 0 : page.getContent().getLast().getId();

        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "items", items,
                "meta", Map.of("next_cursor", nextCursor, "limit", resolvedLimit, "total_count", totalCount, "has_next", hasNext)
        )));
    }

    /**
     * 自分の投稿一覧（ページネーション対応）。
     */
    @GetMapping("/my-posts")
    @Operation(summary = "自分の投稿一覧", description = "自分のタイムライン投稿一覧（カーソルページネーション対応）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyPosts(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "10") Integer limit) {
        Long userId = SecurityUtils.getCurrentUserId();
        int resolvedLimit = Math.min(limit, 50);

        List<TimelinePostEntity> posts = timelinePostRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, resolvedLimit));

        List<Map<String, Object>> items = posts.stream()
                .map(p -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", p.getId());
                    map.put("content", p.getContent());
                    map.put("created_at", p.getCreatedAt());
                    map.put("reaction_count", p.getReactionCount());
                    map.put("reply_count", p.getReplyCount());
                    return map;
                })
                .toList();

        long nextCursor = items.isEmpty() ? 0 : posts.getLast().getId();

        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "items", items,
                "meta", Map.of("next_cursor", nextCursor, "limit", resolvedLimit, "total_count", items.size(), "has_next", items.size() >= resolvedLimit)
        )));
    }

    /**
     * 直近イベント + 出欠状況。
     */
    @GetMapping("/upcoming-events")
    @Operation(summary = "直近イベント", description = "今後N日間のイベント + 出欠状況を横断取得")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getUpcomingEvents(
            @RequestParam(defaultValue = "7") Integer days) {
        Long userId = SecurityUtils.getCurrentUserId();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime until = now.plusDays(days);

        // 個人スケジュール
        List<ScheduleEntity> personalSchedules = scheduleRepository
                .findByUserIdAndStartAtBetweenOrderByStartAtAsc(userId, now, until);
        // 所属チームのスケジュール
        List<UserRoleEntity> teamRoles = userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId);
        List<ScheduleEntity> teamSchedules = teamRoles.stream()
                .flatMap(role -> scheduleRepository
                        .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(role.getTeamId(), now, until).stream())
                .toList();

        List<Map<String, Object>> items = new ArrayList<>();
        personalSchedules.stream().map(this::toScheduleMap).forEach(items::add);
        teamSchedules.stream().map(this::toScheduleMap).forEach(items::add);
        items.sort((a, b) -> ((LocalDateTime) a.get("start_at")).compareTo((LocalDateTime) b.get("start_at")));

        return ResponseEntity.ok(ApiResponse.of(items));
    }

    /**
     * 未読スレッド一覧。
     */
    @GetMapping("/unread-threads")
    @Operation(summary = "未読スレッド一覧", description = "未読の掲示板スレッド + チャットチャネルを横断取得")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUnreadThreads(
            @RequestParam(defaultValue = "10") Integer limit) {
        Long userId = SecurityUtils.getCurrentUserId();

        // 掲示板: 所属チームのスレッドで未読のもの
        List<UserRoleEntity> teamRoles = userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId);
        long totalUnreadBulletin = 0;
        for (UserRoleEntity role : teamRoles) {
            Page<com.mannschaft.app.bulletin.entity.BulletinThreadEntity> threads =
                    bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                            com.mannschaft.app.bulletin.ScopeType.TEAM, role.getTeamId(), PageRequest.of(0, 100));
            for (var thread : threads.getContent()) {
                if (!bulletinReadStatusRepository.existsByThreadIdAndUserId(thread.getId(), userId)) {
                    totalUnreadBulletin++;
                }
            }
        }

        // チャット: 未読数合計
        List<ChatChannelMemberEntity> chatMemberships = chatChannelMemberRepository.findByUserId(userId);
        long totalUnreadChat = chatMemberships.stream()
                .mapToInt(ChatChannelMemberEntity::getUnreadCount)
                .sum();

        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "bulletin_threads", List.of(),
                "chat_channels", List.of(),
                "total_unread_bulletin", totalUnreadBulletin,
                "total_unread_chat", totalUnreadChat
        )));
    }

    /**
     * 最近のアクティビティ。
     */
    @GetMapping("/activity")
    @Operation(summary = "最近のアクティビティ", description = "所属チーム/組織を横断した最近の活動フィード")
    public ResponseEntity<ApiResponse<List<ActivityFeedResponse>>> getActivity(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "10") Integer limit) {
        Long userId = SecurityUtils.getCurrentUserId();
        // 所属チームIDを取得してスコープとする
        List<UserRoleEntity> teamRoles = userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId);
        List<Long> scopeIds = teamRoles.stream().map(UserRoleEntity::getTeamId).toList();
        List<ActivityFeedResponse> response = activityFeedService.getActivityFeed(userId, cursor, limit, scopeIds);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 個人カレンダーサマリー。
     */
    @GetMapping("/calendar")
    @Operation(summary = "個人カレンダーサマリー", description = "個人スケジュール + 所属チームの公開イベントを集約")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCalendar(
            @RequestParam(required = false) String month) {
        Long userId = SecurityUtils.getCurrentUserId();

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = LocalDate.now().atTime(LocalTime.MAX);
        LocalDateTime weekEnd = todayStart.plusDays(7);
        LocalDateTime monthEnd = todayStart.plusMonths(1);

        long eventsToday = scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(userId, todayStart, todayEnd).size();
        long eventsThisWeek = scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(userId, todayStart, weekEnd).size();
        long eventsThisMonth = scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(userId, todayStart, monthEnd).size();

        // チーム公開イベントも加算
        List<UserRoleEntity> teamRoles = userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId);
        for (UserRoleEntity role : teamRoles) {
            eventsToday += scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(role.getTeamId(), todayStart, todayEnd).size();
            eventsThisWeek += scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(role.getTeamId(), todayStart, weekEnd).size();
            eventsThisMonth += scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(role.getTeamId(), todayStart, monthEnd).size();
        }

        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "events_today", eventsToday,
                "events_this_week", eventsThisWeek,
                "events_this_month", eventsThisMonth,
                "days_with_events", List.of()
        )));
    }

    /**
     * パフォーマンスサマリー。
     */
    @GetMapping("/performance")
    @Operation(summary = "パフォーマンスサマリー", description = "所属チーム/組織ごとの個人パフォーマンス概要")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPerformance() {
        // 将来実装: パフォーマンス管理モジュールが完成後に連携
        return ResponseEntity.ok(ApiResponse.of(Map.of("teams", List.of())));
    }

    /**
     * チャットハブデータ取得。
     */
    @GetMapping("/chat-hub")
    @Operation(summary = "チャットハブ", description = "チーム別自動グルーピング + カスタムフォルダ別のチャット一覧")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getChatHub(
            @RequestParam(defaultValue = "false") Boolean allTeams) {
        // 将来実装: チャットハブモジュールが完成後に連携
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "team_groups", List.of(),
                "custom_folders", List.of(),
                "uncategorized_dms", List.of(),
                "summary", Map.of("total_unread", 0, "total_dms", 0, "total_contacts", 0)
        )));
    }

    // ============================================
    // チーム・組織ダッシュボード
    // ============================================

    /**
     * チームダッシュボード一括取得。
     */
    @GetMapping("/team/{teamId}")
    @Operation(summary = "チームダッシュボード一括取得", description = "チーム全体の活動状況・お知らせ・イベント等を一括取得")
    public ResponseEntity<ApiResponse<TeamDashboardResponse>> getTeamDashboard(
            @PathVariable Long teamId,
            @Parameter(description = "統計期間（TODAY / WEEK / MONTH）") @RequestParam(defaultValue = "WEEK") String statsPeriod) {
        Long userId = SecurityUtils.getCurrentUserId();
        TeamDashboardResponse response = dashboardService.getTeamDashboard(userId, teamId, statsPeriod);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 組織ダッシュボード一括取得。
     */
    @GetMapping("/organization/{orgId}")
    @Operation(summary = "組織ダッシュボード一括取得", description = "傘下チーム一覧・組織全体の統計等を一括取得")
    public ResponseEntity<ApiResponse<OrgDashboardResponse>> getOrgDashboard(
            @PathVariable Long orgId,
            @Parameter(description = "統計期間（TODAY / WEEK / MONTH）") @RequestParam(defaultValue = "WEEK") String statsPeriod) {
        Long userId = SecurityUtils.getCurrentUserId();
        OrgDashboardResponse response = dashboardService.getOrgDashboard(userId, orgId, statsPeriod);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ============================================
    // ウィジェット設定
    // ============================================

    /**
     * ウィジェット設定一覧を取得する。
     */
    @GetMapping("/widgets")
    @Operation(summary = "ウィジェット設定一覧", description = "指定スコープのウィジェット設定一覧を取得する")
    public ResponseEntity<ApiResponse<List<WidgetSettingResponse>>> getWidgetSettings(
            @RequestParam String scopeType,
            @RequestParam(required = false) Long scopeId) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScopeType parsed = widgetService.parseScopeType(scopeType);
        Long resolvedScopeId = widgetService.resolveScopeId(parsed, scopeId);
        boolean isAdmin = accessControlService.isAdminOrAbove(userId, resolvedScopeId, parsed.name());
        List<WidgetSettingResponse> response = widgetService.getWidgetSettings(userId, parsed, resolvedScopeId, isAdmin);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ウィジェット設定を一括更新する（UPSERT）。
     */
    @PutMapping("/widgets")
    @Operation(summary = "ウィジェット設定一括更新", description = "ウィジェットの表示/非表示・並び順を一括更新する")
    public ResponseEntity<ApiResponse<List<WidgetSettingResponse>>> updateWidgetSettings(
            @Valid @RequestBody UpdateWidgetSettingsRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<WidgetSettingResponse> response = widgetService.updateWidgetSettings(userId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ウィジェット設定をリセットする。
     */
    @DeleteMapping("/widgets")
    @Operation(summary = "ウィジェット設定リセット", description = "指定スコープの全設定を削除しデフォルトに復帰する")
    public ResponseEntity<Void> resetWidgetSettings(
            @RequestParam String scopeType,
            @RequestParam(required = false) Long scopeId) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScopeType parsed = widgetService.parseScopeType(scopeType);
        Long resolvedScopeId = widgetService.resolveScopeId(parsed, scopeId);
        widgetService.resetWidgetSettings(userId, parsed, resolvedScopeId);
        return ResponseEntity.noContent().build();
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
}
