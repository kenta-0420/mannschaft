package com.mannschaft.app.common.visibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserScopeRoleSnapshot} の単体テスト。
 *
 * <p>F00 Phase A-3b — メンバーシップスナップショットの判定メソッド
 * （isMemberOf / hasRoleOrAbove / isMemberOfParentOrg / isParentOrgInactive）
 * および factory（empty / forSystemAdmin）の挙動を網羅する。</p>
 */
@DisplayName("UserScopeRoleSnapshot — スナップショット判定メソッド")
class UserScopeRoleSnapshotTest {

    private static final ScopeKey TEAM_1 = new ScopeKey("TEAM", 1L);
    private static final ScopeKey TEAM_2 = new ScopeKey("TEAM", 2L);
    private static final ScopeKey ORG_10 = new ScopeKey("ORGANIZATION", 10L);
    private static final ScopeKey ORG_20 = new ScopeKey("ORGANIZATION", 20L);

    @Nested
    @DisplayName("factory: empty / forSystemAdmin")
    class Factories {

        @Test
        @DisplayName("empty() は SystemAdmin でなく全マップ・集合が空")
        void empty_全部空() {
            UserScopeRoleSnapshot s = UserScopeRoleSnapshot.empty();
            assertThat(s.isSystemAdmin()).isFalse();
            assertThat(s.roleByScope()).isEmpty();
            assertThat(s.parentOrgByScope()).isEmpty();
            assertThat(s.orgMemberOf()).isEmpty();
            assertThat(s.suspendedOrgIds()).isEmpty();
        }

        @Test
        @DisplayName("forSystemAdmin() は systemAdmin=true、後続データは空")
        void forSystemAdmin_systemAdminフラグのみtrue() {
            UserScopeRoleSnapshot s = UserScopeRoleSnapshot.forSystemAdmin();
            assertThat(s.isSystemAdmin()).isTrue();
            assertThat(s.roleByScope()).isEmpty();
            assertThat(s.parentOrgByScope()).isEmpty();
            assertThat(s.orgMemberOf()).isEmpty();
            assertThat(s.suspendedOrgIds()).isEmpty();
        }

        @Test
        @DisplayName("コンストラクタの null は空コレクションへ正規化される")
        void null引数は空コレクションへ正規化() {
            UserScopeRoleSnapshot s = new UserScopeRoleSnapshot(false, null, null, null, null);
            assertThat(s.roleByScope()).isEmpty();
            assertThat(s.parentOrgByScope()).isEmpty();
            assertThat(s.orgMemberOf()).isEmpty();
            assertThat(s.suspendedOrgIds()).isEmpty();
        }
    }

    @Nested
    @DisplayName("isMemberOf(scope)")
    class IsMemberOf {

        @Test
        @DisplayName("SystemAdmin は常に true")
        void SystemAdminは常にtrue() {
            UserScopeRoleSnapshot s = UserScopeRoleSnapshot.forSystemAdmin();
            assertThat(s.isMemberOf(TEAM_1)).isTrue();
            assertThat(s.isMemberOf(ORG_10)).isTrue();
        }

        @Test
        @DisplayName("roleByScope に含まれるスコープのみ true")
        void roleByScopeに含まれるスコープのみtrue() {
            UserScopeRoleSnapshot s = new UserScopeRoleSnapshot(
                    false,
                    Map.of(TEAM_1, "MEMBER"),
                    Map.of(),
                    Set.of(),
                    Set.of());
            assertThat(s.isMemberOf(TEAM_1)).isTrue();
            assertThat(s.isMemberOf(TEAM_2)).isFalse();
            assertThat(s.isMemberOf(ORG_10)).isFalse();
        }

