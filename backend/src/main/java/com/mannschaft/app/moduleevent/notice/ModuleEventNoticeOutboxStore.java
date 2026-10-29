package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * M 内部の通知予約 DB 操作。すべて呼出し元の M TX に参加し、業務 SPI を呼ばない。
 * reserve は成功削除と同じ TX、relay/evaluation は接続 facade の独立 TX から呼ぶ。
 * Entity は notification へ公開しない。全域 clear/detach で caller の dirty state を消さない。
 */
public final class ModuleEventNoticeOutboxStore {
    public static final int MAX_CLAIMS = 100;
    private static final int DESCRIPTOR_VERSION = 1;
    private final EntityManager entityManager;

    public ModuleEventNoticeOutboxStore(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    /** unique 競合を例外で握りつぶさず、同じ noticeKey の DB 行へ収束する。 */
    public Optional<ModuleEventNoticeOutboxEntity> reserve(ModuleEventNoticeOutboxEntity reservation) {
        Objects.requireNonNull(reservation, "reservation");
        requireTransaction();
        if (reservation.getId() == null) {
            reservation.setId(UuidV7.generate());
        }
        entityManager.flush();
        entityManager.createNativeQuery("""
                INSERT INTO module_event_notice_outbox
                    (id, notice_key, resolver_key, operation_id, episode_id, scope_type, scope_id,
                     organization_id, phase, audience, descriptor_version, status, attempt_count,
                     next_attempt_at, claim_token, claimed_at, relayed_at, dead_at, last_error_code,
                     created_at, updated_at)
                VALUES (:id, :noticeKey, :resolverKey, :operationId, :episodeId, :scopeType, :scopeId,
                        :organizationId, :phase, :audience, :version, :status, :attemptCount,
                        :next, :token, :claimed, :relayed, :dead, NULL, :created, :updated)
                ON DUPLICATE KEY UPDATE id = id
                """)
                .setParameter("id", bytes(reservation.getId()))
                .setParameter("noticeKey", bytes(reservation.getNoticeKey()))
                .setParameter("resolverKey", reservation.getResolverKey())
                .setParameter("operationId", bytes(reservation.getOperationId()))
                .setParameter("episodeId", bytes(reservation.getEpisodeId()))
                .setParameter("scopeType", reservation.getScopeType())
                .setParameter("scopeId", reservation.getScopeId())
                .setParameter("organizationId", reservation.getOrganizationId())
                .setParameter("phase", reservation.getPhase())
                .setParameter("audience", reservation.getAudience())
                .setParameter("version", reservation.getDescriptorVersion())
                .setParameter("status", reservation.getStatus())
                .setParameter("attemptCount", reservation.getAttemptCount())
                .setParameter("next", timestamp(reservation.getNextAttemptAt()))
                .setParameter("token", bytes(reservation.getClaimToken()))
                .setParameter("claimed", timestamp(reservation.getClaimedAt()))
                .setParameter("relayed", timestamp(reservation.getRelayedAt()))
                .setParameter("dead", timestamp(reservation.getDeadAt()))
                .setParameter("created", timestamp(reservation.getCreatedAt()))
                .setParameter("updated", timestamp(reservation.getUpdatedAt()))
                .executeUpdate();
        List<ModuleEventNoticeOutboxEntity> rows = rows(entityManager.createNativeQuery("""
                SELECT * FROM module_event_notice_outbox WHERE notice_key = :key FOR UPDATE
                """, ModuleEventNoticeOutboxEntity.class).setParameter("key", bytes(reservation.getNoticeKey())));
        if (rows.size() != 1 || !sameDescriptor(rows.getFirst(), reservation)) {
            throw new IllegalArgumentException("noticeKey と immutable descriptor が一致しない");
        }
        return Optional.of(rows.getFirst());
    }

    /** 影響通知のある同 binding/audience だけ、成功削除と同じ TX で WAITING を予約する。 */
    public Optional<ModuleEventNoticeOutboxEntity> reserveRecoveryIntent(
            ModuleEventNoticeOutboxEntity recoveryReservation) {
        Objects.requireNonNull(recoveryReservation, "recoveryReservation");
        requireTransaction();
        if (recoveryReservation.getDescriptorVersion() != DESCRIPTOR_VERSION
                || !"RECOVERED".equals(recoveryReservation.getPhase())
                || !"WAITING_RECOVERY".equals(recoveryReservation.getStatus())) {
            throw new IllegalArgumentException("復旧 intent は既知版の RECOVERED / WAITING_RECOVERY が必要");
        }
        entityManager.flush();
        Query query = binding(entityManager.createNativeQuery("""
                SELECT id FROM module_event_notice_outbox
                WHERE resolver_key = :resolver AND operation_id = :operation AND episode_id = :episode
                  AND scope_type = :scopeType AND scope_id = :scopeId AND organization_id <=> :organization
                  AND audience = :audience AND descriptor_version = :version
                  AND phase IN ('DELAYED', 'QUARANTINED')
                LIMIT 1 FOR UPDATE
                """), recoveryReservation)
                .setParameter("audience", recoveryReservation.getAudience())
                .setParameter("version", DESCRIPTOR_VERSION);
        return query.getResultList().isEmpty() ? Optional.empty() : reserve(recoveryReservation);
    }

    /** 未知版を閉じた enum へ変換せず claim し、adapter の defer で後続へ進める。 */
    public List<ModuleEventNoticeOutboxEntity> claim(int limit, Instant now) {
        return claimRows(limit, now, false);
    }

    public List<ModuleEventNoticeOutboxEntity> claimRecovery(int limit, Instant now) {
        return claimRows(limit, now, true);
    }

    private List<ModuleEventNoticeOutboxEntity> claimRows(int limit, Instant now, boolean recovery) {
        checkLimit(limit);
        Objects.requireNonNull(now, "now");
        requireTransaction();
        entityManager.flush();
        List<ModuleEventNoticeOutboxEntity> claimed = rows(entityManager.createNativeQuery("""
                SELECT * FROM module_event_notice_outbox
                WHERE status = :status AND next_attempt_at <= :now AND claim_token IS NULL
                ORDER BY next_attempt_at, id LIMIT :limit FOR UPDATE SKIP LOCKED
                """, ModuleEventNoticeOutboxEntity.class)
                .setParameter("status", recovery ? "WAITING_RECOVERY" : "PENDING")
                .setParameter("now", timestamp(now)).setParameter("limit", limit));
        claimed.forEach(row -> row.claim(recovery, now));
        return claimed;
    }

    /** TX 外で得た業務評価を現在世代へ適用する。業務 SPI 自体は呼ばない。 */
    public boolean applyRecoveryEvaluation(UUID id, UUID claimToken, ModuleEventRecoveryState recovery,
            Instant nextAttemptAt, Instant now) {
        Objects.requireNonNull(recovery, "recovery");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(now, "now");
        ModuleEventNoticeOutboxEntity row = current(id);
        if (!owned(row, claimToken, "WAITING_RECOVERY")
                || row.getDescriptorVersion() != DESCRIPTOR_VERSION || !"RECOVERED".equals(row.getPhase())) {
            return false;
        }
        if (recovery != ModuleEventRecoveryState.CLOSED_COMPLETE) {
            row.deferRecovery(nextAttemptAt, now);
            return true;
        }
        if (hasPendingPublication(row)) {
            row.deferRecovery(nextAttemptAt, now);
            return false;
        }
        row.readyAfterRecovery(now);
        return true;
    }

    /** RELAYED は enqueue 受付。配送済みとせず token を retention CAS のため保持する。 */
    public boolean markRelayed(UUID id, UUID claimToken, Instant now) {
        Objects.requireNonNull(now, "now");
        ModuleEventNoticeOutboxEntity row = current(id);
        if (!owned(row, claimToken, "RELAYING") || row.getDescriptorVersion() != DESCRIPTOR_VERSION) {
            return false;
        }
        row.relayed(now);
        return true;
    }

    public boolean deferUnsupportedVersion(UUID id, UUID claimToken, Instant nextAttemptAt, Instant now) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(now, "now");
        ModuleEventNoticeOutboxEntity row = current(id);
        if (row == null || row.getDescriptorVersion() == DESCRIPTOR_VERSION
                || !(owned(row, claimToken, "RELAYING") || owned(row, claimToken, "WAITING_RECOVERY"))) {
            return false;
        }
        if ("RELAYING".equals(row.getStatus())) {
            row.deferRelay(nextAttemptAt, now);
        } else {
            row.deferRecovery(nextAttemptAt, now);
        }
        return true;
    }

