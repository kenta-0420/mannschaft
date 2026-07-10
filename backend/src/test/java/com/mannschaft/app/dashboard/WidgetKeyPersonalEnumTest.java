package com.mannschaft.app.dashboard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 対象3-A: 個人ダッシュボード PERSONAL スコープ WidgetKey 受け入れテスト。
 *
 * <p>導出元は ALL_WIDGETS カタログではなく、実際に描画する
 * {@code DashboardPersonalPanel.vue} の並び替え対象ウィジェット（殿の訂正・2026-06-24）。
 * 実パネルが描画する 18 ウィジェットのうち、御裁可案A により除外する 4 種
 * （FamilyHub / AdminBusinessAlert / AmazonAd / RakutenAd）を除いた残りを
 * BE WidgetKey enum と 1:1 で対応させる。</p>
 *
 * <p>除外対象（BEキー不要）:
 * <ul>
 *   <li>WidgetFamilyHub (v-if hasFamilyTeam) → 条件付き固定パネル（案A固定2種）</li>
 *   <li>WidgetAdminBusinessAlert (v-if hasAdminOrDeputyRole) → 条件付き固定パネル（案A固定2種）</li>
 *   <li>WidgetSpotlightPrimary / WidgetSpotlightSecondary → 広告掲載面（F09.19.4 Spotlight・固定・非表示不可）</li>
 * </ul>
 * </p>
 */
@DisplayName("対象3-A: 個人ダッシュボード PERSONAL WidgetKey 受け入れテスト")
class WidgetKeyPersonalEnumTest {

    // ========================================
    // 既存キーの存在確認（回帰）
    // ========================================

    @Nested
    @DisplayName("既存 PERSONAL キーの存在確認（再利用・回帰検知）")
    class ExistingPersonalKeys {