        @Test
        @DisplayName("null スコープは false")
        void null_false() {
            UserScopeRoleSnapshot s = UserScopeRoleSnapshot.empty();
            assertThat(s.isMemberOf(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("hasRoleOrAbove(scope, required)")
    class HasRoleOrAbove {

        @Test
        @DisplayName("SystemAdmin は要求ロールに関係なく常に true")
        void SystemAdminは常にtrue() {
            UserScopeRoleSnapshot s = UserScopeRoleSnapshot.forSystemAdmin();
            assertThat(s.hasRoleOrAbove(TEAM_1, "ADMIN")).isTrue();
            assertThat(s.hasRoleOrAbove(ORG_10, "GUEST")).isTrue();
        }

        @Test
        @DisplayName("ADMIN ロール保持で MEMBER 要求は満たす")
        void ADMINで_MEMBER要求は満たす() {
            UserScopeRoleSnapshot s = new UserScopeRoleSnapshot(
                    false,
                    Map.of(TEAM_1, "ADMIN"),
                    Map.of(),
                    Set.of(),
                    Set.of());
            assertThat(s.hasRoleOrAbove(TEAM_1, "MEMBER")).isTrue();
            assertThat(s.hasRoleOrAbove(TEAM_1, "ADMIN")).isTrue();
        }

        @Test
        @DisplayName("MEMBER ロール保持で ADMIN 要求は満たさない")
        void MEMBERでADMIN要求は満たさない() {
            UserScopeRoleSnapshot s = new UserScopeRoleSnapshot(
                    false,
                    Map.of(TEAM_1, "MEMBER"),
                    Map.of(),
                    Set.of(),
                    Set.of());
            assertThat(s.hasRoleOrAbove(TEAM_1, "ADMIN")).isFalse();
            assertThat(s.hasRoleOrAbove(TEAM_1, "DEPUTY_ADMIN")).isFalse();
        }

        @Test
        @DisplayName("メンバーでないスコープは false")
        void メンバーでないスコープはfalse() {
            UserScopeRoleSnapshot s = new UserScopeRoleSnapshot(
                    false,
                    Map.of(TEAM_1, "ADMIN"),
                    Map.of(),
                    Set.of(),
                    Set.of());
            assertThat(s.hasRoleOrAbove(TEAM_2, "MEMBER")).isFalse();
        }

        @Test
        @DisplayName("null スコープは false")
        void null_false() {
            UserScopeRoleSnapshot s = UserScopeRoleSnapshot.empty();
            assertThat(s.hasRoleOrAbove(null, "MEMBER")).isFalse();
        }
    }

    @Nested
    @DisplayName("isMemberOfParentOrg(scope)")
    class IsMemberOfParentOrg {

        @Test
        @DisplayName("SystemAdmin は常に true")
        void SystemAdminは常にtrue() {
            UserScopeRoleSnapshot s = UserScopeRoleSnapshot.forSystemAdmin();
            assertThat(s.isMemberOfParentOrg(TEAM_1)).isTrue();
        }

        @Test
        @DisplayName("親 ORG マップに該当 + 親 ORG メンバー集合に含まれれば true")
        void 親ORGメンバーシップでtrue() {
            UserScopeRoleSnapshot s = new UserScopeRoleSnapshot(
                    false,
                    Map.of(),
                    Map.of(TEAM_1, 10L),
                    Set.of(ORG_10),
                    Set.of());
            assertThat(s.isMemberOfParentOrg(TEAM_1)).isTrue();
        }

        @Test
        @DisplayName("親 ORG メンバーでなければ false")
        void 親ORG非メンバーはfalse() {
            UserScopeRoleSnapshot s = new UserScopeRoleSnapshot(
                    false,
                    Map.of(),
                    Map.of(TEAM_1, 10L),
                    Set.of(),
                    Set.of());
            assertThat(s.isMemberOfParentOrg(TEAM_1)).isFalse();
        }

        @Test
        @DisplayName("親 ORG マップに無いスコープは false")
        void 親ORGマップ未収録はfalse() {
            UserScopeRoleSnapshot s = new UserScopeRoleSnapshot(
                    false,
                    Map.of(),
                    Map.of(),
                    Set.of(ORG_10),
                    Set.of());
            assertThat(s.isMemberOfParentOrg(TEAM_1)).isFalse();
        }

        @Test
        @DisplayName("ORGANIZATION 自身が parentOrgByScope に登録されていれば判定対象")
        void ORG自身も判定対象() {
            UserScopeRoleSnapshot s = new UserScopeRoleSnapshot(
                    false,
                    Map.of(),
                    Map.of(ORG_10, 10L),
                    Set.of(ORG_10),
                    Set.of());
            assertThat(s.isMemberOfParentOrg(ORG_10)).isTrue();
        }
    }

    @Nested
    @DisplayName("isParentOrgInactive(scope) — §11.6 連鎖ルール")
    class IsParentOrgInactive {

        @Test
        @DisplayName("親 ORG が suspendedOrgIds に含まれれば true")
        void 親ORG非アクティブはtrue() {
            UserScopeRoleSnapshot s = new UserScopeRoleSnapshot(
                    false,
                    Map.of(),
                    Map.of(TEAM_1, 10L),
                    Set.of(),
                    Set.of(10L));
            assertThat(s.isParentOrgInactive(TEAM_1)).isTrue();
        }

        @Test
        @DisplayName("親 ORG がアクティブなら false")
        void 親ORGアクティブはfalse() {
            UserScopeRoleSnapshot s = new UserScopeRoleSnapshot(
                    false,
                    Map.of(),
                    Map.of(TEAM_1, 10L),
                    Set.of(),
                    Set.of(20L));
            assertThat(s.isParentOrgInactive(TEAM_1)).isFalse();
        }

        @Test
        @DisplayName("親 ORG マップに無いスコープは false（不明）")
        void 親ORGマップ未収録はfalse() {
            UserScopeRoleSnapshot s = new UserScopeRoleSnapshot(
                    false,
                    Map.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(10L));
            assertThat(s.isParentOrgInactive(TEAM_1)).isFalse();
        }

        @Test
        @DisplayName("SystemAdmin であっても非アクティブ判定自体は事実通りに返す")
        void SystemAdminも事実通り() {
            // SystemAdmin の高速パスでは parentOrgByScope/suspendedOrgIds が空なので false。
            // §11.6 の運用としては Resolver 側で「SystemAdmin OR !isParentOrgInactive」と組む。
            UserScopeRoleSnapshot s = UserScopeRoleSnapshot.forSystemAdmin();
            assertThat(s.isParentOrgInactive(TEAM_1)).isFalse();
        }
    }
}
