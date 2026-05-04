package com.mannschaft.app.common.visibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RolePriority} の単体テスト。
 *
 * <p>F00 ContentVisibilityResolver Phase A-3b — ロール優先度マップが
 * {@code roles} テーブルの seed および {@code AccessControlService.hasRoleOrAbove}
 * の挙動と完全一致することを保証する。</p>
 */
@DisplayName("RolePriority — 6 ロール優先度マップ")
class RolePriorityTest {

    @Nested
    @DisplayName("priority(roleName)")
    class Priority {

        @Test
        @DisplayName("DB seed と同じ値を返す（SYSTEM_ADMIN=1 〜 GUEST=6）")
        void DB_seedと同じ値を返す() {
            assertThat(RolePriority.priority("SYSTEM_ADMIN")).isEqualTo(1);
            assertThat(RolePriority.priority("ADMIN")).isEqualTo(2);
            assertThat(RolePriority.priority("DEPUTY_ADMIN")).isEqualTo(3);
            assertThat(RolePriority.priority("MEMBER")).isEqualTo(4);
            assertThat(RolePriority.priority("SUPPORTER")).isEqualTo(5);
            assertThat(RolePriority.priority("GUEST")).isEqualTo(6);
        }

        @Test
        @DisplayName("不明ロール名は MAX_VALUE（最弱扱い）を返す")
        void 不明ロール名はMAX_VALUE() {
            assertThat(RolePriority.priority("UNKNOWN_ROLE")).isEqualTo(Integer.MAX_VALUE);
            assertThat(RolePriority.priority("")).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("null も MAX_VALUE を返し例外にならない")
        void null許容() {
            assertThat(RolePriority.priority(null)).isEqualTo(Integer.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("isAtLeast(actual, required)")
    class IsAtLeast {

        @Test
        @DisplayName("SYSTEM_ADMIN は他全てのロール以上")
        void SYSTEM_ADMINは全ロール以上() {
            assertThat(RolePriority.isAtLeast("SYSTEM_ADMIN", "SYSTEM_ADMIN")).isTrue();
            assertThat(RolePriority.isAtLeast("SYSTEM_ADMIN", "ADMIN")).isTrue();
            assertThat(RolePriority.isAtLeast("SYSTEM_ADMIN", "DEPUTY_ADMIN")).isTrue();
            assertThat(RolePriority.isAtLeast("SYSTEM_ADMIN", "MEMBER")).isTrue();
            assertThat(RolePriority.isAtLeast("SYSTEM_ADMIN", "SUPPORTER")).isTrue();
            assertThat(RolePriority.isAtLeast("SYSTEM_ADMIN", "GUEST")).isTrue();
        }

        @Test
        @DisplayName("ADMIN は SYSTEM_ADMIN 未満、それ以下は以上")
        void ADMINの境界() {
            assertThat(RolePriority.isAtLeast("ADMIN", "SYSTEM_ADMIN")).isFalse();
            assertThat(RolePriority.isAtLeast("ADMIN", "ADMIN")).isTrue();
            assertThat(RolePriority.isAtLeast("ADMIN", "DEPUTY_ADMIN")).isTrue();
            assertThat(RolePriority.isAtLeast("ADMIN", "MEMBER")).isTrue();
        }

        @Test
        @DisplayName("DEPUTY_ADMIN は ADMIN 未満、MEMBER 以上")
        void DEPUTY_ADMINの境界() {
            assertThat(RolePriority.isAtLeast("DEPUTY_ADMIN", "ADMIN")).isFalse();
            assertThat(RolePriority.isAtLeast("DEPUTY_ADMIN", "DEPUTY_ADMIN")).isTrue();
            assertThat(RolePriority.isAtLeast("DEPUTY_ADMIN", "MEMBER")).isTrue();
            assertThat(RolePriority.isAtLeast("DEPUTY_ADMIN", "SUPPORTER")).isTrue();
        }

        @Test
        @DisplayName("MEMBER は DEPUTY_ADMIN 未満、SUPPORTER 以上")
        void MEMBERの境界() {
            assertThat(RolePriority.isAtLeast("MEMBER", "DEPUTY_ADMIN")).isFalse();
            assertThat(RolePriority.isAtLeast("MEMBER", "MEMBER")).isTrue();
            assertThat(RolePriority.isAtLeast("MEMBER", "SUPPORTER")).isTrue();
            assertThat(RolePriority.isAtLeast("MEMBER", "GUEST")).isTrue();
        }

        @Test
        @DisplayName("SUPPORTER は MEMBER 未満、GUEST 以上")
        void SUPPORTERの境界() {
            assertThat(RolePriority.isAtLeast("SUPPORTER", "MEMBER")).isFalse();
            assertThat(RolePriority.isAtLeast("SUPPORTER", "SUPPORTER")).isTrue();
            assertThat(RolePriority.isAtLeast("SUPPORTER", "GUEST")).isTrue();
        }

        @Test
        @DisplayName("GUEST は SUPPORTER 未満、自身のみ以上")
        void GUESTの境界() {
            assertThat(RolePriority.isAtLeast("GUEST", "SUPPORTER")).isFalse();
            assertThat(RolePriority.isAtLeast("GUEST", "GUEST")).isTrue();
            assertThat(RolePriority.isAtLeast("GUEST", "MEMBER")).isFalse();
        }

        @Test
        @DisplayName("不明ロール / null は要求ロール未満")
        void 不明ロールnullは要求ロール未満() {
            assertThat(RolePriority.isAtLeast("UNKNOWN", "GUEST")).isFalse();
            assertThat(RolePriority.isAtLeast(null, "GUEST")).isFalse();
        }
    }
}
