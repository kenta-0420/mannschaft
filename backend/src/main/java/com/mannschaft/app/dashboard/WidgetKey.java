package com.mannschaft.app.dashboard;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ウィジェット種別キー。個人・チーム・組織ダッシュボードに対応するウィジェットを定義する。
 * デフォルト表示（is_visible の初期値）とスコープ対応を管理する。
 */
public enum WidgetKey {

    // --- 個人ダッシュボード ---
    NOTICES(ScopeType.PERSONAL, true, 0),
    PLATFORM_ANNOUNCEMENTS(ScopeType.PERSONAL, true, 1),
    UPCOMING_EVENTS(ScopeType.PERSONAL, true, 2),
    MY_POSTS(ScopeType.PERSONAL, true, 3),
    UNREAD_THREADS(ScopeType.PERSONAL, true, 4),
    RECENT_ACTIVITY(ScopeType.PERSONAL, true, 5),
    PERFORMANCE_SUMMARY(ScopeType.PERSONAL, true, 6),
    PERSONAL_CALENDAR(ScopeType.PERSONAL, true, 7),
    PERSONAL_TODO(ScopeType.PERSONAL, true, 8),
    PERSONAL_PROJECT_PROGRESS(ScopeType.PERSONAL, true, 9),
    CHAT_HUB(ScopeType.PERSONAL, true, 10),
    BILLING_PERSONAL(ScopeType.PERSONAL, false, 11),
    /** F03.15 Phase 4: 個人ダッシュボード「今日の時間割」ウィジェット */
    TIMETABLE_TODAY(ScopeType.PERSONAL, true, 12),
    /** F03.15 Phase 4: 個人ダッシュボード「今日のメモ」ウィジェット */
    TIMETABLE_NOTES(ScopeType.PERSONAL, true, 13),

    // --- チームダッシュボード ---
    TEAM_NOTICES(ScopeType.TEAM, true, 0),
    TEAM_UPCOMING_EVENTS(ScopeType.TEAM, true, 1),
    TEAM_TODO(ScopeType.TEAM, true, 2),
    TEAM_PROJECT_PROGRESS(ScopeType.TEAM, true, 3),
    TEAM_ACTIVITY(ScopeType.TEAM, true, 4),
    TEAM_LATEST_POSTS(ScopeType.TEAM, true, 5),
    TEAM_UNREAD_THREADS(ScopeType.TEAM, true, 6),
    TEAM_MEMBER_ATTENDANCE(ScopeType.TEAM, true, 7),
    TEAM_BILLING(ScopeType.TEAM, true, 8),
    TEAM_PAGE_VIEWS(ScopeType.TEAM, false, 9),

    // --- 組織ダッシュボード ---
    ORG_TEAM_LIST(ScopeType.ORGANIZATION, true, 0),
    ORG_NOTICES(ScopeType.ORGANIZATION, true, 1),
    ORG_TODO(ScopeType.ORGANIZATION, true, 2),
    ORG_PROJECT_PROGRESS(ScopeType.ORGANIZATION, true, 3),
    ORG_STATS(ScopeType.ORGANIZATION, true, 4),
    ORG_BILLING(ScopeType.ORGANIZATION, true, 5);

    private final ScopeType scopeType;
    private final boolean defaultVisible;
    private final int defaultSortOrder;

    WidgetKey(ScopeType scopeType, boolean defaultVisible, int defaultSortOrder) {
        this.scopeType = scopeType;
        this.defaultVisible = defaultVisible;
        this.defaultSortOrder = defaultSortOrder;
    }

    public ScopeType getScopeType() {
        return scopeType;
    }

    public boolean isDefaultVisible() {
        return defaultVisible;
    }

    public int getDefaultSortOrder() {
        return defaultSortOrder;
    }

    /**
     * ウィジェットが依存する選択式モジュールのスラッグ。
     * null の場合はデフォルト機能に属し、常に有効。
     */
    private static final Map<WidgetKey, String> MODULE_SLUG_MAP = Map.ofEntries(
            Map.entry(PERFORMANCE_SUMMARY, "performance"),
            Map.entry(PERSONAL_PROJECT_PROGRESS, "project"),
            Map.entry(CHAT_HUB, "chat"),
            Map.entry(TEAM_PROJECT_PROGRESS, "project"),
            Map.entry(TEAM_PAGE_VIEWS, "analytics")
    );

    /**
     * このウィジェットが依存するモジュールスラッグを返す。null ならデフォルト機能。
     */
    public String getRequiredModuleSlug() {
        return MODULE_SLUG_MAP.get(this);
    }

    /** ロール制限ウィジェット（ADMIN / DEPUTY_ADMIN のみ） */
    private static final Set<WidgetKey> ROLE_RESTRICTED = Set.of(
            TEAM_BILLING, TEAM_PAGE_VIEWS, ORG_BILLING
    );

    public boolean isRoleRestricted() {
        return ROLE_RESTRICTED.contains(this);
    }

    /** スコープ別ウィジェット一覧キャッシュ */
    private static final Map<ScopeType, List<WidgetKey>> BY_SCOPE =
            Arrays.stream(values())
                    .collect(Collectors.groupingBy(WidgetKey::getScopeType));

    /**
     * 指定スコープに属するウィジェット一覧を返す。
     */
    public static List<WidgetKey> forScope(ScopeType scopeType) {
        return BY_SCOPE.getOrDefault(scopeType, List.of());
    }
}
