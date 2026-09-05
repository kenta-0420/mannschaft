package com.mannschaft.app.joinrequest.repository;

import com.mannschaft.app.joinrequest.entity.JoinRequestEntity;
import com.mannschaft.app.joinrequest.entity.JoinRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 参加申請リポジトリ（柱③-A・CMP-260901-1538）。
 */
public interface JoinRequestRepository extends JpaRepository<JoinRequestEntity, UUID> {

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
