package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.InviteTokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 招待トークンリポジトリ。
 */
public interface InviteTokenRepository extends JpaRepository<InviteTokenEntity, Long> {

    Optional<InviteTokenEntity> findByToken(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM InviteTokenEntity t WHERE t.token = :token")
    Optional<InviteTokenEntity> findByTokenForUpdate(@Param("token") String token);

    List<InviteTokenEntity> findByTeamIdAndRevokedAtIsNull(Long teamId);

    List<InviteTokenEntity> findByOrganizationIdAndRevokedAtIsNull(Long organizationId);
}
