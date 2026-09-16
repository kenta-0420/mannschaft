package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.entity.RecruitmentListingAudienceScopeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 個人札ごとの固定公開先スナップショット。 */
public interface RecruitmentListingAudienceScopeRepository
        extends JpaRepository<RecruitmentListingAudienceScopeEntity, UUID> {

    List<RecruitmentListingAudienceScopeEntity> findByListingId(Long listingId);

    List<RecruitmentListingAudienceScopeEntity> findByListingIdInOrderByListingIdAscIdAsc(
            Collection<Long> listingIds);

    int countByListingId(Long listingId);

    /** owner と viewer が保存済みの同一 active scope に現在も在籍する札だけを返す。 */
    @Query(value = """
            SELECT DISTINCT a.listing_id
            FROM recruitment_listing_audience_scopes a
            JOIN recruitment_listings l ON l.id = a.listing_id
            JOIN users owner ON owner.id = l.scope_id
            WHERE l.scope_type = 'PERSONAL'
              AND l.visibility = 'SELECTED_SCOPES'
              AND owner.status = 'ACTIVE' AND owner.deleted_at IS NULL
              AND (
                (a.scope_type = 'TEAM'
                  AND (EXISTS (SELECT 1 FROM user_roles ur
                               WHERE ur.user_id = l.scope_id AND ur.team_id = a.scope_id)
                       OR EXISTS (SELECT 1 FROM memberships m
                                  WHERE m.user_id = l.scope_id AND m.scope_type = 'TEAM'
                                    AND m.scope_id = a.scope_id AND m.left_at IS NULL))
                  AND (EXISTS (SELECT 1 FROM user_roles ur
                               JOIN users vu ON vu.id = ur.user_id
                               WHERE ur.user_id = :viewerUserId AND ur.team_id = a.scope_id
                                 AND vu.status = 'ACTIVE' AND vu.deleted_at IS NULL)
                       OR EXISTS (SELECT 1 FROM memberships m
                                  JOIN users vu ON vu.id = m.user_id
                                  WHERE m.user_id = :viewerUserId AND m.scope_type = 'TEAM'
                                    AND m.scope_id = a.scope_id AND m.left_at IS NULL
                                    AND vu.status = 'ACTIVE' AND vu.deleted_at IS NULL)))
                OR
                (a.scope_type = 'ORGANIZATION'
                  AND (EXISTS (SELECT 1 FROM user_roles ur
                               WHERE ur.user_id = l.scope_id AND ur.organization_id = a.scope_id)
                       OR EXISTS (SELECT 1 FROM memberships m
                                  WHERE m.user_id = l.scope_id AND m.scope_type = 'ORGANIZATION'
                                    AND m.scope_id = a.scope_id AND m.left_at IS NULL))
                  AND (EXISTS (SELECT 1 FROM user_roles ur
                               JOIN users vu ON vu.id = ur.user_id
                               WHERE ur.user_id = :viewerUserId AND ur.organization_id = a.scope_id
                                 AND vu.status = 'ACTIVE' AND vu.deleted_at IS NULL)
                       OR EXISTS (SELECT 1 FROM memberships m
                                  JOIN users vu ON vu.id = m.user_id
                                  WHERE m.user_id = :viewerUserId AND m.scope_type = 'ORGANIZATION'
                                    AND m.scope_id = a.scope_id AND m.left_at IS NULL
                                    AND vu.status = 'ACTIVE' AND vu.deleted_at IS NULL)))
              )
            """, nativeQuery = true)
    List<Long> findAccessibleListingIds(@Param("viewerUserId") Long viewerUserId);

    @Modifying
    int deleteByListingId(Long listingId);
}
