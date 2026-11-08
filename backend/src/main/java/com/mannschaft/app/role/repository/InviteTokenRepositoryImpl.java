package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.dto.InviteClaimAuditContext;
import com.mannschaft.app.role.dto.InviteScopeRef;
import com.mannschaft.app.role.dto.InviteTokenSnapshot;
import com.mannschaft.app.role.entity.InviteTokenClaimEntity;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link InviteTokenRepositoryCustom} の実装（Spring Data フラグメント規約: {@code <RepositoryName>Impl}）。
 *
 * <p>native 文の時刻は DB の {@code UTC_TIMESTAMP} を基準にし、期限判定に使う現在時刻も
 * 同じ文の中で {@code UTC_TIMESTAMP(6)} として読む（期限列と同じ変換経路を通るので互いに比較できる）。</p>
 */
@Repository
class InviteTokenRepositoryImpl implements InviteTokenRepositoryCustom {

    private static final String CLAIMS = "invite_token_claims";

    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<InviteScopeRef> findScopeByTokenHash(String tokenHash) {
        return findScope("token_hash = :key", tokenHash);
    }

    @Override
    public Optional<InviteScopeRef> findScopeById(Long id) {
        return findScope("id = :key", id);
    }

    private Optional<InviteScopeRef> findScope(String predicate, Object key) {
        List<?> rows = em.createNativeQuery(
                        "SELECT team_id, organization_id FROM invite_tokens WHERE " + predicate)
                .setParameter("key", key)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.get(0);
        return Optional.of(row[0] != null
                ? new InviteScopeRef("TEAM", ((Number) row[0]).longValue())
                : new InviteScopeRef("ORGANIZATION", ((Number) row[1]).longValue()));
    }

    @Override
    public Optional<InviteTokenSnapshot> lockSnapshotByTokenHash(String tokenHash) {
        return lockSnapshot("token_hash = :key", tokenHash);
    }

    @Override
    public Optional<InviteTokenSnapshot> lockSnapshotById(Long id) {
        return lockSnapshot("id = :key", id);
    }

    private Optional<InviteTokenSnapshot> lockSnapshot(String predicate, Object key) {
        List<?> rows = em.createNativeQuery(
                        "SELECT id, team_id, organization_id, role_id, expires_at, max_uses, used_count, revoked_at, "
                                + "scope_closure_generation, created_by, target_user_id, UTC_TIMESTAMP(6) "
                                + "FROM invite_tokens WHERE " + predicate + " FOR UPDATE")
                .setParameter("key", key)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = (Object[]) rows.get(0);
        return Optional.of(new InviteTokenSnapshot(
                longOrNull(r[0]), longOrNull(r[1]), longOrNull(r[2]), longOrNull(r[3]),
                time(r[4]), r[5] == null ? null : ((Number) r[5]).intValue(), ((Number) r[6]).intValue(),
                time(r[7]), ((Number) r[8]).longValue(), longOrNull(r[9]), longOrNull(r[10]), time(r[11])));
    }

    @Override
    public int incrementUsedCountIfAvailable(Long tokenId) {
        em.flush();
        return em.createNativeQuery("UPDATE invite_tokens SET used_count = used_count + 1 "
                        + "WHERE id = :id AND revoked_at IS NULL AND (max_uses IS NULL OR used_count < max_uses)")
                .setParameter("id", tokenId)
                .executeUpdate();
    }

    @Override
    public int decrementUsedCount(Long tokenId) {
        em.flush();
        return em.createNativeQuery("UPDATE invite_tokens SET used_count = used_count - 1 "
                        + "WHERE id = :id AND used_count > 0")
                .setParameter("id", tokenId)
                .executeUpdate();
    }

    @Override
    public void refreshIfManaged(Long tokenId) {
        InviteTokenEntity managed = em.find(InviteTokenEntity.class, tokenId);
        if (managed != null) {
            em.refresh(managed);
        }
    }

    @Override
    public Optional<InviteTokenEntity> lockFreshById(Long id) {
        return lockFresh("t.id = :key", id);
    }

    @Override
    public Optional<InviteTokenEntity> lockFreshByTokenHash(String tokenHash) {
        return lockFresh("t.tokenHash = :key", tokenHash);
    }

