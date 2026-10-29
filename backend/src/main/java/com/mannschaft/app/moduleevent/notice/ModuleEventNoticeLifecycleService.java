package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeAudience;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticePhase;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeScope;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * M 通知予約だけを担当する。lock 順は helper current PK → main current read → impact notice → REC notice。
 * 業務 SPI/recipient/通知実装を呼ばず、成功は同期復旧判定でなく WAITING intent のみ残す。
 * Q 運用 alert の真の状態遷移/afterCommit と retention は core 接続の別 AC。
 */
@Service
public class ModuleEventNoticeLifecycleService {
    private final EntityManager entityManager;
    private final ModuleEventImpactResolverRegistry registry;

    public ModuleEventNoticeLifecycleService(EntityManager entityManager, ModuleEventImpactResolverRegistry registry) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** publication 原時刻が厳密に now-30分より前の有界 helper ID。limit は1..100。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
    public List<UUID> findDelayedCandidateIds(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit < 1 || limit > ModuleEventNoticeOutboxStore.MAX_CLAIMS) {
            throw new IllegalArgumentException("limit は 1..100 が必要");
        }
        List<?> ids = entityManager.createNativeQuery("""
                SELECT a.id FROM event_publication_attempts a
                JOIN event_publication p ON p.id = a.publication_id
                WHERE a.status IN ('PENDING', 'IN_PROGRESS') AND p.completion_date IS NULL
                  AND p.publication_date < :cutoff
                  AND REGEXP_LIKE(a.resolver_key, '^[a-z][a-z0-9.-]{0,99}$', 'c')
                  AND a.operation_id IS NOT NULL AND a.episode_id IS NOT NULL AND a.scope_id > 0
                  AND (a.organization_id IS NULL OR a.organization_id > 0)
                  AND (BINARY a.scope_type = 'TEAM'
                       OR (BINARY a.scope_type = 'ORGANIZATION' AND a.organization_id = a.scope_id))
                ORDER BY p.publication_date, a.id LIMIT :limit
                """).setParameter("cutoff", Timestamp.from(now.minusSeconds(1800)))
                .setParameter("limit", limit).getResultList();
        return ids.stream().map(ModuleEventNoticeLifecycleService::uuid).toList();
    }

    /** 外側 coordinator が候補ごとに Bean を呼ぶ。同 Bean 自己呼出しで短 TX を失わない。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
    public boolean reserveDelayed(UUID attemptId, Instant now) {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(now, "now");
        List<?> helpers = entityManager.createNativeQuery("""
                SELECT publication_id, resolver_key, operation_id, episode_id, scope_type, scope_id,
                       organization_id, status
                FROM event_publication_attempts WHERE id = :id FOR UPDATE
                """).setParameter("id", bytes(attemptId)).getResultList();
        if (helpers.isEmpty()) {
            return false;
        }
        Object[] helper = (Object[]) helpers.getFirst();
        if (!"PENDING".equals(helper[7]) && !"IN_PROGRESS".equals(helper[7])) {
            return false;
        }
        Optional<ModuleEventImpactBinding> binding = binding(helper);
        if (binding.isEmpty()) {
            return false;
        }
        // publication は Instant/UTC 列。helper の SERVER_ZONE LocalDateTime は比較に使わない。
        boolean delayed = !entityManager.createNativeQuery("""
                SELECT id FROM event_publication WHERE id = :publication
                  AND completion_date IS NULL AND publication_date < :cutoff FOR UPDATE
                """).setParameter("publication", bytes(uuid(helper[0])))
                .setParameter("cutoff", Timestamp.from(now.minusSeconds(1800))).getResultList().isEmpty();
        if (!delayed) {
            return false;
        }
        reserve(binding.get(), ModuleEventNoticePhase.DELAYED, now);
        return true;
    }

    /** core が現在世代を lock/隔離遷移した同 M TX に参加し、2 audience の Q を予約する。 */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = false)
    public void reserveQuarantined(ModuleEventImpactBinding binding, Instant now) {
        validate(binding, now);
        reserve(binding, ModuleEventNoticePhase.QUARANTINED, now);
    }

    /** core の成功削除前と同 M TX。既存 impact がある audience だけ RECOVERED/WAITING を予約する。 */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = false)
    public void beforeSuccessfulRemoval(ModuleEventImpactBinding binding, Instant now) {
        validate(binding, now);
        ModuleEventNoticeOutboxStore store = new ModuleEventNoticeOutboxStore(entityManager);
        for (ModuleEventNoticeAudience audience : ModuleEventNoticeAudience.values()) {
            store.reserveRecoveryIntent(reservation(binding, ModuleEventNoticePhase.RECOVERED, audience, now));
        }
    }

    private void reserve(ModuleEventImpactBinding binding, ModuleEventNoticePhase phase, Instant now) {
        ModuleEventNoticeOutboxStore store = new ModuleEventNoticeOutboxStore(entityManager);
        for (ModuleEventNoticeAudience audience : ModuleEventNoticeAudience.values()) {
            store.reserve(reservation(binding, phase, audience, now));
        }
    }

    private void validate(ModuleEventImpactBinding binding, Instant now) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(now, "now");
        registry.validateBinding(binding, binding.getResolverKey());
    }

    private Optional<ModuleEventImpactBinding> binding(Object[] helper) {
        try {
            ModuleEventImpactBinding binding = new ModuleEventImpactBinding((String) helper[1],
                    uuid(helper[2]), uuid(helper[3]), ModuleEventNoticeScope.valueOf((String) helper[4]),
                    ((Number) helper[5]).longValue(), helper[6] == null ? null : ((Number) helper[6]).longValue());
            registry.validateBinding(binding, binding.getResolverKey());
            return Optional.of(binding);
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException | ClassCastException ex) {
            // 未接続/破損 scalar から通知を予約しない。業務 provider は呼んでいない。
            return Optional.empty();
        }
    }

    private ModuleEventNoticeOutboxEntity reservation(ModuleEventImpactBinding binding,
            ModuleEventNoticePhase phase, ModuleEventNoticeAudience audience, Instant now) {
        // key の版と全6scalar/phase/audience を固定順・区切り付きで識別する。
        // resolverKey は検証済みで改行を含まず、他の型も区切りと衝突しない。
        String canonical = String.join("\n", "module-event-notice:v1", binding.getResolverKey(),
                binding.getOperationId().toString(), binding.getEpisodeId().toString(),
                binding.getScopeType().name(), binding.getScopeId().toString(),
                binding.getOrganizationId() == null ? "null" : binding.getOrganizationId().toString(),
                phase.name(), audience.name());
        UUID key = UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
        return ModuleEventNoticeOutboxEntity.builder().id(UuidV7.generate()).noticeKey(key)
                .resolverKey(binding.getResolverKey()).operationId(binding.getOperationId()).episodeId(binding.getEpisodeId())
                .scopeType(binding.getScopeType().name()).scopeId(binding.getScopeId()).organizationId(binding.getOrganizationId())
                .phase(phase.name()).audience(audience.name()).descriptorVersion(1)
                .status(phase == ModuleEventNoticePhase.RECOVERED ? "WAITING_RECOVERY" : "PENDING")
                .attemptCount(0).nextAttemptAt(now).createdAt(now).updatedAt(now).build();
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID id) {
            return id;
        }
        if (!(value instanceof byte[] raw) || raw.length != 16) {
            throw new IllegalArgumentException("publication/binding の UUID が不正");
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
