package com.mannschaft.app.returnstayplan.repository;

import com.mannschaft.app.returnstayplan.entity.ReturnStayPlanTeamVisibilityEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** F02.11 TEAM 公開先 Repository の骨格。 */
public interface ReturnStayPlanTeamVisibilityRepository
        extends JpaRepository<ReturnStayPlanTeamVisibilityEntity, UUID> {

    List<ReturnStayPlanTeamVisibilityEntity> findByPlanId(UUID planId);

    List<ReturnStayPlanTeamVisibilityEntity> findByTeamId(Long teamId);
    void deleteByPlanId(UUID planId);

    @Query(value = """
            SELECT COUNT(DISTINCT t.id)
              FROM teams t
              JOIN memberships m
                ON m.scope_type = 'TEAM'
               AND m.scope_id = t.id
               AND m.user_id = :ownerUserId
               AND m.role_kind = 'MEMBER'
               AND m.left_at IS NULL
              JOIN users u
                ON u.id = :ownerUserId
               AND u.status = 'ACTIVE'
               AND u.deleted_at IS NULL
             WHERE t.id IN (:teamIds)
               AND t.archived_at IS NULL
               AND t.deleted_at IS NULL
            """, nativeQuery = true)
    long countSaveableTeams(
            @Param("ownerUserId") Long ownerUserId,
            @Param("teamIds") List<Long> teamIds);

    @Query(value = """
            SELECT t.id
              FROM teams t
              JOIN memberships vm
                ON vm.scope_type = 'TEAM'
               AND vm.scope_id = t.id
               AND vm.user_id = :viewerUserId
               AND vm.role_kind = 'MEMBER'
               AND vm.left_at IS NULL
              JOIN users vu
                ON vu.id = :viewerUserId
               AND vu.status = 'ACTIVE'
               AND vu.deleted_at IS NULL
             WHERE t.slug = :slug
               AND t.archived_at IS NULL
               AND t.deleted_at IS NULL
            """, nativeQuery = true)
    java.util.Optional<Long> findAuthorizedTeamId(
            @Param("slug") String slug,
            @Param("viewerUserId") Long viewerUserId);

    @Query(value = """
            SELECT HEX(p.id) AS planIdHex,
                   p.owner_user_id AS ownerUserId,
                   ou.display_name AS ownerDisplayName,
                   ou.avatar_url AS ownerAvatarUrl,
                   p.plan_type AS planType,
                   p.country_code AS countryCode,
                   p.prefecture_code AS prefectureCode,
                   p.region_name AS regionName,
                   p.timezone AS timezone,
                   p.start_date AS startDate,
                   p.end_date AS endDate
              FROM return_stay_plans p
              JOIN return_stay_plan_team_visibilities pv
                ON pv.plan_id = p.id
              JOIN teams t
                ON t.id = pv.team_id
               AND t.id = :teamId
               AND t.archived_at IS NULL
               AND t.deleted_at IS NULL
              JOIN memberships vm
                ON vm.scope_type = 'TEAM'
               AND vm.scope_id = t.id
               AND vm.user_id = :viewerUserId
               AND vm.role_kind = 'MEMBER'
               AND vm.left_at IS NULL
              JOIN users vu
                ON vu.id = :viewerUserId
               AND vu.status = 'ACTIVE'
               AND vu.deleted_at IS NULL
              JOIN memberships om
                ON om.scope_type = 'TEAM'
               AND om.scope_id = t.id
               AND om.user_id = p.owner_user_id
               AND om.role_kind = 'MEMBER'
               AND om.left_at IS NULL
              JOIN users ou
                ON ou.id = p.owner_user_id
               AND ou.status = 'ACTIVE'
               AND ou.deleted_at IS NULL
             WHERE p.owner_user_id IN (:memberIds)
               AND p.is_published = TRUE
               AND p.end_date >= :today
             ORDER BY p.owner_user_id, p.end_date, p.start_date, p.id
            """, nativeQuery = true)
    List<VisiblePlanProjection> findVisiblePlans(
            @Param("viewerUserId") Long viewerUserId,
            @Param("teamId") Long teamId,
            @Param("memberIds") List<Long> memberIds,
            @Param("today") java.time.LocalDate today);

    @Query(value = """
            SELECT COUNT(*) > 0
              FROM return_stay_plans p
              JOIN return_stay_plan_team_visibilities pv
                ON pv.plan_id = p.id AND pv.team_id = :teamId
              JOIN teams t
                ON t.id = :teamId AND t.archived_at IS NULL AND t.deleted_at IS NULL
              JOIN memberships vm
                ON vm.scope_type = 'TEAM' AND vm.scope_id = t.id
               AND vm.user_id = :viewerUserId AND vm.role_kind = 'MEMBER' AND vm.left_at IS NULL
              JOIN users vu
                ON vu.id = :viewerUserId AND vu.status = 'ACTIVE' AND vu.deleted_at IS NULL
              JOIN memberships om
                ON om.scope_type = 'TEAM' AND om.scope_id = t.id
               AND om.user_id = p.owner_user_id AND om.role_kind = 'MEMBER' AND om.left_at IS NULL
              JOIN users ou
                ON ou.id = p.owner_user_id AND ou.status = 'ACTIVE' AND ou.deleted_at IS NULL
             WHERE p.id = :planId AND p.owner_user_id = :ownerUserId AND p.is_published = TRUE
            """, nativeQuery = true)
    boolean existsVisiblePlan(
            @Param("viewerUserId") Long viewerUserId,
            @Param("ownerUserId") Long ownerUserId,
            @Param("teamId") Long teamId,
            @Param("planId") UUID planId);

    interface VisiblePlanProjection {
        String getPlanIdHex();
        Long getOwnerUserId();
        String getOwnerDisplayName();
        String getOwnerAvatarUrl();
        String getPlanType();
        String getCountryCode();
        String getPrefectureCode();
        String getRegionName();
        String getTimezone();
        java.time.LocalDate getStartDate();
        java.time.LocalDate getEndDate();
    }
}
