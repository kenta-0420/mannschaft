package com.mannschaft.app.committee.repository;

import com.mannschaft.app.committee.entity.CommitteeInvitationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