    /**
     * ロック付きの問い合わせ（現在読み）で行の存在を確かめ、管理下に古い Entity が残っていても
     * ロック付き refresh で最新値へ置き換える。保留中の変更は問い合わせ前の自動 flush で書き出される。
     */
    private Optional<InviteTokenEntity> lockFresh(String predicate, Object key) {
        List<InviteTokenEntity> found = em.createQuery(
                        "SELECT t FROM InviteTokenEntity t WHERE " + predicate, InviteTokenEntity.class)
                .setParameter("key", key)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        InviteTokenEntity entity = found.get(0);
        em.flush();
        em.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(entity);
    }

    @Override
    public UUID insertClaim(Long tokenId, Long userId) {
        InviteTokenClaimEntity claim = InviteTokenClaimEntity.builder()
                .tokenId(tokenId)
                .userId(userId)
                .status(InviteTokenClaimEntity.CLAIMED)
                .build();
        em.persist(claim);
        em.flush();
        return claim.getId();
    }

    @Override
    public int markClaimGranted(UUID claimId) {
        return transition(claimId, InviteTokenClaimEntity.GRANTED);
    }

    @Override
    public int markClaimReleased(UUID claimId) {
        return transition(claimId, InviteTokenClaimEntity.RELEASED);
    }

    private int transition(UUID claimId, String to) {
        return em.createNativeQuery("UPDATE " + CLAIMS + " SET status = :to, updated_at = UTC_TIMESTAMP(6) "
                        + "WHERE id = UNHEX(:id) AND status = 'CLAIMED'")
                .setParameter("to", to)
                .setParameter("id", hex(claimId))
                .executeUpdate();
    }

    @Override
    public Optional<Long> findClaimTokenId(UUID claimId) {
        List<?> rows = em.createNativeQuery("SELECT token_id FROM " + CLAIMS + " WHERE id = UNHEX(:id)")
                .setParameter("id", hex(claimId))
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(((Number) rows.get(0)).longValue());
    }

    @Override
    public Optional<String> findClaimStatusLocked(UUID claimId) {
        List<?> rows = em.createNativeQuery("SELECT status FROM " + CLAIMS + " WHERE id = UNHEX(:id) FOR UPDATE")
                .setParameter("id", hex(claimId))
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of((String) rows.get(0));
    }

    @Override
    public Optional<InviteClaimAuditContext> findClaimAuditContext(UUID claimId) {
        List<?> rows = em.createNativeQuery("SELECT c.user_id, t.team_id, t.organization_id, t.created_by FROM " + CLAIMS
                        + " c JOIN invite_tokens t ON t.id = c.token_id WHERE c.id = UNHEX(:id)")
                .setParameter("id", hex(claimId))
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = (Object[]) rows.get(0);
        return Optional.of(new InviteClaimAuditContext(longOrNull(r[0]), longOrNull(r[1]), longOrNull(r[2]),
                longOrNull(r[3])));
    }

    @Override
    public long countStaleClaimed(long thresholdSeconds) {
        Object count = em.createNativeQuery("SELECT COUNT(*) FROM " + CLAIMS + " WHERE status = 'CLAIMED' "
                        + "AND created_at < DATE_SUB(UTC_TIMESTAMP(6), INTERVAL :seconds SECOND)")
                .setParameter("seconds", thresholdSeconds)
                .getSingleResult();
        return ((Number) count).longValue();
    }

    @Override
    public long oldestClaimedAgeSeconds() {
        Object age = em.createNativeQuery("SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND, created_at, UTC_TIMESTAMP(6))), 0) "
                        + "FROM " + CLAIMS + " WHERE status = 'CLAIMED'")
                .getSingleResult();
        return ((Number) age).longValue();
    }

    private static String hex(UUID id) {
        return id.toString().replace("-", "");
    }

    private static Long longOrNull(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Instant time(Object value) {
        if (value == null) {
            return null;
        }
        // 全列が同じ変換経路を通るので、壁時計を UTC とみなして瞬間に写しても互いの前後関係は保たれる
        if (value instanceof LocalDateTime local) {
            return local.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
        if (value instanceof java.util.Date date) {
            return new Timestamp(date.getTime()).toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
        throw new IllegalStateException("招待行の時刻列を解釈できない型: " + value.getClass().getName());
    }
}
