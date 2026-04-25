package com.mannschaft.app.family.repository;

import com.mannschaft.app.family.CareLinkStatus;
import com.mannschaft.app.family.entity.UserCareLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * ユーザーケアリンクリポジトリ。F03.12。
 */
public interface UserCareLinkRepository extends JpaRepository<UserCareLinkEntity, Long> {

    List<UserCareLinkEntity> findByCareRecipientUserIdAndStatus(Long careRecipientUserId, CareLinkStatus status);

    List<UserCareLinkEntity> findByWatcherUserIdAndStatus(Long watcherUserId, CareLinkStatus status);

    @Query("SELECT u FROM UserCareLinkEntity u WHERE (u.careRecipientUserId = :userId OR u.watcherUserId = :userId) AND u.status = 'PENDING'")
    List<UserCareLinkEntity> findPendingInvitationsForUser(@Param("userId") Long userId);

    Optional<UserCareLinkEntity> findByInvitationToken(String token);

    boolean existsByCareRecipientUserIdAndWatcherUserId(Long careRecipientUserId, Long watcherUserId);

    long countByCareRecipientUserIdAndStatusIn(Long careRecipientUserId, List<CareLinkStatus> statuses);

    boolean existsByCareRecipientUserIdAndStatus(Long careRecipientUserId, CareLinkStatus status);
}
