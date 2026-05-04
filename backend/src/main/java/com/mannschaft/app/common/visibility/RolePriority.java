package com.mannschaft.app.common.visibility;

import java.util.Map;

/**
 * ロール名の優先度マップ。値が小さいほど上位。
 *
 * <p>F00 共通可視性基盤のメンバーシップ判定（{@code MembershipBatchQueryService} および
 * {@code UserScopeRoleSnapshot.hasRoleOrAbove}）で利用される。</p>
 *
 * <p>本クラスの値は {@code roles} テーブルの {@code priority} 列および
 * {@code AccessControlService.hasRoleOrAbove(...)} の昇格ロジックと完全一致させる。
 * いずれかを変更する際はもう一方も同時に追従させること。</p>
 *
 * <p>現行値（Flyway {@code V2.014__seed_roles.sql} の seed と一致）:</p>
 * <pre>
 *   SYSTEM_ADMIN  = 1
 *   ADMIN         = 2
 *   DEPUTY_ADMIN  = 3
 *   MEMBER        = 4
 *   SUPPORTER     = 5
 *   GUEST         = 6
 * </pre>
 *
 * <p>「以上」判定は {@code priority(候補) <= priority(必要)}。</p>
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §10.2 / §15 D-14。</p>
 */
public final class RolePriority {

    private static final Map<String, Integer> PRIORITY_MAP = Map.of(
            "SYSTEM_ADMIN", 1,
            "ADMIN", 2,
            "DEPUTY_ADMIN", 3,
            "MEMBER", 4,
            "SUPPORTER", 5,
            "GUEST", 6);

    private RolePriority() {
        throw new AssertionError("utility class");
    }

    /**
     * ロール名の優先度を返す。不明ロールは {@link Integer#MAX_VALUE} を返す（最弱扱い）。
     */
    public static int priority(String roleName) {
        if (roleName == null) {
            return Integer.MAX_VALUE;
        }
        Integer p = PRIORITY_MAP.get(roleName);
        return p != null ? p : Integer.MAX_VALUE;
    }

    /**
     * {@code actual} が {@code required} 以上のロールであるかを判定する。
     * 「以上」とは数値上 {@code priority(actual) <= priority(required)} を満たすこと。
     *
     * @param actual   判定対象のロール名
     * @param required 必要ロール名
     * @return {@code actual} が {@code required} 以上ならば {@code true}
     */
    public static boolean isAtLeast(String actual, String required) {
        return priority(actual) <= priority(required);
    }
}
