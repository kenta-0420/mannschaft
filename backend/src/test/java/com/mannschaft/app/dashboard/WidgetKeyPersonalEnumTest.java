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
 * <p>FE useDashboardWidgets.ts の ALL_WIDGETS（personal スコープ）と BE WidgetKey enum の
 * 1:1 対応を検証する。新規追加した PERSONAL_* キーが enum に存在し、PUT で受理される
 * ことを確認する（試練フェーズ: 実装前は RED になることを確認してから実装する）。</p>
 *
 * <p>除外対象（BEキー不要）:
 * <ul>
 *   <li>family-hub (FamilyHub) → 条件付き固定パネル（案A固定2種）</li>
 *   <li>admin-business-alert (AdminBusinessAlert) → 条件付き固定パネル（案A固定2種）</li>
 *   <li>AmazonAd / RakutenAd → 広告（固定・非表示不可）</li>
 * </ul>
 * </p>
 */
@DisplayName("対象3-A: 個人ダッシュボード PERSONAL WidgetKey 受け入れテスト")
class WidgetKeyPersonalEnumTest {

    // ========================================
    // 既存キーの存在確認（回帰）
    // ========================================

    @Nested
    @DisplayName("既存 PERSONAL キーの存在確認（回帰検知）")
    class ExistingPersonalKeys {

