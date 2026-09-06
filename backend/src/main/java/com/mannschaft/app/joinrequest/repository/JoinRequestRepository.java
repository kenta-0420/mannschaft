package com.mannschaft.app.joinrequest.repository;

import com.mannschaft.app.joinrequest.entity.JoinRequestEntity;
import com.mannschaft.app.joinrequest.entity.JoinRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 参加申請リポジトリ（柱③-A・CMP-260901-1538）。
 */
public interface JoinRequestRepository extends JpaRepository<JoinRequestEntity, UUID> {

    /**
     * approve/reject の直列化用。通常の {@link JpaRepository#findById} は行ロックを取らないため、
     * 同時 approve/reject が両方とも PENDING を確認できてしまう（レビューP1-2）。悲観ロック取得後に
     * 呼び出し側で改めて PENDING 状態を確認すること（金型: {@code MembershipRepository#findByIdForUpdate}）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM JoinRequestEntity r WHERE r.id = :id")
    Optional<JoinRequestEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<JoinRequestEntity> findByTeamIdAndRequesterUserIdAndStatus(
            Long teamId, Long requesterUserId, JoinRequestStatus status);

    Optional<JoinRequestEntity> findByOrganizationIdAndRequesterUserIdAndStatus(
            Long organizationId, Long requesterUserId, JoinRequestStatus status);

    Page<JoinRequestEntity> findByTeamIdAndStatus(Long teamId, JoinRequestStatus status, Pageable pageable);

    Page<JoinRequestEntity> findByOrganizationIdAndStatus(
            Long organizationId, JoinRequestStatus status, Pageable pageable);

    List<JoinRequestEntity> findByTeamIdAndRequesterUserIdOrderByCreatedAtDesc(Long teamId, Long requesterUserId);

    List<JoinRequestEntity> findByOrganizationIdAndRequesterUserIdOrderByCreatedAtDesc(
            Long organizationId, Long requesterUserId);
}
