package com.mannschaft.app.role.authorization;

import java.util.Map;

/** ロールの優先度規則。rolesのseedと一致し、小さい値を上位、不明値を最弱とする。 */
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

    /** ロール名の優先度を返す。null・不明ロールは最弱扱いにする。 */
    public static int priority(String roleName) {
        if (roleName == null) {
            return Integer.MAX_VALUE;
        }
        Integer p = PRIORITY_MAP.get(roleName);
        return p != null ? p : Integer.MAX_VALUE;
    }
}
