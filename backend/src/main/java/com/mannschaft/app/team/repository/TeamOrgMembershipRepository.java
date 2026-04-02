package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;


/**
 * チーム−組織所属リポジトリ。
 */
public interface TeamOrgMembershipRepository extends JpaRepository<TeamOrgMembershipEntity, Long> {

    Optional<TeamOrgMembershipEntity> findByTeamIdAndOrganizationId(Long teamId, Long organizationId);

    List<TeamOrgMembershipEntity> findByOrganizationIdAndStatus(Long organizationId, TeamOrgMembershipEntity.Status status);

    /**
     * チームが所属するACTIVE状態の組織を取得する（通常1件）。
     */
    Optional<TeamOrgMembershipEntity> findFirstByTeamIdAndStatus(Long teamId, TeamOrgMembershipEntity.Status status);

    /**
     * チームが所属する全組織を取得する。
     */
    List<TeamOrgMembershipEntity> findByTeamIdAndStatus(Long teamId, TeamOrgMembershipEntity.Status status);

    /**
     * 物理削除バッチ用: 指定ユーザーが招待者のレコードのinvitedByをNULL化する。
     */
    @Modifying
    @Query("UPDATE TeamOrgMembershipEntity m SET m.invitedBy = NULL WHERE m.invitedBy = :userId")
    int nullifyInvitedBy(@Param("userId") Long userId);

    /**
     * 物理削除バッチ用: 指定ユーザーが承認者のレコードのrespondedByをNULL化する。
     */
    @Modifying
    @Query("UPDATE TeamOrgMembershipEntity m SET m.respondedBy = NULL WHERE m.respondedBy = :userId")
    int nullifyRespondedBy(@Param("userId") Long userId);
}
