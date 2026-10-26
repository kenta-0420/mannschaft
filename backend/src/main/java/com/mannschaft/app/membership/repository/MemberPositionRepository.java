package com.mannschaft.app.membership.repository;

import com.mannschaft.app.membership.entity.MemberPositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 役職割当 Repository。
 *
 * <p>F00.5 メンバーシップ基盤再設計で導入。</p>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §5.2 / §7.4</p>
 */
public interface MemberPositionRepository extends JpaRepository<MemberPositionEntity, Long> {

    /**
     * 指定 membership に紐付く現役（ended_at IS NULL）の役職割当を取得する。
     */
    @Query("SELECT mp FROM MemberPositionEntity mp " +
            "WHERE mp.membershipId = :membershipId AND mp.endedAt IS NULL " +
            "ORDER BY mp.startedAt DESC")
    List<MemberPositionEntity> findCurrentByMembership(@Param("membershipId") Long membershipId);

    /**
     * 指定 position に紐付く現役（ended_at IS NULL）の役職割当を取得する。
     */
    @Query("SELECT mp FROM MemberPositionEntity mp " +
            "WHERE mp.positionId = :positionId AND mp.endedAt IS NULL " +
            "ORDER BY mp.startedAt DESC")
    List<MemberPositionEntity> findCurrentByPosition(@Param("positionId") Long positionId);
}
