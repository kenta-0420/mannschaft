package com.mannschaft.app.family.repository;

import com.mannschaft.app.family.CareLinkStatus;
import com.mannschaft.app.family.entity.UserCareLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * 複数のケア対象者ユーザーIDを IN 句で一括取得する（N+1 防止）。
     *
     * <p>F03.12 §14 主催者点呼機能。候補者一覧取得時にケアリンク情報をまとめてロードする。</p>
     *
     * @param careRecipientUserIds ケア対象者ユーザーIDのコレクション
     * @param status               取得対象ステータス（通常 ACTIVE）
     * @return 該当するケアリンク一覧
     */
    @Query("SELECT u FROM UserCareLinkEntity u WHERE u.careRecipientUserId IN :userIds AND u.status = :status")
    List<UserCareLinkEntity> findByCareRecipientUserIdInAndStatus(
            @Param("userIds") Collection<Long> careRecipientUserIds,
            @Param("status") CareLinkStatus status);
}
