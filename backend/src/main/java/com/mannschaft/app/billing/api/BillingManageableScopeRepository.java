package com.mannschaft.app.billing.api;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * F20.1 課金履歴 API（AC-55/AC-56）— 利用者が「課金を管理できる」scope の列挙。
 *
 * <p>各 SQL は {@link com.mannschaft.app.billing.BillingAccessRepository} の
 * 判定 SQL と <b>同じ条件</b>を、単一 scope の存在判定から集合の列挙へ書き換えたものである
 * （ADMIN 行、又は同一 scope の permission group 経由で課金権限を明示付与された
 * DEPUTY_ADMIN）。列挙が認可の真実源になってはならないため、列挙結果は
 * {@link BillingAccessGuard} で必ず再検証してから返す（AC-56）。</p>
 *
 * <p>SYSTEM_ADMIN の権限文字列による短絡許可は<b>持たない</b>。ここに短絡を入れると
 * 消費者向け API から全 scope が列挙できてしまう。</p>
 */
@Repository
@RequiredArgsConstructor
public class BillingManageableScopeRepository {

    private static final String ADMIN_TEAM_IDS_SQL = """
            SELECT DISTINCT ur.team_id AS scope_id, t.name AS scope_name
              FROM user_roles ur
              JOIN roles r ON r.id = ur.role_id
              JOIN users u ON u.id = ur.user_id
              JOIN teams t ON t.id = ur.team_id AND t.deleted_at IS NULL
             WHERE ur.user_id = ?
               AND ur.team_id IS NOT NULL
               AND ur.organization_id IS NULL
               AND r.name = 'ADMIN'
               AND u.deleted_at IS NULL
               AND u.status = 'ACTIVE'
            """;

    private static final String ADMIN_ORG_IDS_SQL = """
            SELECT DISTINCT ur.organization_id AS scope_id, o.name AS scope_name
              FROM user_roles ur
              JOIN roles r ON r.id = ur.role_id
              JOIN users u ON u.id = ur.user_id
              JOIN organizations o ON o.id = ur.organization_id AND o.deleted_at IS NULL
             WHERE ur.user_id = ?
               AND ur.organization_id IS NOT NULL
               AND ur.team_id IS NULL
               AND r.name = 'ADMIN'
               AND u.deleted_at IS NULL
               AND u.status = 'ACTIVE'
            """;

    private static final String DEPUTY_TEAM_IDS_SQL = """
            SELECT DISTINCT ur.team_id AS scope_id, t.name AS scope_name
              FROM user_roles ur
              JOIN roles r ON r.id = ur.role_id
              JOIN users u ON u.id = ur.user_id
              JOIN teams t ON t.id = ur.team_id AND t.deleted_at IS NULL
              JOIN user_permission_groups upg ON upg.user_id = ur.user_id
              JOIN permission_groups pg ON pg.id = upg.group_id
              JOIN permission_group_permissions pgp ON pgp.group_id = pg.id
              JOIN permissions p ON p.id = pgp.permission_id
             WHERE ur.user_id = ?
               AND ur.team_id IS NOT NULL
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

    private static final String DEPUTY_ORG_IDS_SQL = """
            SELECT DISTINCT ur.organization_id AS scope_id, o.name AS scope_name
              FROM user_roles ur
              JOIN roles r ON r.id = ur.role_id
              JOIN users u ON u.id = ur.user_id
              JOIN organizations o ON o.id = ur.organization_id AND o.deleted_at IS NULL
              JOIN user_permission_groups upg ON upg.user_id = ur.user_id
              JOIN permission_groups pg ON pg.id = upg.group_id
              JOIN permission_group_permissions pgp ON pgp.group_id = pg.id
              JOIN permissions p ON p.id = pgp.permission_id
             WHERE ur.user_id = ?
               AND ur.organization_id IS NOT NULL
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

    private static final RowMapper<ManageableScopeRow> ROW_MAPPER =
            (rs, rowNum) -> new ManageableScopeRow(
                    rs.getLong("scope_id"), rs.getString("scope_name"));

    private final JdbcTemplate jdbcTemplate;

    /** 課金を管理できる TEAM（ADMIN 又は課金権限付き DEPUTY_ADMIN）。 */
    public List<ManageableScopeRow> findManageableTeams(long userId, String teamPermissionName) {
        return merge(
                jdbcTemplate.query(ADMIN_TEAM_IDS_SQL, ROW_MAPPER, userId),
                jdbcTemplate.query(DEPUTY_TEAM_IDS_SQL, ROW_MAPPER, userId, teamPermissionName));
    }

    /** 課金を管理できる ORG（ADMIN 又は課金権限付き DEPUTY_ADMIN）。 */
    public List<ManageableScopeRow> findManageableOrganizations(long userId, String orgPermissionName) {
        return merge(
                jdbcTemplate.query(ADMIN_ORG_IDS_SQL, ROW_MAPPER, userId),
                jdbcTemplate.query(DEPUTY_ORG_IDS_SQL, ROW_MAPPER, userId, orgPermissionName));
    }

    /** ADMIN 経路と DEPUTY_ADMIN 経路の重複を id で除いて結合する。 */
    private static List<ManageableScopeRow> merge(
            List<ManageableScopeRow> first, List<ManageableScopeRow> second) {
        List<ManageableScopeRow> merged = new ArrayList<>(first);
        Set<Long> seen = new HashSet<>();
        for (ManageableScopeRow row : first) {
            seen.add(row.id());
        }
        for (ManageableScopeRow row : second) {
            if (seen.add(row.id())) {
                merged.add(row);
            }
        }
        return merged;
    }

    /**
     * 列挙された scope の1件。
     *
     * @param id   scope ID
     * @param name 表示名（発行元マスタの現在値）
     */
    public record ManageableScopeRow(Long id, String name) {
    }
}
