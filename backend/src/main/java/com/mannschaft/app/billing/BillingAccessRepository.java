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

    /**
     * 当該 TEAM に何らかのロールを持つ（＝そのスコープの構成員である）か。
     * ロール名は問わない。403（権限不足）と 404（存在秘匿）を撃ち分けるためだけに使う。
     */
    private static final String ANY_ROLE_TEAM_SQL = """
            SELECT COUNT(*)
              FROM user_roles ur
              JOIN users u ON u.id = ur.user_id
             WHERE ur.user_id = ?
               AND ur.team_id = ?
               AND ur.organization_id IS NULL
               AND u.deleted_at IS NULL
               AND u.status = 'ACTIVE'
            """;

    /** {@link #ANY_ROLE_TEAM_SQL} の ORG 版。 */
    private static final String ANY_ROLE_ORG_SQL = """
            SELECT COUNT(*)
              FROM user_roles ur
              JOIN users u ON u.id = ur.user_id
             WHERE ur.user_id = ?
               AND ur.organization_id = ?
               AND ur.team_id IS NULL
               AND u.deleted_at IS NULL
               AND u.status = 'ACTIVE'
            """;

    private static final String LOCK_TEAM_PERMISSION_GROUPS_SQL = """
            SELECT pg.id
              FROM user_permission_groups upg
              JOIN permission_groups pg ON pg.id = upg.group_id
             WHERE upg.user_id = ?
               AND pg.team_id = ?
               AND pg.organization_id IS NULL
               AND pg.deleted_at IS NULL
             ORDER BY pg.id
             FOR UPDATE
            """;

    private static final String LOCK_ORG_PERMISSION_GROUPS_SQL = """
            SELECT pg.id
              FROM user_permission_groups upg
              JOIN permission_groups pg ON pg.id = upg.group_id
             WHERE upg.user_id = ?
               AND pg.organization_id = ?
               AND pg.team_id IS NULL
               AND pg.deleted_at IS NULL
             ORDER BY pg.id
             FOR UPDATE
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

    /**
     * 操作者が当該 scope に何らかのロールを持つ構成員かどうか（ロール名は問わない）。
     *
     * <p><b>用途は 403 と 404 の撃ち分けだけ</b>である（PR6a AC-51 / AC-52）。スコープの外の
     * 利用者には契約 ID の存在を悟らせない（404 で畳む）が、スコープの内側にいて権限だけが
     * 足りない利用者へ 404 を返すと「無い」と誤解させるため 403 を返す。許可判定そのものは
     * {@link #existsAdmin} / {@link #existsDeputyPermissionGroup} が行い、本メソッドは
     * <b>許可を一切与えない</b>。
     *
     * @param userId    操作者
     * @param scopeKind scope 種別（USER は呼び出し元が本人判定するため常に false）
     * @param scopeId   scope ID
     * @return 当該 scope の構成員なら true
     */
    public boolean existsScopeRole(Long userId, EntitlementScopeKind scopeKind, Long scopeId) {
        if (userId == null || scopeKind == null || scopeId == null) {
            return false;
        }
        return switch (scopeKind) {
            case TEAM -> count(ANY_ROLE_TEAM_SQL, userId, scopeId) > 0;
            case ORG -> count(ANY_ROLE_ORG_SQL, userId, scopeId) > 0;
            case USER -> false;
        };
    }

    /** 契約変更txで、現在割当済みの同一scope permission group行を決定順にロックする。 */
    public void lockAssignedPermissionGroups(
            Long userId,
            EntitlementScopeKind scopeKind,
            Long scopeId) {
        if (userId == null || scopeKind == null || scopeId == null) {
            return;
        }
        switch (scopeKind) {
            case TEAM -> jdbcTemplate.queryForList(
                    LOCK_TEAM_PERMISSION_GROUPS_SQL, Long.class, userId, scopeId);
            case ORG -> jdbcTemplate.queryForList(
                    LOCK_ORG_PERMISSION_GROUPS_SQL, Long.class, userId, scopeId);
            case USER -> {
                // USER scopeにpermission group委譲はない。
            }
        }
    }

    private long count(String sql, Object... args) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, args);
        return result == null ? 0 : result;
    }
}