    /** relay adapter は自由文を渡さず RELAY_FAILED に変換する。
     * 現 RELAYING/token のみ attempt+1、PENDING 又は DEAD、lease 解除を同じ TX で行う。
     * 許可された固定 code だけを保存し、未知/null/空/長大コードは DB 操作前に拒否する。 */
    public boolean markFailed(UUID id, UUID claimToken, String fixedErrorCode, Instant nextAttemptAt,
            boolean dead, Instant now) {
        if (!"RELAY_FAILED".equals(fixedErrorCode)) {
            throw new IllegalArgumentException("relay 失敗 code は RELAY_FAILED のみ使用できる");
        }
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(now, "now");
        ModuleEventNoticeOutboxEntity row = current(id);
        if (!owned(row, claimToken, "RELAYING") || row.getDescriptorVersion() != DESCRIPTOR_VERSION) {
            return false;
        }
        row.relayFailed(dead, nextAttemptAt, now);
        return true;
    }

    /** PENDING 行だけの SQL MIN(created_at)。Entity を全件ロードせず、空は Optional.empty。 */
    public Optional<Instant> oldestPendingCreatedAt() {
        requireTransaction();
        entityManager.flush();
        return Optional.ofNullable(entityManager.createQuery(
                "select min(n.createdAt) from ModuleEventNoticeOutboxEntity n where n.status = :status",
                Instant.class).setParameter("status", "PENDING").getSingleResult());
    }