        @Test
        @DisplayName("NOTICES が PERSONAL スコープで存在する")
        void notices_存在() {
            WidgetKey wk = WidgetKey.valueOf("NOTICES");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PLATFORM_ANNOUNCEMENTS が PERSONAL スコープで存在する")
        void platform_announcements_存在() {
            WidgetKey wk = WidgetKey.valueOf("PLATFORM_ANNOUNCEMENTS");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("UPCOMING_EVENTS が PERSONAL スコープで存在する")
        void upcoming_events_存在() {
            WidgetKey wk = WidgetKey.valueOf("UPCOMING_EVENTS");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_TODO が PERSONAL スコープで存在する")
        void personal_todo_存在() {
            WidgetKey wk = WidgetKey.valueOf("PERSONAL_TODO");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("MY_POSTS が PERSONAL スコープで存在する")
        void my_posts_存在() {
            WidgetKey wk = WidgetKey.valueOf("MY_POSTS");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("CHAT_HUB が PERSONAL スコープで存在する")
        void chat_hub_存在() {
            WidgetKey wk = WidgetKey.valueOf("CHAT_HUB");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("MY_CORKBOARD が PERSONAL スコープで存在する")
        void my_corkboard_存在() {
            WidgetKey wk = WidgetKey.valueOf("MY_CORKBOARD");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("TIMETABLE_TODAY が PERSONAL スコープで存在する")
        void timetable_today_存在() {
            WidgetKey wk = WidgetKey.valueOf("TIMETABLE_TODAY");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }
    }

    // ========================================
    // 新規追加キーの存在確認（対象3-A）
    // ========================================

    @Nested
    @DisplayName("新規追加 PERSONAL_* キーの存在確認（対象3-A）")
    class NewPersonalKeys {

        @Test
        @DisplayName("PERSONAL_TEAM_ANNOUNCEMENTS が PERSONAL スコープで存在する（FE: team-announcements）")
        void personal_team_announcements_存在() {
            WidgetKey wk = WidgetKey.valueOf("PERSONAL_TEAM_ANNOUNCEMENTS");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_ORG_ANNOUNCEMENTS が PERSONAL スコープで存在する（FE: org-announcements）")
        void personal_org_announcements_存在() {
            WidgetKey wk = WidgetKey.valueOf("PERSONAL_ORG_ANNOUNCEMENTS");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_BLOG が PERSONAL スコープで存在する（FE: blog）")
        void personal_blog_存在() {
            WidgetKey wk = WidgetKey.valueOf("PERSONAL_BLOG");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_NOTIFICATIONS が PERSONAL スコープで存在する（FE: notifications）")
        void personal_notifications_存在() {
            WidgetKey wk = WidgetKey.valueOf("PERSONAL_NOTIFICATIONS");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_RECRUITMENT_FEED が PERSONAL スコープで存在する（FE: recruitment-feed）")
        void personal_recruitment_feed_存在() {
            WidgetKey wk = WidgetKey.valueOf("PERSONAL_RECRUITMENT_FEED");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_MY_RECRUITMENTS が PERSONAL スコープで存在する（FE: my-recruitments）")
        void personal_my_recruitments_存在() {
            WidgetKey wk = WidgetKey.valueOf("PERSONAL_MY_RECRUITMENTS");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_WEATHER が PERSONAL スコープで存在する（FE: weather）")
        void personal_weather_存在() {
            WidgetKey wk = WidgetKey.valueOf("PERSONAL_WEATHER");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_VILLAGE_LOBBY_DIGEST が PERSONAL スコープで存在する（FE: village-lobby-digest）")
        void personal_village_lobby_digest_存在() {
            WidgetKey wk = WidgetKey.valueOf("PERSONAL_VILLAGE_LOBBY_DIGEST");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }

        @Test
        @DisplayName("PERSONAL_INBOX が PERSONAL スコープで存在する（FE: inbox）")
        void personal_inbox_存在() {
            WidgetKey wk = WidgetKey.valueOf("PERSONAL_INBOX");
            assertThat(wk.getScopeType()).isEqualTo(ScopeType.PERSONAL);
        }
    }

    // ========================================
    // PERSONAL スコープのキー総数確認
    // ========================================

    @Nested
    @DisplayName("PERSONAL スコープのキー総数確認")
    class PersonalScopeCount {

        @Test
        @DisplayName("PERSONAL スコープのキーが 24 件（既存 15 件 + 新規 9 件）")
        void personal_scope_total_24件() {
            List<WidgetKey> personalKeys = Arrays.stream(WidgetKey.values())
                    .filter(wk -> wk.getScopeType() == ScopeType.PERSONAL)
                    .collect(Collectors.toList());

            assertThat(personalKeys)
                    .as("PERSONAL スコープのキーが 24 件あること（既存 15 件 + 新規 9 件）")
                    .hasSize(24);
        }

        @Test
        @DisplayName("除外対象（FamilyHub / AdminBusinessAlert / 広告）の BEキーが PERSONAL スコープに存在しない")
        void excluded_widgets_no_be_key() {
            // FamilyHub → BEキー不要（条件付き固定）
            // AdminBusinessAlert → BEキー不要（条件付き固定）
            // AmazonAd / RakutenAd → BEキー不要（広告）
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
        @DisplayName("forScope(PERSONAL) に新規追加キーが含まれる")
        void forScope_personal_新規キー含む() {
            List<WidgetKey> personalKeys = WidgetKey.forScope(ScopeType.PERSONAL);

            // 新規追加キー
            assertThat(personalKeys).contains(
                    WidgetKey.valueOf("PERSONAL_TEAM_ANNOUNCEMENTS"),
                    WidgetKey.valueOf("PERSONAL_ORG_ANNOUNCEMENTS"),
                    WidgetKey.valueOf("PERSONAL_BLOG"),
                    WidgetKey.valueOf("PERSONAL_NOTIFICATIONS"),
                    WidgetKey.valueOf("PERSONAL_RECRUITMENT_FEED"),
                    WidgetKey.valueOf("PERSONAL_MY_RECRUITMENTS"),
                    WidgetKey.valueOf("PERSONAL_WEATHER"),
                    WidgetKey.valueOf("PERSONAL_VILLAGE_LOBBY_DIGEST"),
                    WidgetKey.valueOf("PERSONAL_INBOX")
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
        @DisplayName("新規追加キーの defaultSortOrder が既存の最大値（14）より大きい")
        void new_keys_sort_order_gt_14() {
            // MY_CORKBOARD が defaultSortOrder=14 で最後の既存キー
            List<WidgetKey> newKeys = Arrays.stream(WidgetKey.values())
                    .filter(wk -> wk.getScopeType() == ScopeType.PERSONAL)
                    .filter(wk -> wk.getDefaultSortOrder() > 14)
                    .collect(Collectors.toList());

            // 新規追加した 9 件全てが order > 14 であること
            assertThat(newKeys).hasSize(9);
        }
    }
}