        @Test
        @DisplayName("NOTICES が PERSONAL スコープで存在する（FE: WidgetNotices）")
        void notices_存在() {
            assertThat(WidgetKey.valueOf("NOTICES").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_CALENDAR が PERSONAL スコープで存在する（FE: WidgetMyCalendar）")
        void personal_calendar_存在() {
            assertThat(WidgetKey.valueOf("PERSONAL_CALENDAR").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("UPCOMING_EVENTS が PERSONAL スコープで存在する（FE: WidgetUpcomingEvents）")
        void upcoming_events_存在() {
            assertThat(WidgetKey.valueOf("UPCOMING_EVENTS").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_TODO が PERSONAL スコープで存在する（FE: WidgetPersonalTodo）")
        void personal_todo_存在() {
            assertThat(WidgetKey.valueOf("PERSONAL_TODO").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("TIMETABLE_TODAY が PERSONAL スコープで存在する（FE: DashboardTimetableTodayWidget）")
        void timetable_today_存在() {
            assertThat(WidgetKey.valueOf("TIMETABLE_TODAY").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("TIMETABLE_NOTES が PERSONAL スコープで存在する（FE: DashboardQuickMemoWidget が紐付け）")
        void timetable_notes_存在() {
            assertThat(WidgetKey.valueOf("TIMETABLE_NOTES").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("UNREAD_THREADS が PERSONAL スコープで存在する（FE: WidgetUnreadThreads）")
        void unread_threads_存在() {
            assertThat(WidgetKey.valueOf("UNREAD_THREADS").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("RECENT_ACTIVITY が PERSONAL スコープで存在する（FE: WidgetRecentActivity）")
        void recent_activity_存在() {
            assertThat(WidgetKey.valueOf("RECENT_ACTIVITY").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }
    }

    // ========================================
    // 新規追加キーの存在確認（対象3-A）
    // ========================================

    @Nested
    @DisplayName("新規追加 PERSONAL_* キーの存在確認（対象3-A・実パネル正準）")
    class NewPersonalKeys {

        @Test
        @DisplayName("PERSONAL_EVENT_DISMISSAL_REMINDER が PERSONAL スコープで存在する（FE: WidgetEventDismissalReminder）")
        void personal_event_dismissal_reminder_存在() {
            assertThat(WidgetKey.valueOf("PERSONAL_EVENT_DISMISSAL_REMINDER").getScopeType())
                    .isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_WEATHER が PERSONAL スコープで存在する（FE: WidgetWeather）")
        void personal_weather_存在() {
            assertThat(WidgetKey.valueOf("PERSONAL_WEATHER").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_TODO_COUNTDOWN が PERSONAL スコープで存在する（FE: WidgetTodoCountdown）")
        void personal_todo_countdown_存在() {
            assertThat(WidgetKey.valueOf("PERSONAL_TODO_COUNTDOWN").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_REFLECTION_TODAY が PERSONAL スコープで存在する（FE: WidgetReflectionToday）")
        void personal_reflection_today_存在() {
            assertThat(WidgetKey.valueOf("PERSONAL_REFLECTION_TODAY").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_TEAM_ANNOUNCEMENTS が PERSONAL スコープで存在する（FE: WidgetTeamAnnouncements）")
        void personal_team_announcements_存在() {
            assertThat(WidgetKey.valueOf("PERSONAL_TEAM_ANNOUNCEMENTS").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_ORG_ANNOUNCEMENTS が PERSONAL スコープで存在する（FE: WidgetOrgAnnouncements）")
        void personal_org_announcements_存在() {
            assertThat(WidgetKey.valueOf("PERSONAL_ORG_ANNOUNCEMENTS").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_BLOG が PERSONAL スコープで存在する（FE: WidgetMyBlog）")
        void personal_blog_存在() {
            assertThat(WidgetKey.valueOf("PERSONAL_BLOG").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_MY_TEAMS が PERSONAL スコープで存在する（FE: WidgetMyTeams）")
        void personal_my_teams_存在() {
            assertThat(WidgetKey.valueOf("PERSONAL_MY_TEAMS").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_MY_ORGANIZATIONS が PERSONAL スコープで存在する（FE: WidgetMyOrganizations）")
        void personal_my_organizations_存在() {
            assertThat(WidgetKey.valueOf("PERSONAL_MY_ORGANIZATIONS").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_FAVORITES が PERSONAL スコープで存在する（FE: WidgetFavorites）")
        void personal_favorites_存在() {
            assertThat(WidgetKey.valueOf("PERSONAL_FAVORITES").getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }
    }

    // ========================================
    // 誤キーが存在しないことの確認（前回の誤りの回帰防止）
    // ========================================

    @Nested
    @DisplayName("ALL_WIDGETS カタログ由来の誤キーが存在しないこと（回帰防止）")
    class ErroneousKeysAbsent {

        @Test
        @DisplayName("実パネルに描画されないキー（ALL_WIDGETS カタログ由来の誤り）は enum に存在しない")
        void erroneous_keys_absent() {
            List<String> personalKeyNames = Arrays.stream(WidgetKey.values())
                    .filter(wk -> wk.getScopeType() == ScopeType.PERSONAL)
                    .map(WidgetKey::name)
                    .collect(Collectors.toList());

            // 前回 ALL_WIDGETS から誤って導出したが、DashboardPersonalPanel.vue が描画しないキー
            assertThat(personalKeyNames)
                    .doesNotContain(
                            "PERSONAL_NOTIFICATIONS",
                            "PERSONAL_RECRUITMENT_FEED",
                            "PERSONAL_MY_RECRUITMENTS",
                            "PERSONAL_VILLAGE_LOBBY_DIGEST",
                            "PERSONAL_INBOX"
                    );
        }
    }

    // ========================================
    // PERSONAL スコープのキー総数確認
    // ========================================

    @Nested
    @DisplayName("PERSONAL スコープのキー総数確認")
    class PersonalScopeCount {

        @Test
        @DisplayName("PERSONAL スコープのキーが 26 件（既存 15 件 + 新規 11 件）")
        void personal_scope_total_26件() {
            List<WidgetKey> personalKeys = Arrays.stream(WidgetKey.values())
                    .filter(wk -> wk.getScopeType() == ScopeType.PERSONAL)
                    .collect(Collectors.toList());

            assertThat(personalKeys)
                    .as("PERSONAL スコープのキーが 26 件あること（既存 15 件 + 新規 11 件・PERSONAL_MY_TIMELINE 追加）")
                    .hasSize(26);
        }

        @Test
        @DisplayName("除外対象（FamilyHub / AdminBusinessAlert / 広告）の BEキーが PERSONAL スコープに存在しない")
        void excluded_widgets_no_be_key() {
            List<String> personalKeyNames = Arrays.stream(WidgetKey.values())
                    .filter(wk -> wk.getScopeType() == ScopeType.PERSONAL)
                    .map(WidgetKey::name)
                    .collect(Collectors.toList());

            assertThat(personalKeyNames)
                    .doesNotContain(
                            "FAMILY_HUB",
                            "PERSONAL_FAMILY_HUB",
                            "ADMIN_BUSINESS_ALERT",
                            "PERSONAL_ADMIN_BUSINESS_ALERT",
                            "AMAZON_AD",
                            "RAKUTEN_AD"
                    );
        }
    }

    // ========================================
    // forScope: PERSONAL スコープ一覧
    // ========================================

    @Nested
    @DisplayName("forScope: PERSONAL スコープ一覧")
    class ForScopePersonal {

        @Test
        @DisplayName("forScope(PERSONAL) に新規追加キー 10 件が含まれる")
        void forScope_personal_新規キー含む() {
            List<WidgetKey> personalKeys = WidgetKey.forScope(ScopeType.PERSONAL);

            assertThat(personalKeys).contains(
                    WidgetKey.valueOf("PERSONAL_EVENT_DISMISSAL_REMINDER"),
                    WidgetKey.valueOf("PERSONAL_WEATHER"),
                    WidgetKey.valueOf("PERSONAL_TODO_COUNTDOWN"),
                    WidgetKey.valueOf("PERSONAL_REFLECTION_TODAY"),
                    WidgetKey.valueOf("PERSONAL_TEAM_ANNOUNCEMENTS"),
                    WidgetKey.valueOf("PERSONAL_ORG_ANNOUNCEMENTS"),
                    WidgetKey.valueOf("PERSONAL_BLOG"),
                    WidgetKey.valueOf("PERSONAL_MY_TEAMS"),
                    WidgetKey.valueOf("PERSONAL_MY_ORGANIZATIONS"),
                    WidgetKey.valueOf("PERSONAL_FAVORITES")
            );
        }
    }

    // ========================================
    // defaultSortOrder: 新規追加キーの順序
    // ========================================

    @Nested
    @DisplayName("defaultSortOrder: 新規追加キーの順序確認")
    class DefaultSortOrder {

        @Test
        @DisplayName("新規追加キー 11 件の defaultSortOrder が既存の最大値（14）より大きい")
        void new_keys_sort_order_gt_14() {
            // MY_CORKBOARD が defaultSortOrder=14 で最後の既存キー
            List<WidgetKey> newKeys = Arrays.stream(WidgetKey.values())
                    .filter(wk -> wk.getScopeType() == ScopeType.PERSONAL)
                    .filter(wk -> wk.getDefaultSortOrder() > 14)
                    .collect(Collectors.toList());

            // 新規追加した 11 件全てが order > 14（連番 15〜25・PERSONAL_MY_TIMELINE=25）であること
            assertThat(newKeys).hasSize(11);
        }
    }
}