    public int recoverStuck(Instant claimedBefore, Instant now) {
        Objects.requireNonNull(claimedBefore, "claimedBefore");
        Objects.requireNonNull(now, "now");
        requireTransaction();
        entityManager.flush();
        List<ModuleEventNoticeOutboxEntity> expired = rows(entityManager.createNativeQuery("""
                SELECT * FROM module_event_notice_outbox
                WHERE status IN ('RELAYING', 'WAITING_RECOVERY')
                  AND claim_token IS NOT NULL AND claimed_at < :before
                ORDER BY claimed_at, id LIMIT 100 FOR UPDATE SKIP LOCKED
                """, ModuleEventNoticeOutboxEntity.class).setParameter("before", timestamp(claimedBefore)));
        expired.forEach(row -> row.recoverLease(now));
        return expired.size();
    }

    private ModuleEventNoticeOutboxEntity current(UUID id) {
        Objects.requireNonNull(id, "id");
        requireTransaction();
        entityManager.flush();
        ModuleEventNoticeOutboxEntity row = entityManager.find(ModuleEventNoticeOutboxEntity.class,
                id, LockModeType.PESSIMISTIC_WRITE);
        if (row != null) {
            entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE);
        }
        return row;
    }

    private List<ModuleEventNoticeOutboxEntity> rows(Query query) {
        List<ModuleEventNoticeOutboxEntity> result = new ArrayList<>();
        for (Object value : query.getResultList()) {
            ModuleEventNoticeOutboxEntity row = ModuleEventNoticeOutboxEntity.class.cast(value);
            entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE);
            result.add(row);
        }
        return List.copyOf(result);
    }

    /** core 所有の scalar 6 列と索引が必要。全状態の未完了 listener を current read する。
     * 業務 CLOSED_COMPLETE だけで pending を推測せず、payload/JSON を走査しない。
     * 列導入と後発 attempt の実 DB 負例が通るまでは結合 green の根拠にしない。 */
    private boolean hasPendingPublication(ModuleEventNoticeOutboxEntity row) {
        entityManager.flush();
        Query query = binding(entityManager.createNativeQuery("""
                SELECT a.publication_id FROM event_publication_attempts a
                JOIN event_publication p ON p.id = a.publication_id
                WHERE a.resolver_key = :resolver AND a.operation_id = :operation AND a.episode_id = :episode
                  AND a.scope_type = :scopeType AND a.scope_id = :scopeId
                  AND a.organization_id <=> :organization AND p.completion_date IS NULL
                LIMIT 1 FOR UPDATE
                """), row);
        return !query.getResultList().isEmpty();
    }

    private static boolean owned(ModuleEventNoticeOutboxEntity row, UUID token, String status) {
        return row != null && token != null && token.equals(row.getClaimToken()) && status.equals(row.getStatus());
    }

    private static boolean sameDescriptor(ModuleEventNoticeOutboxEntity left, ModuleEventNoticeOutboxEntity right) {
        return left.getDescriptorVersion() == right.getDescriptorVersion()
                && Objects.equals(left.getNoticeKey(), right.getNoticeKey())
                && Objects.equals(left.getResolverKey(), right.getResolverKey())
                && Objects.equals(left.getOperationId(), right.getOperationId())
                && Objects.equals(left.getEpisodeId(), right.getEpisodeId())
                && Objects.equals(left.getScopeType(), right.getScopeType())
                && Objects.equals(left.getScopeId(), right.getScopeId())
                && Objects.equals(left.getOrganizationId(), right.getOrganizationId())
                && Objects.equals(left.getPhase(), right.getPhase())
                && Objects.equals(left.getAudience(), right.getAudience());
    }

    private static Query binding(Query query, ModuleEventNoticeOutboxEntity row) {
        return query.setParameter("resolver", row.getResolverKey())
                .setParameter("operation", bytes(row.getOperationId()))
                .setParameter("episode", bytes(row.getEpisodeId()))
                .setParameter("scopeType", row.getScopeType()).setParameter("scopeId", row.getScopeId())
                .setParameter("organization", row.getOrganizationId());
    }

    private void requireTransaction() {
        if (!entityManager.isJoinedToTransaction()) {
            throw new IllegalStateException("通知予約 Store は既存 M TX 内で呼ぶ必要がある");
        }
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > MAX_CLAIMS) {
            throw new IllegalArgumentException("limit は 1..100 が必要");
        }
    }

    private static byte[] bytes(UUID id) {
        return id == null ? null : ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
