package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.WidgetKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

            // 想定 8 キーを網羅
            assertThat(result.keySet()).containsExactlyInAnyOrder(
                    WidgetKey.TEAM_NOTICES,
                    WidgetKey.TEAM_UPCOMING_EVENTS,
                    WidgetKey.TEAM_TODO,
                    WidgetKey.TEAM_PROJECT_PROGRESS,
                    WidgetKey.TEAM_ACTIVITY,
                    WidgetKey.TEAM_LATEST_POSTS,
                    WidgetKey.TEAM_UNREAD_THREADS,
                    WidgetKey.TEAM_MEMBER_ATTENDANCE);

            // ADMIN 限定は含まれない
            assertThat(result).doesNotContainKey(WidgetKey.TEAM_BILLING);
            assertThat(result).doesNotContainKey(WidgetKey.TEAM_PAGE_VIEWS);

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

            // 想定 5 キーを網羅
            assertThat(result.keySet()).containsExactlyInAnyOrder(
                    WidgetKey.ORG_TEAM_LIST,
                    WidgetKey.ORG_NOTICES,
                    WidgetKey.ORG_TODO,
                    WidgetKey.ORG_PROJECT_PROGRESS,
                    WidgetKey.ORG_STATS);

            // ADMIN 限定は含まれない
            assertThat(result).doesNotContainKey(WidgetKey.ORG_BILLING);
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
        @DisplayName("全管理対象キーを返す（13 件）")
        void getAllConfigurableKeys_13件() {
            Set<WidgetKey> all = WidgetDefaultMinRoleMap.getAllConfigurableKeys();
            // TEAM 8 件 + ORG 5 件 = 13 件
            assertThat(all).hasSize(13);
        }

        @Test
        @DisplayName("ADMIN 限定ウィジェット（TEAM_BILLING / TEAM_PAGE_VIEWS / ORG_BILLING）を含まない")
        void getAllConfigurableKeys_ADMIN限定除外() {
            Set<WidgetKey> all = WidgetDefaultMinRoleMap.getAllConfigurableKeys();
            assertThat(all)
                    .doesNotContain(WidgetKey.TEAM_BILLING)
                    .doesNotContain(WidgetKey.TEAM_PAGE_VIEWS)
                    .doesNotContain(WidgetKey.ORG_BILLING);
        }

        @Test
        @DisplayName("getAll は getAllConfigurableKeys と一致する")
        void getAll_全件一致() {
            Map<WidgetKey, MinRole> all = WidgetDefaultMinRoleMap.getAll();
            assertThat(all.keySet()).isEqualTo(WidgetDefaultMinRoleMap.getAllConfigurableKeys());
        }
    }
}
