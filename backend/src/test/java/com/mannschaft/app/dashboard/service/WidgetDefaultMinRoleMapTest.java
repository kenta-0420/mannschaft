package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.WidgetKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F02.2.1: {@link WidgetDefaultMinRoleMap} の単体テスト。
 *
 * <p>設計書 §3「デフォルト min_role 値（アプリ層定義）」のテーブル全件を
 * 網羅検証する。テーブルの値が変更された場合に必ずテストが落ちることで、
 * フロントエンド・ドキュメントとの整合性を保つ。</p>
 */
@DisplayName("WidgetDefaultMinRoleMap 単体テスト")
class WidgetDefaultMinRoleMapTest {

    // ========================================
    // チームダッシュボード（設計書 §3 表）
    // ========================================

    @Nested
    @DisplayName("getDefault: チームダッシュボードのデフォルト値")
    class TeamDefaults {

        @Test
        @DisplayName("TEAM_NOTICES → PUBLIC")
        void teamNotices_PUBLIC() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.TEAM_NOTICES))
                    .isEqualTo(MinRole.PUBLIC);
        }

        @Test
        @DisplayName("TEAM_UPCOMING_EVENTS → PUBLIC")
        void teamUpcomingEvents_PUBLIC() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.TEAM_UPCOMING_EVENTS))
                    .isEqualTo(MinRole.PUBLIC);
        }

        @Test
        @DisplayName("TEAM_TODO → MEMBER")
        void teamTodo_MEMBER() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.TEAM_TODO))
                    .isEqualTo(MinRole.MEMBER);
        }

        @Test
        @DisplayName("TEAM_PROJECT_PROGRESS → MEMBER")
        void teamProjectProgress_MEMBER() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.TEAM_PROJECT_PROGRESS))
                    .isEqualTo(MinRole.MEMBER);
        }

        @Test
        @DisplayName("TEAM_ACTIVITY → SUPPORTER")
        void teamActivity_SUPPORTER() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.TEAM_ACTIVITY))
                    .isEqualTo(MinRole.SUPPORTER);
        }

        @Test
        @DisplayName("TEAM_LATEST_POSTS → SUPPORTER")
        void teamLatestPosts_SUPPORTER() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.TEAM_LATEST_POSTS))
                    .isEqualTo(MinRole.SUPPORTER);
        }

        @Test
        @DisplayName("TEAM_UNREAD_THREADS → MEMBER")
        void teamUnreadThreads_MEMBER() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.TEAM_UNREAD_THREADS))
                    .isEqualTo(MinRole.MEMBER);
        }

        @Test
        @DisplayName("TEAM_MEMBER_ATTENDANCE → MEMBER（ユーザー要件の発端）")
        void teamMemberAttendance_MEMBER() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.TEAM_MEMBER_ATTENDANCE))
                    .isEqualTo(MinRole.MEMBER);
        }

        @Test
        @DisplayName("TEAM_TOURNAMENT_RECORD → SUPPORTER（F08.7.1）")
        void teamTournamentRecord_SUPPORTER() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.TEAM_TOURNAMENT_RECORD))
                    .isEqualTo(MinRole.SUPPORTER);
        }

        @Test
        @DisplayName("TEAM_DIVISION_STANDINGS → SUPPORTER（F08.7.1）")
        void teamDivisionStandings_SUPPORTER() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.TEAM_DIVISION_STANDINGS))
                    .isEqualTo(MinRole.SUPPORTER);
        }

        @Test
        @DisplayName("TEAM_MATCH_SUMMARY → MEMBER（F08.10）")
        void teamMatchSummary_MEMBER() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.TEAM_MATCH_SUMMARY))
                    .isEqualTo(MinRole.MEMBER);
        }
    }

    // ========================================
    // 組織ダッシュボード
    // ========================================

    @Nested
    @DisplayName("getDefault: 組織ダッシュボードのデフォルト値")
    class OrgDefaults {

        @Test
        @DisplayName("ORG_TEAM_LIST → PUBLIC")
        void orgTeamList_PUBLIC() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.ORG_TEAM_LIST))
                    .isEqualTo(MinRole.PUBLIC);
        }

        @Test
        @DisplayName("ORG_NOTICES → PUBLIC")
        void orgNotices_PUBLIC() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.ORG_NOTICES))
                    .isEqualTo(MinRole.PUBLIC);
        }

        @Test
        @DisplayName("ORG_TODO → MEMBER")
        void orgTodo_MEMBER() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.ORG_TODO))
                    .isEqualTo(MinRole.MEMBER);
        }

        @Test
        @DisplayName("ORG_PROJECT_PROGRESS → MEMBER")
        void orgProjectProgress_MEMBER() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.ORG_PROJECT_PROGRESS))
                    .isEqualTo(MinRole.MEMBER);
        }

        @Test
        @DisplayName("ORG_STATS → SUPPORTER")
        void orgStats_SUPPORTER() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.ORG_STATS))
                    .isEqualTo(MinRole.SUPPORTER);
        }

        @Test
        @DisplayName("ORG_TOURNAMENT_SUMMARY → MEMBER（F08.7.1）")
        void orgTournamentSummary_MEMBER() {
            assertThat(WidgetDefaultMinRoleMap.getDefault(WidgetKey.ORG_TOURNAMENT_SUMMARY))
                    .isEqualTo(MinRole.MEMBER);
        }
    }

    // ========================================
    // ADMIN 限定ウィジェット
    // ========================================

    @Nested
    @DisplayName("isConfigurable: ADMIN 限定ウィジェットの判定")
    class AdminOnlyWidgets {

        @Test
        @DisplayName("TEAM_BILLING は管理対象外（isConfigurable=false）")
        void teamBilling_対象外() {
            assertThat(WidgetDefaultMinRoleMap.isConfigurable(WidgetKey.TEAM_BILLING)).isFalse();
        }

        @Test
        @DisplayName("TEAM_PAGE_VIEWS は管理対象外（isConfigurable=false）")
        void teamPageViews_対象外() {
            assertThat(WidgetDefaultMinRoleMap.isConfigurable(WidgetKey.TEAM_PAGE_VIEWS)).isFalse();
        }

        @Test
        @DisplayName("ORG_BILLING は管理対象外（isConfigurable=false）")
        void orgBilling_対象外() {
            assertThat(WidgetDefaultMinRoleMap.isConfigurable(WidgetKey.ORG_BILLING)).isFalse();
        }

        @Test
        @DisplayName("ADMIN_TEAM_MEMBERS は管理対象外（isConfigurable=false）")
        void adminTeamMembers_対象外() {
            assertThat(WidgetDefaultMinRoleMap.isConfigurable(WidgetKey.ADMIN_TEAM_MEMBERS)).isFalse();
        }

        @Test
        @DisplayName("ADMIN_TEAM_RESERVATIONS は管理対象外（isConfigurable=false）")
        void adminTeamReservations_対象外() {
            assertThat(WidgetDefaultMinRoleMap.isConfigurable(WidgetKey.ADMIN_TEAM_RESERVATIONS)).isFalse();
        }

        @Test
        @DisplayName("ADMIN_ORG_MEMBERS は管理対象外（isConfigurable=false）")
        void adminOrgMembers_対象外() {
            assertThat(WidgetDefaultMinRoleMap.isConfigurable(WidgetKey.ADMIN_ORG_MEMBERS)).isFalse();
        }

        @Test
        @DisplayName("ADMIN 限定ウィジェットへの getDefault は IllegalArgumentException")
        void getDefault_ADMIN限定_例外() {
            assertThatThrownBy(() -> WidgetDefaultMinRoleMap.getDefault(WidgetKey.TEAM_BILLING))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("管理対象外");
        }
    }

    // ========================================
    // 個人ダッシュボード（対象外）
    // ========================================

    @Nested
    @DisplayName("isConfigurable: 個人ダッシュボード用ウィジェット")
    class PersonalWidgets {

        @Test
        @DisplayName("PERSONAL ウィジェットは全て管理対象外")
        void personalWidgets_対象外() {
            assertThat(WidgetDefaultMinRoleMap.isConfigurable(WidgetKey.NOTICES)).isFalse();
            assertThat(WidgetDefaultMinRoleMap.isConfigurable(WidgetKey.PERSONAL_TODO)).isFalse();
            assertThat(WidgetDefaultMinRoleMap.isConfigurable(WidgetKey.MY_POSTS)).isFalse();
            assertThat(WidgetDefaultMinRoleMap.isConfigurable(WidgetKey.CHAT_HUB)).isFalse();
        }
    }

    // ========================================
    // null 入力
    // ========================================

    @Nested
    @DisplayName("null 入力")
    class NullInputs {

        @Test
        @DisplayName("getDefault(null) → IllegalArgumentException")
        void getDefault_null_例外() {
            assertThatThrownBy(() -> WidgetDefaultMinRoleMap.getDefault(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("isConfigurable(null) → false（NPE しない）")
        void isConfigurable_null_false() {
            assertThat(WidgetDefaultMinRoleMap.isConfigurable(null)).isFalse();
        }

        @Test
        @DisplayName("getDefaultsForScope(null) → IllegalArgumentException")
        void getDefaultsForScope_null_例外() {
            assertThatThrownBy(() -> WidgetDefaultMinRoleMap.getDefaultsForScope(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ========================================
    // getDefaultsForScope: スコープ別フィルタ
    // ========================================

    @Nested
    @DisplayName("getDefaultsForScope: スコープ別フィルタリング")
    class ScopeFiltering {

        @Test
        @DisplayName("TEAM スコープ → TEAM_* のみ含む（ORG_* と PERSONAL を含まない）")
        void team_TEAMキーのみ() {
            Map<WidgetKey, MinRole> result =
                    WidgetDefaultMinRoleMap.getDefaultsForScope(ScopeType.TEAM);

            // 全キーが TEAM スコープであること
            assertThat(result.keySet())
                    .allSatisfy(key -> assertThat(key.getScopeType()).isEqualTo(ScopeType.TEAM));

            // 想定 11 キーを網羅（F08.7.1 で 2 件 + F08.10 で 1 件追加）
            assertThat(result.keySet()).containsExactlyInAnyOrder(
                    WidgetKey.TEAM_NOTICES,
                    WidgetKey.TEAM_UPCOMING_EVENTS,
                    WidgetKey.TEAM_TODO,
                    WidgetKey.TEAM_PROJECT_PROGRESS,
                    WidgetKey.TEAM_ACTIVITY,
                    WidgetKey.TEAM_LATEST_POSTS,
                    WidgetKey.TEAM_UNREAD_THREADS,
                    WidgetKey.TEAM_MEMBER_ATTENDANCE,
                    WidgetKey.TEAM_TOURNAMENT_RECORD,
                    WidgetKey.TEAM_DIVISION_STANDINGS,
                    WidgetKey.TEAM_MATCH_SUMMARY);

            // ADMIN 限定は含まれない
            assertThat(result).doesNotContainKey(WidgetKey.TEAM_BILLING);
            assertThat(result).doesNotContainKey(WidgetKey.TEAM_PAGE_VIEWS);
            assertThat(result).doesNotContainKey(WidgetKey.ADMIN_TEAM_MEMBERS);
            assertThat(result).doesNotContainKey(WidgetKey.ADMIN_TEAM_RESERVATIONS);

            // 値が設計書通り
            assertThat(result.get(WidgetKey.TEAM_NOTICES)).isEqualTo(MinRole.PUBLIC);
            assertThat(result.get(WidgetKey.TEAM_MEMBER_ATTENDANCE)).isEqualTo(MinRole.MEMBER);
        }

        @Test
        @DisplayName("ORGANIZATION スコープ → ORG_* のみ含む（TEAM_* と PERSONAL を含まない）")
        void organization_ORGキーのみ() {
            Map<WidgetKey, MinRole> result =
                    WidgetDefaultMinRoleMap.getDefaultsForScope(ScopeType.ORGANIZATION);

            // 全キーが ORGANIZATION スコープ
            assertThat(result.keySet())
                    .allSatisfy(key ->
                            assertThat(key.getScopeType()).isEqualTo(ScopeType.ORGANIZATION));

            // 想定 6 キーを網羅（F08.7.1 で 1 件追加）
            assertThat(result.keySet()).containsExactlyInAnyOrder(
                    WidgetKey.ORG_TEAM_LIST,
                    WidgetKey.ORG_NOTICES,
                    WidgetKey.ORG_TODO,
                    WidgetKey.ORG_PROJECT_PROGRESS,
                    WidgetKey.ORG_STATS,
                    WidgetKey.ORG_TOURNAMENT_SUMMARY);

            // ADMIN 限定は含まれない
            assertThat(result).doesNotContainKey(WidgetKey.ORG_BILLING);
            assertThat(result).doesNotContainKey(WidgetKey.ADMIN_ORG_MEMBERS);
        }

        @Test
        @DisplayName("PERSONAL スコープ → 空マップ（個人ダッシュボードは対象外）")
        void personal_空マップ() {
            Map<WidgetKey, MinRole> result =
                    WidgetDefaultMinRoleMap.getDefaultsForScope(ScopeType.PERSONAL);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("TEAM スコープの結果は不変マップ")
        void team_不変マップ() {
            Map<WidgetKey, MinRole> result =
                    WidgetDefaultMinRoleMap.getDefaultsForScope(ScopeType.TEAM);
            assertThatThrownBy(() -> result.put(WidgetKey.TEAM_NOTICES, MinRole.MEMBER))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ========================================
    // getAllConfigurableKeys
    // ========================================

    @Nested
    @DisplayName("getAllConfigurableKeys / getAll")
    class AllKeys {

        @Test
        @DisplayName("全管理対象キーを返す（17 件）")
        void getAllConfigurableKeys_17件() {
            Set<WidgetKey> all = WidgetDefaultMinRoleMap.getAllConfigurableKeys();
            // TEAM 11 件 + ORG 6 件 = 17 件（F08.7.1 で 3 件 + F08.10 で 1 件追加）
            assertThat(all).hasSize(17);
        }

        @Test
        @DisplayName("ADMIN 限定ウィジェット（TEAM_BILLING / TEAM_PAGE_VIEWS / ORG_BILLING / ADMIN_* 3件）を含まない")
        void getAllConfigurableKeys_ADMIN限定除外() {
            Set<WidgetKey> all = WidgetDefaultMinRoleMap.getAllConfigurableKeys();
            assertThat(all)
                    .doesNotContain(WidgetKey.TEAM_BILLING)
                    .doesNotContain(WidgetKey.TEAM_PAGE_VIEWS)
                    .doesNotContain(WidgetKey.ORG_BILLING)
                    .doesNotContain(WidgetKey.ADMIN_TEAM_MEMBERS)
                    .doesNotContain(WidgetKey.ADMIN_TEAM_RESERVATIONS)
                    .doesNotContain(WidgetKey.ADMIN_ORG_MEMBERS);
        }

        @Test
        @DisplayName("getAll は getAllConfigurableKeys と一致する")
        void getAll_全件一致() {
            Map<WidgetKey, MinRole> all = WidgetDefaultMinRoleMap.getAll();
            assertThat(all.keySet()).isEqualTo(WidgetDefaultMinRoleMap.getAllConfigurableKeys());
        }
    }

    // ========================================
    // ロール制限ウィジェット（isRoleRestricted）の回帰検知
    // ========================================

    @Nested
    @DisplayName("isRoleRestricted と isConfigurable の整合性（回帰防止）")
    class RoleRestrictedConsistency {

        /**
         * WidgetKey.isRoleRestricted()=true のウィジェットは、
         * WidgetDefaultMinRoleMap の管理対象外（isConfigurable=false）でなければならない。
         *
         * <p>設計書 §2.4 手順4: ロール固定ウィジェットは min_role 変更不可。
         * 将来 ROLE_RESTRICTED セットに新規キーを追加した際に自動的にここで検知される。</p>
         */
        @ParameterizedTest(name = "{0} は isRoleRestricted=true のため isConfigurable=false")
        @EnumSource(value = WidgetKey.class, names = {
                "TEAM_BILLING",
                "TEAM_PAGE_VIEWS",
                "ORG_BILLING",
                "ADMIN_TEAM_MEMBERS",
                "ADMIN_TEAM_RESERVATIONS",
                "ADMIN_ORG_MEMBERS"
        })
        @DisplayName("ロール制限ウィジェットは isConfigurable=false")
        void roleRestrictedWidgets_isConfigurableFalse(WidgetKey key) {
            // 前提確認: テスト対象が実際に isRoleRestricted=true であること
            assertThat(key.isRoleRestricted())
                    .as("%s は ROLE_RESTRICTED セットに含まれているはず", key)
                    .isTrue();
            // 本命: isConfigurable は false であること
            assertThat(WidgetDefaultMinRoleMap.isConfigurable(key))
                    .as("%s は isRoleRestricted=true のため isConfigurable=false でなければならない", key)
                    .isFalse();
        }

        /**
         * isRoleRestricted=true の全キーが DEFAULTS マップに含まれないことを一括検証する。
         * @EnumSource の名前リストへの追記忘れを防ぐセーフネット。
         */
        @Test
        @DisplayName("isRoleRestricted=true の全ウィジェットが isConfigurable=false（網羅）")
        void allRoleRestrictedWidgets_isConfigurableFalse() {
            for (WidgetKey key : WidgetKey.values()) {
                if (key.isRoleRestricted()) {
                    assertThat(WidgetDefaultMinRoleMap.isConfigurable(key))
                            .as("%s は isRoleRestricted=true のため isConfigurable=false でなければならない", key)
                            .isFalse();
                }
            }
        }
    }
}
