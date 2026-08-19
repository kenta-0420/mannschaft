package com.mannschaft.app.dashboard.controller;

import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.dashboard.ScheduleFeedErrorCode;
import com.mannschaft.app.dashboard.dto.ActivityFeedPageResponse;
import com.mannschaft.app.dashboard.dto.ChatHubResponse;
import com.mannschaft.app.dashboard.dto.DashboardAnnouncementResponse;
import com.mannschaft.app.dashboard.dto.OrgDashboardResponse;
import com.mannschaft.app.dashboard.dto.PersonalDashboardResponse;
import com.mannschaft.app.dashboard.dto.TeamDashboardResponse;
import com.mannschaft.app.dashboard.dto.UpdateWidgetSettingsRequest;
import com.mannschaft.app.dashboard.dto.WidgetSettingResponse;
import com.mannschaft.app.dashboard.service.ActivityFeedService;
import com.mannschaft.app.dashboard.service.ChatHubService;
import com.mannschaft.app.dashboard.service.DashboardService;
import com.mannschaft.app.dashboard.service.DashboardWidgetService;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.service.TeamService;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final ChatHubService chatHubService;
    private final AccessControlService accessControlService;
    private final NotificationRepository notificationRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRoleRepository userRoleRepository;
    private final BulletinThreadRepository bulletinThreadRepository;
    private final BulletinReadStatusRepository bulletinReadStatusRepository;
    private final ChatChannelMemberRepository chatChannelMemberRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;
    private final ContentVisibilityChecker contentVisibilityChecker;
    /** 司令塔第二弾: 個人「今後の予定」への本人シフト統合用（ADHD-UX戦役第四陣）。 */
    private final ShiftAssignmentRepository shiftAssignmentRepository;
    /** 司令塔第二弾: 個人「今後の予定」への本人予約統合用（ADHD-UX戦役第四陣）。 */
    private final ReservationRepository reservationRepository;

    /** F22.1 第二波: 統合「要対応」集計の遅延取得（第 2 段階）に使用する。 */
    private final com.mannschaft.app.dashboard.service.ScopeActionRequiredFacade scopeActionRequiredFacade;
    private final OrganizationService organizationService;
    private final TeamService teamService;
    private final com.mannschaft.app.admin.service.PlatformAnnouncementService platformAnnouncementService;

    // ============================================
    // 個人ダッシュボード
    // ============================================

    /**
     * 個人ダッシュボードの全ウィジェットデータを一括取得する。
     */
    @SelfScopedEndpoint("dashboardService.getPersonalDashboard の検索キーは SecurityUtils.getCurrentUserId() の"
            + "userId のみで、リクエストは他ユーザーの識別子を受け取らない（DashboardController.java:120）")
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
     * プラットフォームお知らせ取得（WidgetPlatformAnnouncements 用）。
     */
    // 認可根治戦役 Wave4 ロットD: 本エンドポイントは Controller / Service にコード上の認可判定を
    // 持たないが、SecurityConfig のパス単位宣言的認可（deny-by-default の anyRequest().authenticated()）
    // でログイン済みユーザーにのみ到達が強制されている。
    // 根拠: SecurityConfig の .anyRequest().authenticated()
    // 応答は platformAnnouncementService.getActiveAnnouncements() が返す公開中の全ユーザー共通の
    // お知らせのみで、認証済みユーザーであれば誰が呼んでも同一の結果になる（ユーザー固有データを
    // 含まない）ため、authenticated() のみで安全に成立する。
    @AuthorizedByPathConfig("anyRequest().authenticated()")
    @GetMapping("/announcements")
    @Operation(summary = "プラットフォームお知らせ取得", description = "公開中のプラットフォームお知らせ一覧を返す")
    public ResponseEntity<ApiResponse<List<DashboardAnnouncementResponse>>> getAnnouncements() {
        List<DashboardAnnouncementResponse> announcements = platformAnnouncementService
                .getActiveAnnouncements()
                .stream()
                .map(a -> new DashboardAnnouncementResponse(
                        a.getId(),
                        a.getTitle(),
                        a.getBody(),
                        mapSeverity(a.getPriority()),
                        a.getIsPinned(),
                        a.getPublishedAt()))
                .toList();
        return ResponseEntity.ok(ApiResponse.of(announcements));
    }

    private static String mapSeverity(String priority) {
        if (priority == null) return "INFO";
        return switch (priority.toUpperCase()) {
            case "URGENT", "CRITICAL" -> "URGENT";
            case "HIGH", "WARNING" -> "WARNING";
            default -> "INFO";
        };
    }

    /**
     * お知らせ欄の詳細一覧（ページネーション対応）。
     */
    @SelfScopedEndpoint("notificationRepository の検索条件が SecurityUtils.getCurrentUserId() の"
            + "userId のみで、リクエストは他ユーザーの識別子を受け取らない（DashboardController.java:163）")
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
    @SelfScopedEndpoint("timelinePostRepository.findByUserIdOrderByCreatedAtDesc の検索条件が"
            + "SecurityUtils.getCurrentUserId() のみで、リクエストは他ユーザーの識別子を受け取らない"
            + "（DashboardController.java:210）")
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
     * 個人TODOウィジェット用データ取得。
     */
    @SelfScopedEndpoint("dashboardService.getPersonalTodos の検索キーが SecurityUtils.getCurrentUserId() の"
            + "userId のみで、リクエストは他ユーザーの識別子を受け取らない（DashboardController.java:239）")
    @GetMapping("/todos")
    @Operation(summary = "個人TODO一覧", description = "自分がアサインされた未完了TODOの一覧と期限切れ件数を取得")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPersonalTodos() {
        Long userId = SecurityUtils.getCurrentUserId();
        Map<String, Object> response = dashboardService.getPersonalTodos(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 直近イベント + 出欠状況。
     */
    @GetMapping("/upcoming-events")
    @Operation(summary = "直近イベント",
            description = "今後N日間のイベント + 本人のシフト + 本人の予約を横断統合し、開始日時昇順で返す（kind: EVENT/SHIFT/RESERVATION）")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getUpcomingEvents(
            @RequestParam(defaultValue = "7") Integer days) {
        Long userId = SecurityUtils.getCurrentUserId();
        // ユーザーTZの当日0時を起点とすることで、今日すでに始まった予定・終日予定（start_at=当日00:00）を含める。
        // getCalendar と同じパターンで LocalDate.now(zone).atStartOfDay() を使用する。
        java.time.ZoneId zone = TimezoneContextHolder.get();
        LocalDateTime from = LocalDate.now(zone).atStartOfDay();
        LocalDateTime until = from.plusDays(days);
        // シフト・予約は slot_date（LocalDate）で絞り込むため、日付境界のみ切り出す。
        LocalDate fromDate = from.toLocalDate();
        LocalDate untilDate = until.toLocalDate();

        // 個人スケジュール（チーム・組織に紐付かないもののみ）
        List<ScheduleEntity> personalSchedules = scheduleRepository
                .findByUserIdAndTeamIdIsNullAndOrganizationIdIsNullAndStartAtBetweenOrderByStartAtAsc(userId, from, until);
        // 所属チームのスケジュール（CMP-027: user_roles ∪ memberships の在籍チーム）
        List<Long> teamIdsForSchedule = userRoleRepository.findTeamIdsByUserId(userId);
        List<ScheduleEntity> teamSchedules = teamIdsForSchedule.stream()
                .flatMap(teamId -> scheduleRepository
                        .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(teamId, from, until).stream())
                .toList();
        // 所属組織のスケジュール（CMP-027: user_roles ∪ memberships の在籍組織）
        List<Long> orgIdsForSchedule = userRoleRepository.findOrganizationIdsByUserId(userId);
        List<ScheduleEntity> orgSchedules = orgIdsForSchedule.stream()
                .flatMap(orgId -> scheduleRepository
                        .findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(orgId, from, until).stream())
                .toList();

        // F00 認可基盤連携（2026-05-29）: チーム横断・組織横断スケジュールは
        // visibility を必ず反映させるため、ID 群を
        // filterAccessible に通して可視なものだけ採用する（team/org をまとめて 1 回判定）。
        // 個人スケジュールは本人取得のため対象外で常に含める。
        List<ScheduleEntity> teamOrgSchedules = new ArrayList<>();
        teamOrgSchedules.addAll(teamSchedules);
        teamOrgSchedules.addAll(orgSchedules);
        Set<Long> visibleTeamOrgIds = teamOrgSchedules.isEmpty()
                ? Set.of()
                : contentVisibilityChecker.filterAccessible(
                        ReferenceType.SCHEDULE,
                        teamOrgSchedules.stream().map(ScheduleEntity::getId).toList(),
                        userId);

        List<Map<String, Object>> items = new ArrayList<>();
        personalSchedules.stream()
                .map(e -> toScheduleMapPersonal(e))
                .forEach(items::add);
        teamSchedules.stream()
                .filter(e -> visibleTeamOrgIds.contains(e.getId()))
                .map(e -> toScheduleMapTeam(e))
                .forEach(items::add);
        orgSchedules.stream()
                .filter(e -> visibleTeamOrgIds.contains(e.getId()))
                .map(e -> toScheduleMapOrg(e))
                .forEach(items::add);

        // 司令塔第二弾（ADHD-UX戦役第四陣）: 本人のシフト（CONFIRMED）・予約（CONFIRMED・代表行）を統合する。
        // それぞれ userId で絞り込み済みのため他人分の混入はない（AC-B2-2）。
        // 各 1 クエリ + チーム名の一括解決 1 クエリのみで、items 件数に関わらず固定 3 クエリ（AC-B2-5・N+1回避）。
        List<Object[]> shiftRows = shiftAssignmentRepository.findUpcomingByUserIdBetween(userId, fromDate, untilDate);
        List<Object[]> reservationRows = reservationRepository.findUpcomingByUserIdBetween(userId, fromDate, untilDate);

        Set<Long> teamIds = new HashSet<>();
        for (Object[] row : shiftRows) {
            Long teamId = (Long) row[5];
            if (teamId != null) teamIds.add(teamId);
        }
        for (Object[] row : reservationRows) {
            Long teamId = (Long) row[5];
            if (teamId != null) teamIds.add(teamId);
        }
        Map<Long, TeamEntity> teamMap = teamIds.isEmpty()
                ? Map.of()
                : teamRepository.findAllById(teamIds).stream()
                        .collect(Collectors.toMap(TeamEntity::getId, t -> t));

        shiftRows.stream().map(row -> toShiftMap(row, teamMap)).forEach(items::add);
        reservationRows.stream().map(row -> toReservationMap(row, teamMap)).forEach(items::add);

        items.sort((a, b) -> ((LocalDateTime) a.get("start_at")).compareTo((LocalDateTime) b.get("start_at")));

        return ResponseEntity.ok(ApiResponse.of(items));
    }

    /**
     * 未読スレッド一覧。
     */
    @SelfScopedEndpoint("userRoleRepository.findByUserIdAndTeamIdIsNotNull と "
            + "chatChannelMemberRepository.findByUserId がいずれも SecurityUtils.getCurrentUserId() の"
            + "userId のみで絞り込まれ、リクエストは他ユーザーの識別子を受け取らない（DashboardController.java:340）")
    @GetMapping("/unread-threads")
    @Operation(summary = "未読スレッド一覧", description = "未読の掲示板スレッド + チャットチャネルを横断取得")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUnreadThreads(
            @RequestParam(defaultValue = "10") Integer limit) {
        Long userId = SecurityUtils.getCurrentUserId();

        // 掲示板: 所属チームのスレッドで未読のもの（CMP-027: user_roles ∪ memberships の在籍チーム）
        List<Long> bulletinTeamIds = userRoleRepository.findTeamIdsByUserId(userId);
        long totalUnreadBulletin = 0;
        for (Long teamId : bulletinTeamIds) {
            Page<com.mannschaft.app.bulletin.entity.BulletinThreadEntity> threads =
                    bulletinThreadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                            com.mannschaft.app.bulletin.ScopeType.TEAM, teamId, PageRequest.of(0, 100));
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
    @SelfScopedEndpoint("スコープIDが userRoleRepository.findTeamIdsByUserId / findOrganizationIdsByUserId"
            + "（いずれも認証主体の userId のみで絞り込む）から導出された自分の所属チーム・所属組織IDのみで、"
            + "リクエストで他ユーザーの識別子は受け取らない（DashboardController.java:399-419）")
    @GetMapping("/activity")
    @Operation(summary = "最近のアクティビティ", description = "所属チーム/組織を横断した最近の活動フィード")
    public ResponseEntity<ApiResponse<ActivityFeedPageResponse>> getActivity(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "10") Integer limit) {
        // F03.18 §7 D-1: 入口で cursor / limit を検証する。ErrorCode をデッドコードにしない。
        if (cursor != null && cursor <= 0) {
            throw new BusinessException(ScheduleFeedErrorCode.INVALID_CURSOR);
        }
        if (limit != null && limit <= 0) {
            throw new BusinessException(ScheduleFeedErrorCode.INVALID_LIMIT);
        }

        Long userId = SecurityUtils.getCurrentUserId();
        // 所属チームIDと所属組織IDの «両方» をスコープとする（CMP-027: user_roles ∪ memberships の在籍）。
        // 従来はチームIDしか集めておらず、ORGANIZATION スコープの活動が原理的に一件も表示されなかった
        // （SCHEDULE 系だけでなく既存7種別も同じく被害を受けていた）。
        List<Long> teamIds = userRoleRepository.findTeamIdsByUserId(userId);
        List<Long> orgIds = userRoleRepository.findOrganizationIdsByUserId(userId);
        ActivityFeedPageResponse response =
                activityFeedService.getActivityFeed(userId, cursor, limit, teamIds, orgIds);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 本コントローラのクエリパラメータ型変換失敗を、ドメイン固有のエラーコードへ写像する。
     *
     * <p>F03.18 §7: {@code cursor=abc} のような非数値カーソルは、{@code @RequestParam Long cursor}
     * のバインドが本体到達 «前» に失敗するため、そのままでは {@code GlobalExceptionHandler} の
     * 既定写像で {@code COMMON_001} になり、入口バリデーションが投げる {@code SCHEDULE_FEED_001}
     * （範囲外カーソル）と契約が食い違う。「不正なカーソルは常に SCHEDULE_FEED_001」という
     * 契約を守るため、当該パラメータの型変換例外だけをここで写像する。</p>
     *
     * <p>コントローラ局所の {@code @ExceptionHandler} を使うのは、{@code GlobalExceptionHandler}
     * 側に "cursor" という汎用的なパラメータ名の分岐を足すと、無関係な他エンドポイントの
     * 型変換エラーまで巻き込むため（既存の {@code isUnresolvedScopeSlug} は 404 という
     * 全体共通の意味づけだったが、SCHEDULE_FEED_001 は本機能固有である）。
     * 局所ハンドラの前例: {@code WeatherController} / {@code OrganizationTeamSearchController}。</p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleParameterTypeMismatch(MethodArgumentTypeMismatchException ex) {
        if ("cursor".equals(ex.getName())) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of(ScheduleFeedErrorCode.INVALID_CURSOR));
        }
        if ("limit".equals(ex.getName())) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of(ScheduleFeedErrorCode.INVALID_LIMIT));
        }
        // 他パラメータは共通の型変換エラーとして扱う（握りつぶさず 400 で返す）。
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(CommonErrorCode.COMMON_001));
    }

    /**
     * 個人カレンダーサマリー。
     */
    @SelfScopedEndpoint("scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc の検索条件が"
            + "SecurityUtils.getCurrentUserId() の userId のみで、リクエストは他ユーザーの識別子を"
            + "受け取らない（DashboardController.java:400-402）")
    @GetMapping("/calendar")
    @Operation(summary = "個人カレンダーサマリー", description = "個人スケジュール + 所属チームの公開イベントを集約")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCalendar(
            @RequestParam(required = false) String month) {
        Long userId = SecurityUtils.getCurrentUserId();

        LocalDateTime todayStart = LocalDate.now(TimezoneContextHolder.get()).atStartOfDay();
        LocalDateTime todayEnd = LocalDate.now(TimezoneContextHolder.get()).atTime(LocalTime.MAX);
        LocalDateTime weekEnd = todayStart.plusDays(7);
        LocalDateTime monthEnd = todayStart.plusMonths(1);

        // F00 認可基盤連携（CMP-017b 第五隊）: today ⊂ week ⊂ month の入れ子期間を
        // 個別に 6 クエリ（personal 3 本 + team 3 本）発行していたのを、
        // 最広範囲（todayStart〜monthEnd）を personal 1 本 + team 1 本の計 2 本で取得し、
        // アプリ層で今日/週/月を集計する方式へ変更（AC-24）。
        // 個人スケジュールは本人所有のため常に可視。チーム予定のみ filterAccessible で
        // min_view_role 等の可視性判定を通す（従来はここが未判定で漏洩していた）。
        List<ScheduleEntity> personalSchedules = scheduleRepository
                .findByUserIdAndStartAtBetweenOrderByStartAtAsc(userId, todayStart, monthEnd);

        // CMP-027: user_roles ∪ memberships の在籍チーム ID（素メンバー/応援者を取りこぼさない）
        List<Long> teamIds = userRoleRepository.findTeamIdsByUserId(userId);
        List<ScheduleEntity> teamSchedules = teamIds.isEmpty()
                ? List.of()
                : scheduleRepository.findByTeamIdInAndStartAtBetween(teamIds, todayStart, monthEnd);
        Set<Long> visibleTeamIds = teamSchedules.isEmpty()
                ? Set.of()
                : contentVisibilityChecker.filterAccessible(
                        ReferenceType.SCHEDULE,
                        teamSchedules.stream().map(ScheduleEntity::getId).toList(),
                        userId);

        List<ScheduleEntity> allSchedules = new ArrayList<>(personalSchedules);
        teamSchedules.stream().filter(s -> visibleTeamIds.contains(s.getId())).forEach(allSchedules::add);

        long eventsToday = 0;
        long eventsThisWeek = 0;
        long eventsThisMonth = 0;
        for (ScheduleEntity s : allSchedules) {
            LocalDateTime startAt = s.getStartAt();
            if (startAt == null) {
                continue;
            }
            eventsThisMonth++;
            if (!startAt.isAfter(weekEnd)) {
                eventsThisWeek++;
            }
            if (!startAt.isAfter(todayEnd)) {
                eventsToday++;
            }
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
    // 認可根治戦役 Wave4 ロットD: 本エンドポイントは Controller / Service にコード上の認可判定を
    // 持たないが、SecurityConfig のパス単位宣言的認可（deny-by-default の anyRequest().authenticated()）
    // でログイン済みユーザーにのみ到達が強制されている。
    // 根拠: SecurityConfig の .anyRequest().authenticated()
    // 現状は静的な空配列のみを返すスタブ実装で、データ取得処理（リポジトリ・他ドメイン Service 呼び出し）
    // 自体が存在しないため authenticated() のみで安全に成立する（認証済みなら誰が呼んでも同一の空応答）。
    // 【重要・将来の実装者への歯止め】パフォーマンス管理モジュールと連携してユーザー固有データを
    // 返すよう実装した瞬間、この根拠は失効する。実データ取得を実装する際は、本注釈をそのまま残さず
    // 認可の要否（所属チーム/組織のスコープ検証等）を必ず再検討すること。
    @AuthorizedByPathConfig("anyRequest().authenticated()")
    @GetMapping("/performance")
    @Operation(summary = "パフォーマンスサマリー", description = "所属チーム/組織ごとの個人パフォーマンス概要")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPerformance() {
        // パフォーマンス管理モジュール実装完了後にデータ取得を連携予定
        return ResponseEntity.ok(ApiResponse.of(Map.of("teams", List.of())));
    }

    /**
     * チャットハブデータ取得。
     */
    @SelfScopedEndpoint("chatHubService.getChatHub の検索キーが SecurityUtils.getCurrentUserId() の"
            + "userId のみで、リクエストは他ユーザーの識別子を受け取らない（DashboardController.java:437）")
    @GetMapping("/chat-hub")
    @Operation(summary = "チャットハブ", description = "グループチャンネル・DM・フォルダ別連絡先の一覧を返す")
    public ResponseEntity<ApiResponse<ChatHubResponse>> getChatHub() {
        Long userId = SecurityUtils.getCurrentUserId();
        ChatHubResponse response = chatHubService.getChatHub(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ============================================
    // チーム・組織ダッシュボード
    // ============================================

    /**
     * チームダッシュボード一括取得。
     */
    @GetMapping("/team/{teamPublicId}")
    @Operation(summary = "チームダッシュボード一括取得", description = "チーム全体の活動状況・お知らせ・イベント等を一括取得")
    public ResponseEntity<ApiResponse<TeamDashboardResponse>> getTeamDashboard(
            @PathVariable String teamPublicId,
            @Parameter(description = "統計期間（TODAY / WEEK / MONTH）") @RequestParam(defaultValue = "WEEK") String statsPeriod) {
        Long teamId = teamService.resolveTeamId(teamPublicId);
        Long userId = SecurityUtils.getCurrentUserId();
        TeamDashboardResponse response = dashboardService.getTeamDashboard(userId, teamId, statsPeriod);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 組織ダッシュボード一括取得。
     */
    @GetMapping("/organization/{orgPublicId}")
    @Operation(summary = "組織ダッシュボード一括取得", description = "傘下チーム一覧・組織全体の統計等を一括取得")
    public ResponseEntity<ApiResponse<OrgDashboardResponse>> getOrgDashboard(
            @PathVariable String orgPublicId,
            @Parameter(description = "統計期間（TODAY / WEEK / MONTH）") @RequestParam(defaultValue = "WEEK") String statsPeriod) {
        Long orgId = organizationService.resolveOrgId(orgPublicId);
        Long userId = SecurityUtils.getCurrentUserId();
        OrgDashboardResponse response = dashboardService.getOrgDashboard(userId, orgId, statsPeriod);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * F22.1 第二波: チームの統合「要対応」集計を取得する（第 2 段階・遅延取得）。
     *
     * <p>回覧板（未確認）/アンケート（未回答）/出欠（未回答）を 1 つに集約して返す。
     * 認可は所属検証（{@code checkMembership}）を {@link com.mannschaft.app.dashboard.service.ScopeActionRequiredFacade}
     * 内で通したうえで、各ドメイン Service が per-scope 認可を再適用する（集計バイパス禁止）。
     * GET 読み取りのため監査ログ対象外（02 §6）。</p>
     */
    @GetMapping("/team/{teamPublicId}/action-required")
    @Operation(summary = "チーム統合「要対応」集計",
            description = "回覧板/アンケート/出欠の未対応を集約。横スワイプ・ダッシュボードのビューポート進入時に遅延取得")
    public ResponseEntity<ApiResponse<com.mannschaft.app.dashboard.dto.ActionRequiredSummaryResponse>>
            getTeamActionRequired(@PathVariable String teamPublicId) {
        Long teamId = teamService.resolveTeamId(teamPublicId);
        Long userId = SecurityUtils.getCurrentUserId();
        com.mannschaft.app.dashboard.dto.ActionRequiredSummaryResponse response =
                scopeActionRequiredFacade.getActionRequired(userId, "TEAM", teamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * F22.1 第二波: 組織の統合「要対応」集計を取得する（第 2 段階・遅延取得）。
     * 仕様は {@link #getTeamActionRequired} の組織版。
     */
    @GetMapping("/organization/{orgPublicId}/action-required")
    @Operation(summary = "組織統合「要対応」集計",
            description = "回覧板/アンケート/出欠の未対応を集約（組織スコープ）")
    public ResponseEntity<ApiResponse<com.mannschaft.app.dashboard.dto.ActionRequiredSummaryResponse>>
            getOrgActionRequired(@PathVariable String orgPublicId) {
        Long orgId = organizationService.resolveOrgId(orgPublicId);
        Long userId = SecurityUtils.getCurrentUserId();
        com.mannschaft.app.dashboard.dto.ActionRequiredSummaryResponse response =
                scopeActionRequiredFacade.getActionRequired(userId, "ORGANIZATION", orgId);
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
            @RequestParam(required = false) String scopeId) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScopeType parsed = widgetService.parseScopeType(scopeType);
        Long resolvedScopeId = widgetService.resolveScopeId(parsed, scopeId);
        // 個人スコープには「メンバーシップ上の管理者ロール」の概念が無く、
        // AccessControlService（membership.ScopeType を利用）に PERSONAL を渡すと
        // IllegalArgumentException が発生して500になる。
        // ドメイン的に正しい短絡として個人スコープでは isAdmin=false で確定させ、
        // accessControlService.isAdminOrAbove を呼ばない。
        boolean isAdmin = parsed != ScopeType.PERSONAL
                && accessControlService.isAdminOrAbove(userId, resolvedScopeId, parsed.name());
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
    // 認可根治戦役 Wave4 ロットD: scopeId はリクエストから受け取るが、
    // widgetService.resetWidgetSettings が呼ぶ
    // widgetSettingRepository.deleteByUserIdAndScopeTypeAndScopeId は
    // userId を必須条件として含む複合キー検索であり、削除対象は常に
    // 「呼び出しユーザー自身のウィジェット設定行」に限定される（DashboardWidgetService.java:236-238）。
    // scopeId に他ユーザーが所属しないチーム/組織のIDを渡しても、当該ユーザー自身の設定行が
    // 存在しなければ 0 件削除で無害。他ユーザーの設定行には userId 不一致のため到達しない。
    @SelfScopedEndpoint("削除対象が (SecurityUtils.getCurrentUserId(), scopeType, scopeId) の複合キーで"
            + "userId を必須条件に含むため、常に呼び出しユーザー自身のウィジェット設定行しか削除できない"
            + "（DashboardWidgetService#resetWidgetSettings, DashboardWidgetService.java:236-238）")
    @DeleteMapping("/widgets")
    @Operation(summary = "ウィジェット設定リセット", description = "指定スコープの全設定を削除しデフォルトに復帰する")
    public ResponseEntity<Void> resetWidgetSettings(
            @RequestParam String scopeType,
            @RequestParam(required = false) String scopeId) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScopeType parsed = widgetService.parseScopeType(scopeType);
        Long resolvedScopeId = widgetService.resolveScopeId(parsed, scopeId);
        widgetService.resetWidgetSettings(userId, parsed, resolvedScopeId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 個人予定をMap表現に変換する。
     */
    private Map<String, Object> toScheduleMapPersonal(ScheduleEntity entity) {
        Map<String, Object> map = toScheduleBaseMap(entity);
        map.put("scope_type", "PERSONAL");
        map.put("scope_name", null);
        map.put("scope_icon_url", null);
        return map;
    }

    /**
     * チーム予定をMap表現に変換する。チーム名・アイコンを付与する。
     */
    private Map<String, Object> toScheduleMapTeam(ScheduleEntity entity) {
        Map<String, Object> map = toScheduleBaseMap(entity);
        map.put("scope_type", "TEAM");
        Long teamId = entity.getTeamId();
        if (teamId != null) {
            Optional<TeamEntity> team = teamRepository.findById(teamId);
            map.put("scope_name", team.map(TeamEntity::getName).orElse(null));
            map.put("scope_icon_url", team.map(TeamEntity::getIconUrl).orElse(null));
        } else {
            map.put("scope_name", null);
            map.put("scope_icon_url", null);
        }
        return map;
    }

    /**
     * 組織予定をMap表現に変換する。組織名・アイコンを付与する。
     */
    private Map<String, Object> toScheduleMapOrg(ScheduleEntity entity) {
        Map<String, Object> map = toScheduleBaseMap(entity);
        map.put("scope_type", "ORG");
        Long orgId = entity.getOrganizationId();
        if (orgId != null) {
            Optional<OrganizationEntity> org = organizationRepository.findById(orgId);
            map.put("scope_name", org.map(OrganizationEntity::getName).orElse(null));
            map.put("scope_icon_url", org.map(OrganizationEntity::getIconUrl).orElse(null));
        } else {
            map.put("scope_name", null);
            map.put("scope_icon_url", null);
        }
        return map;
    }

    /**
     * スケジュールエンティティの共通フィールドをMapに変換する。
     */
    private Map<String, Object> toScheduleBaseMap(ScheduleEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId());
        // 司令塔第二弾（ADHD-UX戦役第四陣）: kind でイベント/シフト/予約を区別する（AC-B2-1）。
        map.put("kind", "EVENT");
        map.put("title", entity.getTitle());
        map.put("start_at", entity.getStartAt());
        map.put("end_at", entity.getEndAt());
        map.put("location", entity.getLocation());
        map.put("all_day", entity.getAllDay());
        return map;
    }

    /**
     * シフト割当（本人分・CONFIRMED）を統合予定Mapに変換する。
     *
     * <p>row = {@code [id, scheduleTitle, slotDate, startTime, endTime, teamId]}
     * （{@link ShiftAssignmentRepository#findUpcomingByUserIdBetween} の返却形）。</p>
     */
    private Map<String, Object> toShiftMap(Object[] row, Map<Long, TeamEntity> teamMap) {
        Long id = (Long) row[0];
        String title = (String) row[1];
        LocalDate slotDate = (LocalDate) row[2];
        LocalTime startTime = (LocalTime) row[3];
        LocalTime endTime = (LocalTime) row[4];
        Long teamId = (Long) row[5];

        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("kind", "SHIFT");
        map.put("title", title);
        map.put("start_at", LocalDateTime.of(slotDate, startTime));
        map.put("end_at", buildEndAt(slotDate, startTime, endTime));
        map.put("location", null);
        map.put("all_day", false);
        map.put("scope_type", "TEAM");
        TeamEntity team = teamMap.get(teamId);
        map.put("scope_name", team != null ? team.getName() : null);
        map.put("scope_icon_url", team != null ? team.getIconUrl() : null);
        return map;
    }

    /**
     * 予約（本人分・CONFIRMED・代表行）を統合予定Mapに変換する。
     *
     * <p>row = {@code [id, slotTitle, slotDate, startTime, endTime, teamId]}
     * （{@link ReservationRepository#findUpcomingByUserIdBetween} の返却形）。</p>
     */
    private Map<String, Object> toReservationMap(Object[] row, Map<Long, TeamEntity> teamMap) {
        Long id = (Long) row[0];
        String title = (String) row[1];
        LocalDate slotDate = (LocalDate) row[2];
        LocalTime startTime = (LocalTime) row[3];
        LocalTime endTime = (LocalTime) row[4];
        Long teamId = (Long) row[5];

        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("kind", "RESERVATION");
        map.put("title", title != null ? title : "");
        map.put("start_at", LocalDateTime.of(slotDate, startTime));
        map.put("end_at", buildEndAt(slotDate, startTime, endTime));
        map.put("location", null);
        map.put("all_day", false);
        map.put("scope_type", "TEAM");
        TeamEntity team = teamMap.get(teamId);
        map.put("scope_name", team != null ? team.getName() : null);
        map.put("scope_icon_url", team != null ? team.getIconUrl() : null);
        return map;
    }

    /**
     * 終了日時を組み立てる。終了時刻が開始時刻より前（日跨ぎシフト・深夜営業予約）の場合は
     * 翌日扱いにする。
     */
    private LocalDateTime buildEndAt(LocalDate slotDate, LocalTime startTime, LocalTime endTime) {
        LocalDate endDate = endTime.isBefore(startTime) ? slotDate.plusDays(1) : slotDate;
        return LocalDateTime.of(endDate, endTime);
    }
}
