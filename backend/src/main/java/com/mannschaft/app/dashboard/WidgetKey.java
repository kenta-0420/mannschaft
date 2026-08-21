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
    /** F09.8.1: マイコルクボードウィジェット（横断ピン止め一覧） */
    MY_CORKBOARD(ScopeType.PERSONAL, true, 14),
    // --- 対象3-A: 個人ダッシュボード DashboardPersonalPanel.vue が実際に描画する並び替え対象ウィジェットと 1:1 対応。---
    // 導出元は ALL_WIDGETS カタログではなく実パネル（殿の訂正・2026-06-24）。
    // FamilyHub（v-if hasFamilyTeam）・AdminBusinessAlert（v-if hasAdminOrDeputyRole）・広告（Amazon/楽天）は
    // 御裁可案A により対象外（並び替え・非表示の対象外＝BEキーを作らない）。
    /** FE WidgetEventDismissalReminder: F03.12 §16 解散通知未送信リマインダー（主催者向け） */
    PERSONAL_EVENT_DISMISSAL_REMINDER(ScopeType.PERSONAL, true, 15),
    /** FE WidgetWeather: F02.10 登録郵便番号から導出した居住地点の天気予報 */
    PERSONAL_WEATHER(ScopeType.PERSONAL, true, 16),
    /** FE WidgetTodoCountdown: 締切が近い TODO のカウントダウン */
    PERSONAL_TODO_COUNTDOWN(ScopeType.PERSONAL, true, 17),
    /** FE WidgetReflectionToday: F06.5 follow-up A 今日の振り返り導線 */
    PERSONAL_REFLECTION_TODAY(ScopeType.PERSONAL, true, 18),
    /** FE WidgetTeamAnnouncements: 所属チームからの掲示板・お知らせ */
    PERSONAL_TEAM_ANNOUNCEMENTS(ScopeType.PERSONAL, true, 19),
    /** FE WidgetOrgAnnouncements: 所属組織からの掲示板・お知らせ */
    PERSONAL_ORG_ANNOUNCEMENTS(ScopeType.PERSONAL, true, 20),
    /** FE WidgetMyBlog: 自分のブログ記事・作成導線 */
    PERSONAL_BLOG(ScopeType.PERSONAL, true, 21),
    /** FE WidgetMyTeams: 参加チーム一覧 */
    PERSONAL_MY_TEAMS(ScopeType.PERSONAL, true, 22),
    /** FE WidgetMyOrganizations: 参加組織一覧 */
    PERSONAL_MY_ORGANIZATIONS(ScopeType.PERSONAL, true, 23),
    /** FE WidgetFavorites: F02.9 Phase 2 お気に入りウィジェット */
    PERSONAL_FAVORITES(ScopeType.PERSONAL, true, 24),
    /** FE WidgetMyTimeline: 所属 team/org 横断の個人集約タイムライン（GET /api/v1/timeline/my） */
    PERSONAL_MY_TIMELINE(ScopeType.PERSONAL, true, 25),
    /** F02.11: 帰省・滞在予定を個人ダッシュボードで管理するウィジェット */
    RETURN_STAY_PLAN(ScopeType.PERSONAL, true, 26),

    // --- チームダッシュボード ---
    TEAM_NOTICES(ScopeType.TEAM, true, 0),
    TEAM_UPCOMING_EVENTS(ScopeType.TEAM, true, 1),
    TEAM_TODO(ScopeType.TEAM, true, 2),
    TEAM_PROJECT_PROGRESS(ScopeType.TEAM, true, 3),
    TEAM_ACTIVITY(ScopeType.TEAM, true, 4),
    TEAM_LATEST_POSTS(ScopeType.TEAM, true, 5),
    TEAM_UNREAD_THREADS(ScopeType.TEAM, true, 6),
    TEAM_MEMBER_ATTENDANCE(ScopeType.TEAM, true, 7),
    /** F08.7.1: 自チーム大会成績（通算成績＋順位履歴） */
    TEAM_TOURNAMENT_RECORD(ScopeType.TEAM, true, 8),
    /** F08.7.1: 順位表（現在参加中ディビジョンの順位表） */
    TEAM_DIVISION_STANDINGS(ScopeType.TEAM, true, 9),
    TEAM_BILLING(ScopeType.TEAM, true, 10),
    TEAM_PAGE_VIEWS(ScopeType.TEAM, false, 11),
    /** F08.10: チーム試合サマリ（直近成績＋ミニチャート＋進行中試合の記録再開導線） */
    TEAM_MATCH_SUMMARY(ScopeType.TEAM, true, 12),
    /** F10.1.1 P3b Wave2: 管理者レンズ チームメンバー統計（総数/アクティブ/今月新規・ADMIN 限定・コード固定 ADMINS_AND_ABOVE） */
    ADMIN_TEAM_MEMBERS(ScopeType.TEAM, true, 13),
    /** F10.1.1 P3b Wave2: 管理者レンズ チーム予約サマリ（承認待ち/本日の予約数・ADMIN 限定・コード固定 ADMINS_AND_ABOVE） */
    ADMIN_TEAM_RESERVATIONS(ScopeType.TEAM, true, 14),
    /** F10.1.1 P3b Wave3: 管理者レンズ チーム予算サマリ（配分/実績/残/超過カテゴリ数・ADMIN/TEAM_BUDGET_VIEW 限定・コード固定 ADMINS_AND_ABOVE） */
    ADMIN_TEAM_BUDGET(ScopeType.TEAM, true, 15),
    // --- 対象2: FE チームウィジェットと 1:1 対応するため追加（並び順 DB 永続化の根治）。---
    // FE WidgetKeyMap（useDashboardWidgets.ts）の右辺が参照していたが enum に存在せず、
    // PUT 時に DASHBOARD_001 で弾かれて並び順が DB 保存されなかった欠落キーを補完する。
    /** FE: members（メンバー一覧） */
    TEAM_MEMBERS(ScopeType.TEAM, true, 16),
    /** FE: gallery（ギャラリー） */
    TEAM_GALLERY(ScopeType.TEAM, true, 17),
    /** FE: circulation（回覧板） */
    TEAM_CIRCULATION(ScopeType.TEAM, true, 18),
    /** FE: surveys（アンケート） */
    TEAM_SURVEYS(ScopeType.TEAM, true, 19),
    /** FE: survey-results（アンケート結果） */
    TEAM_SURVEY_RESULTS(ScopeType.TEAM, true, 20),
    /** FE: blog（ブログ） */
    TEAM_BLOG(ScopeType.TEAM, true, 21),
    /** FE: schedule（カレンダー）。upcoming-events と区別するため専用キー（旧: TEAM_UPCOMING_EVENTS と衝突していた） */
    TEAM_SCHEDULE_CALENDAR(ScopeType.TEAM, true, 22),
    /** FE: member-info（メンバー情報定期更新フォーム・F14.2） */
    TEAM_MEMBER_INFO(ScopeType.TEAM, true, 23),

    // --- 組織ダッシュボード ---
    ORG_TEAM_LIST(ScopeType.ORGANIZATION, true, 0),
    ORG_NOTICES(ScopeType.ORGANIZATION, true, 1),
    ORG_TODO(ScopeType.ORGANIZATION, true, 2),
    ORG_PROJECT_PROGRESS(ScopeType.ORGANIZATION, true, 3),
    ORG_STATS(ScopeType.ORGANIZATION, true, 4),
    /** F08.7.1: 主催大会サマリ（各大会×各部の首位・参加数・status） */
    ORG_TOURNAMENT_SUMMARY(ScopeType.ORGANIZATION, true, 5),
    ORG_BILLING(ScopeType.ORGANIZATION, true, 6),
    /** F10.1.1 P3b Wave2: 管理者レンズ 組織メンバー統計（総数/アクティブ/今月新規・ADMIN 限定・コード固定 ADMINS_AND_ABOVE） */
    ADMIN_ORG_MEMBERS(ScopeType.ORGANIZATION, true, 7),
    /** F10.1.1 P3b Wave3: 管理者レンズ 組織予算サマリ（配分/実績/残/超過カテゴリ数・ADMIN/BUDGET_VIEW 限定・コード固定 ADMINS_AND_ABOVE） */
    ADMIN_ORG_BUDGET(ScopeType.ORGANIZATION, true, 8),
    // --- 対象2: FE 組織ウィジェットと 1:1 対応するため追加（並び順 DB 永続化の根治）。---
    // 組織スコープは従来 FE WidgetKeyMap にマッピングが無く、並び順が一切 DB 保存されなかった。
    // FE ALL_WIDGETS で organization スコープを持つ全ウィジェットへ専用キーを付与する。
    /** FE: upcoming-events（今後の予定） */
    ORG_UPCOMING_EVENTS(ScopeType.ORGANIZATION, true, 9),
    /** FE: timeline（タイムライン） */
    ORG_LATEST_POSTS(ScopeType.ORGANIZATION, true, 10),
    /** FE: blog（ブログ） */
    ORG_BLOG(ScopeType.ORGANIZATION, true, 11),
    /** FE: chat（チャット） */
    ORG_UNREAD_THREADS(ScopeType.ORGANIZATION, true, 12),
    /** FE: schedule（カレンダー） */
    ORG_SCHEDULE_CALENDAR(ScopeType.ORGANIZATION, true, 13),
    /** FE: members（メンバー一覧） */
    ORG_MEMBERS(ScopeType.ORGANIZATION, true, 14),
    /** FE: activities（活動記録） */
    ORG_ACTIVITY(ScopeType.ORGANIZATION, true, 15),
    /** FE: gallery（ギャラリー） */
    ORG_GALLERY(ScopeType.ORGANIZATION, true, 16),
    /** FE: circulation（回覧板） */
    ORG_CIRCULATION(ScopeType.ORGANIZATION, true, 17),
    /** FE: surveys（アンケート） */
    ORG_SURVEYS(ScopeType.ORGANIZATION, true, 18),
    /** FE: survey-results（アンケート結果） */
    ORG_SURVEY_RESULTS(ScopeType.ORGANIZATION, true, 19),
    /** FE: attendance-results（出席確認状況） */
    ORG_MEMBER_ATTENDANCE(ScopeType.ORGANIZATION, true, 20);

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
            // F08.7.1 大会成績ウィジェット（TEAM_TOURNAMENT_RECORD / TEAM_DIVISION_STANDINGS /
            // ORG_TOURNAMENT_SUMMARY）はモジュール依存を登録しない。
            // 理由（設計書 02_dashboard_widgets.md §4.1 Y-1 訂正の grep 結果）:
            //   module_definitions（V2.024__seed_module_definitions.sql）に大会・リーグ用の
            //   スラッグは未登録であり、F08.7 大会機能自体もモジュールスラッグで gate していない。
            //   存在しないスラッグを登録すると ModuleService.isModuleEnabledForTeam が常に false を返し、
            //   ウィジェットが全団体で永久に非表示になる（機能不全）。
            //   よって F08.7 本体の運用と整合させ、ここではモジュール依存を付与しない。
            //   将来、大会・リーグを選択式モジュールとして正式にシードした際に、本マップへ追加する。
    );

    /**
     * このウィジェットが依存するモジュールスラッグを返す。null ならデフォルト機能。
     */
    public String getRequiredModuleSlug() {
        return MODULE_SLUG_MAP.get(this);
    }

    /** ロール制限ウィジェット（ADMIN / DEPUTY_ADMIN のみ） */
    private static final Set<WidgetKey> ROLE_RESTRICTED = Set.of(
            TEAM_BILLING, TEAM_PAGE_VIEWS, ORG_BILLING,
            // F10.1.1 P3b 管理者レンズウィジェットは ADMIN/DEPUTY 限定（コード固定 ADMINS_AND_ABOVE）。
            ADMIN_TEAM_MEMBERS, ADMIN_TEAM_RESERVATIONS, ADMIN_ORG_MEMBERS,
            ADMIN_TEAM_BUDGET, ADMIN_ORG_BUDGET
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
