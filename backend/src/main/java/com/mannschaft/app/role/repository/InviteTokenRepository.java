package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.InviteTokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 招待トークンリポジトリ。
 */
public interface InviteTokenRepository extends JpaRepository<InviteTokenEntity, Long>, InviteTokenRepositoryCustom {

    /** 平文ではなく {@code SecretTokenVault#hash} で得たハッシュで引く。 */
    Optional<InviteTokenEntity> findByTokenHash(String tokenHash);

    /**
     * 管理下に古い Entity が残っていると、それをそのまま返す（ロックは取れるが値は古い）。
     * 承諾・失効の経路では使わず {@link InviteTokenRepositoryCustom#lockFreshByTokenHash(String)} を使うこと
     * （CMP-260826-1457 §8.2）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM InviteTokenEntity t WHERE t.tokenHash = :tokenHash")
    Optional<InviteTokenEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * 管理下に古い Entity が残っていると、それをそのまま返す。
     * 失効経路では使わず {@link InviteTokenRepositoryCustom#lockFreshById(Long)} を使うこと。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM InviteTokenEntity t WHERE t.id = :id")
    Optional<InviteTokenEntity> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Query(value = "UPDATE invite_tokens SET revoked_at = UTC_TIMESTAMP() "
            + "WHERE team_id = :scopeId AND scope_closure_generation < :generation AND revoked_at IS NULL", nativeQuery = true)
    int revokeTeamBeforeGeneration(@Param("scopeId") Long scopeId, @Param("generation") long generation);

    @Modifying
    @Query(value = "UPDATE invite_tokens SET revoked_at = UTC_TIMESTAMP() "
            + "WHERE organization_id = :scopeId AND scope_closure_generation < :generation AND revoked_at IS NULL", nativeQuery = true)
    int revokeOrganizationBeforeGeneration(@Param("scopeId") Long scopeId, @Param("generation") long generation);

    /** 回復batchのprimary keyset読取。期限/使用上限/宛先で旧行を取りこぼさない。 */
    @org.springframework.transaction.annotation.Transactional
    List<InviteTokenEntity> findByRevokedAtIsNullAndIdGreaterThanAndIdLessThanEqualOrderByIdAsc(
            Long afterId, Long upperId, Pageable pageable);

    @org.springframework.transaction.annotation.Transactional
    Optional<InviteTokenEntity> findTopByOrderByIdDesc();

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
