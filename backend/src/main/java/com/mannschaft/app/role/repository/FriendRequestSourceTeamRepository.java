package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.UserRoleEntity;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 申請元の現在状態を単一のrole所有SQLで読む専用境界。
 * 他ドメインのEntity/Repositoryを参照せず、本人所属とチーム状態を絞る物理read JOINからscalarだけ返す。
 * 実効ロールと権限はRoleService.resolveEffectivePermissionsと同じ規則を用いる。
 */
public interface FriendRequestSourceTeamRepository extends Repository<UserRoleEntity, Long> {
    String SCOPED_TEAMS = """

            SELECT m.scope_id AS team_id, t.slug AS team_slug, t.name AS team_name,
                CASE WHEN COALESCE(CASE sr.name
                    WHEN 'SYSTEM_ADMIN' THEN 100 WHEN 'ADMIN' THEN 90 WHEN 'DEPUTY_ADMIN' THEN 80
                    WHEN 'MEMBER' THEN 50 WHEN 'GUEST' THEN 10 ELSE 0 END, -1)
                    >= CASE m.role_kind WHEN 'SYSTEM_ADMIN' THEN 100 WHEN 'ADMIN' THEN 90
                        WHEN 'DEPUTY_ADMIN' THEN 80 WHEN 'MEMBER' THEN 50 WHEN 'GUEST' THEN 10 ELSE 0 END
                    THEN sr.name ELSE m.role_kind END AS effective_role
            FROM memberships m
            JOIN users u ON u.id = m.user_id
            JOIN teams t ON t.id = m.scope_id
            LEFT JOIN user_roles ur ON ur.user_id = m.user_id AND ur.team_id = m.scope_id
            LEFT JOIN roles sr ON sr.id = ur.role_id
            WHERE m.user_id = :userId AND m.scope_type = 'TEAM'
                AND m.left_at IS NULL AND m.archived_at IS NULL
                AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                AND t.lifecycle_status = 'ACTIVE' AND t.deleted_at IS NULL AND t.archived_at IS NULL
            """;
    String PERMISSION_FILTER = """
            WHERE (
                    (s.effective_role = 'ADMIN' AND EXISTS (
                        SELECT 1 FROM role_permissions rp JOIN roles r ON r.id = rp.role_id
                        JOIN permissions p ON p.id = rp.permission_id
                        WHERE r.name = 'ADMIN' AND p.name = 'MANAGE_FRIEND_TEAMS' AND rp.is_default = TRUE))
                    OR (s.effective_role IN ('DEPUTY_ADMIN', 'MEMBER') AND EXISTS (
                        SELECT 1 FROM user_permission_groups upg JOIN permission_groups pg ON pg.id = upg.group_id
                        JOIN permission_group_permissions pgp ON pgp.group_id = pg.id
                        JOIN permissions p ON p.id = pgp.permission_id
                        WHERE upg.user_id = :userId AND pg.team_id = s.team_id AND pg.deleted_at IS NULL
                            AND pg.target_role = s.effective_role AND p.name = 'MANAGE_FRIEND_TEAMS'))
                    OR (s.effective_role = 'MEMBER'
                        AND NOT EXISTS (
                            SELECT 1 FROM user_permission_groups upg JOIN permission_groups pg ON pg.id = upg.group_id
                            WHERE upg.user_id = :userId AND pg.team_id = s.team_id
                                AND pg.deleted_at IS NULL AND pg.target_role = 'MEMBER')
                        AND EXISTS (
                            SELECT 1 FROM role_permissions rp JOIN roles r ON r.id = rp.role_id
                            JOIN permissions p ON p.id = rp.permission_id
                            LEFT JOIN team_role_permissions trp ON trp.role_id = rp.role_id
                                AND trp.permission_id = rp.permission_id AND trp.scope_type = 'TEAM' AND trp.scope_id = s.team_id
                            WHERE r.name = 'MEMBER' AND p.name = 'MANAGE_FRIEND_TEAMS'
                                AND COALESCE(trp.is_enabled, rp.is_default) = TRUE))
                )
            """;
    String ELIGIBLE_SOURCES = "FROM (" + SCOPED_TEAMS + ") s " + PERMISSION_FILTER;
    String LIST_SOURCES = "SELECT DISTINCT s.team_id, s.team_slug, s.team_name " + ELIGIBLE_SOURCES
            + " AND s.team_slug <> :targetSlug ORDER BY s.team_id ASC LIMIT :limit OFFSET :offset";
    String COUNT_SOURCES = "SELECT COUNT(DISTINCT s.team_id) " + ELIGIBLE_SOURCES
            + " AND s.team_slug <> :targetSlug";

    /** 全条件で絞り込んだ後に重複排除・SQLページングする。 */
    @Query(value = LIST_SOURCES, nativeQuery = true)
    List<Object[]> findEligibleSources(@Param("userId") Long userId, @Param("targetSlug") String targetSlug,
            @Param("limit") int limit, @Param("offset") long offset);

    /** 一覧と同じ述語の総数。 */
    @Query(value = COUNT_SOURCES, nativeQuery = true)
    long countEligibleSources(@Param("userId") Long userId, @Param("targetSlug") String targetSlug);

    /** target自己除外以外を候補と共有し、書込直前の元失効を判定する。 */
    @Query(value = "SELECT COUNT(DISTINCT s.team_id) " + ELIGIBLE_SOURCES
            + " AND s.team_id = :sourceTeamId", nativeQuery = true)
    long countEligibleSource(@Param("userId") Long userId, @Param("sourceTeamId") Long sourceTeamId);
}
