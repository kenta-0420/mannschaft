package com.mannschaft.app.role.dto;

/** MEMBER 既定権限1件の設定。 */
public record MemberPermissionSetting(
        String name, String displayName, boolean enabled, boolean inherited) {
}
