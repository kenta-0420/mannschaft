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

    Optional<InviteTokenEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * 宛先付きトークンの重複 PENDING 検出（F04.12・③）。
     * 同一宛先 × 同一チームで未失効のトークンを列挙し、呼出側で {@code isValid()} により有効判定する。
     */
    List<InviteTokenEntity> findByTargetUserIdAndTeamIdAndRevokedAtIsNull(Long targetUserId, Long teamId);

    /**
     * 宛先付きトークンの重複 PENDING 検出（F04.12・③）。組織スコープ版。
     */
    List<InviteTokenEntity> findByTargetUserIdAndOrganizationIdAndRevokedAtIsNull(
            Long targetUserId, Long organizationId);
}
