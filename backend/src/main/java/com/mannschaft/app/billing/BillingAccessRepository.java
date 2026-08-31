package com.mannschaft.app.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 利用者向け課金 API 専用の認可 query。
 *
 * <p>ロール権限のキャッシュや {@code role_permissions} を経由せず、毎要求で現在の
 * scope ロールと permission group の明示付与を読み取る。</p>
 */
@Repository
@RequiredArgsConstructor
public class BillingAccessRepository {

    private static final String ADMIN_TEAM_SQL = """
            SELECT COUNT(*)
              FROM user_roles ur
              JOIN roles r ON r.id = ur.role_id
              JOIN users u ON u.id = ur.user_id
             WHERE ur.user_id = ?
               AND ur.team_id = ?
               AND ur.organization_id IS NULL
               AND r.name = 'ADMIN'
               AND u.deleted_at IS NULL
               AND u.status = 'ACTIVE'
            """;

    private static final String ADMIN_ORG_SQL = """
            SELECT COUNT(*)
              FROM user_roles ur
              JOIN roles r ON r.id = ur.role_id
              JOIN users u ON u.id = ur.user_id
             WHERE ur.user_id = ?
               AND ur.organization_id = ?
               AND ur.team_id IS NULL
               AND r.name = 'ADMIN'
               AND u.deleted_at IS NULL
               AND u.status = 'ACTIVE'
            """;

    private static final String DEPUTY_TEAM_PERMISSION_SQL = """
            SELECT COUNT(*)
              FROM user_roles ur
              JOIN roles r ON r.id = ur.role_id
              JOIN users u ON u.id = ur.user_id
              JOIN user_permission_groups upg ON upg.user_id = ur.user_id
              JOIN permission_groups pg ON pg.id = upg.group_id
              JOIN permission_group_permissions pgp ON pgp.group_id = pg.id
              JOIN permissions p ON p.id = pgp.permission_id
             WHERE ur.user_id = ?
               AND ur.team_id = ?
               AND ur.organization_id IS NULL
               AND r.name = 'DEPUTY_ADMIN'
               AND pg.team_id = ur.team_id
               AND pg.organization_id IS NULL
               AND pg.target_role = 'DEPUTY_ADMIN'
               AND pg.deleted_at IS NULL
               AND p.name = ?
               AND p.scope = 'TEAM'
               AND u.deleted_at IS NULL
               AND u.status = 'ACTIVE'
            """;

    private static final String DEPUTY_ORG_PERMISSION_SQL = """
            SELECT COUNT(*)
              FROM user_roles ur
              JOIN roles r ON r.id = ur.role_id
              JOIN users u ON u.id = ur.user_id
              JOIN user_permission_groups upg ON upg.user_id = ur.user_id
              JOIN permission_groups pg ON pg.id = upg.group_id
              JOIN permission_group_permissions pgp ON pgp.group_id = pg.id
              JOIN permissions p ON p.id = pgp.permission_id
             WHERE ur.user_id = ?
               AND ur.organization_id = ?
               AND ur.team_id IS NULL
               AND r.name = 'DEPUTY_ADMIN'
               AND pg.organization_id = ur.organization_id
               AND pg.team_id IS NULL
               AND pg.target_role = 'DEPUTY_ADMIN'
               AND pg.deleted_at IS NULL
               AND p.name = ?
               AND p.scope = 'ORGANIZATION'
               AND u.deleted_at IS NULL
               AND u.status = 'ACTIVE'
            """;

    private final JdbcTemplate jdbcTemplate;

    public boolean existsAdmin(Long userId, EntitlementScopeKind scopeKind, Long scopeId) {
        if (userId == null || scopeKind == null || scopeId == null) {
            return false;
        }
        return switch (scopeKind) {
            case TEAM -> count(ADMIN_TEAM_SQL, userId, scopeId) > 0;
            case ORG -> count(ADMIN_ORG_SQL, userId, scopeId) > 0;
            case USER -> false;
        };
    }

    public boolean existsDeputyPermissionGroup(
            Long userId,
            EntitlementScopeKind scopeKind,
            Long scopeId,
            String permissionName) {
        if (userId == null || scopeKind == null || scopeId == null || permissionName == null) {
            return false;
        }
        return switch (scopeKind) {
            case TEAM -> count(DEPUTY_TEAM_PERMISSION_SQL, userId, scopeId, permissionName) > 0;
            case ORG -> count(DEPUTY_ORG_PERMISSION_SQL, userId, scopeId, permissionName) > 0;
            case USER -> false;
        };
    }

    private long count(String sql, Object... args) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, args);
        return result == null ? 0 : result;
    }
}
