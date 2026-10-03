package com.mannschaft.app.committee.repository;

import com.mannschaft.app.committee.entity.CommitteeInvitationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 委員会招集状リポジトリ。
 */
public interface CommitteeInvitationRepository extends JpaRepository<CommitteeInvitationEntity, Long> {

    /**
     * トークンで招集状を検索する。
     */
    Optional<CommitteeInvitationEntity> findByInviteToken(String inviteToken);

    /** 親委員会・現役メンバーをロックした後に招集状の最新状態を取得する。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM CommitteeInvitationEntity i WHERE i.id = :invitationId")
    Optional<CommitteeInvitationEntity> findByIdForUpdate(@Param("invitationId") Long invitationId);

    /**
     * 委員会の未解決招集状一覧を取得する。
     */
    List<CommitteeInvitationEntity> findByCommitteeIdAndResolvedAtIsNull(Long committeeId);

    /**
     * 被招集者の未解決招集状一覧を取得する。
     */
    List<CommitteeInvitationEntity> findByInviteeUserIdAndResolvedAtIsNull(Long inviteeUserId);

    /**
     * 被招集者への委員会の未解決招集状が存在するか確認する。
     */
    boolean existsByCommitteeIdAndInviteeUserIdAndResolvedAtIsNull(Long committeeId, Long inviteeUserId);

    /**
     * 期限切れの未解決招集状を取得する（バッチ用）。
     */
    @Query("SELECT i FROM CommitteeInvitationEntity i WHERE i.resolvedAt IS NULL AND i.expiresAt < :now")
    List<CommitteeInvitationEntity> findExpiredPendingInvitations(@Param("now") LocalDateTime now);

    /**
     * 組織脱退者宛ての未解決招集状を、論理削除済み委員会も含めてロック取得する。
     */
    @Query(value = """
            SELECT i.*
              FROM committee_invitations i
              JOIN committees c ON c.id = i.committee_id
             WHERE c.organization_id = :organizationId
               AND i.invitee_user_id = :inviteeUserId
               AND i.resolved_at IS NULL
             ORDER BY i.committee_id ASC, i.id ASC
             FOR UPDATE
            """, nativeQuery = true)
    List<CommitteeInvitationEntity> findPendingByOrganizationAndInviteeForUpdate(
            @Param("organizationId") Long organizationId,
            @Param("inviteeUserId") Long inviteeUserId);
}
