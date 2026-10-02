package com.mannschaft.app.role.dto;

import java.util.List;

/** スコープ別 MEMBER 既定権限レスポンス。 */
public record MemberPermissionsResponse(
        String scopeType, Long scopeId, String roleName, List<MemberPermissionSetting> permissions) {
}
