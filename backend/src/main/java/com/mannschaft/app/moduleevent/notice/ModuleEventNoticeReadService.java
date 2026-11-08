package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeAudience;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticePhase;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeScope;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * M 自領域の有界読取だけを担当する別 Bean。業務 SPI を M TX 内で呼ばない。
 * notice_ref unique の1行を版判定後に DTO 化し、6 scalar と本表未完了を LIMIT 1 で照合する。
 * 未知版の enum 変換や payload 再 deserialize は行わない。
 */
@Service
public class ModuleEventNoticeReadService {
    private final EntityManager entityManager;
    private final ModuleEventImpactResolverRegistry registry;

    public ModuleEventNoticeReadService(EntityManager entityManager, ModuleEventImpactResolverRegistry registry) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
    public Optional<Snapshot> snapshot(long noticeRef) {
        if (noticeRef <= 0) {
            throw new IllegalArgumentException("通知参照 ID は正数が必要");
        }
        List<?> rows = entityManager.createNativeQuery("""
                SELECT descriptor_version, notice_key, resolver_key, operation_id, episode_id,
                       scope_type, scope_id, organization_id, phase, audience, status
                FROM module_event_notice_outbox WHERE notice_ref = :ref LIMIT 1
                """).setParameter("ref", noticeRef).getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.getFirst();
        if (!(row[0] instanceof Number version) || version.intValue() != 1) {
            return Optional.empty();
        }
        ModuleEventNoticeDescriptor descriptor;
        try {
            String resolverKey = (String) row[2];
            if (registry.find(resolverKey).isEmpty()) {
                return Optional.empty();
            }
            ModuleEventImpactBinding binding = new ModuleEventImpactBinding(resolverKey,
                    uuid(row[3]), uuid(row[4]), ModuleEventNoticeScope.valueOf((String) row[5]),
                    ((Number) row[6]).longValue(), row[7] == null ? null : ((Number) row[7]).longValue());
            registry.validateBinding(binding, resolverKey);
            descriptor = new ModuleEventNoticeDescriptor(uuid(row[1]), noticeRef, binding,
                    ModuleEventNoticePhase.valueOf((String) row[8]),
                    ModuleEventNoticeAudience.valueOf((String) row[9]));
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException | ClassCastException ex) {
            // 永続 descriptor の不正は未解決。業務 provider の例外はこの範囲で呼ばない。
            return Optional.empty();
        }
        ModuleEventImpactBinding binding = descriptor.getImpact();
        boolean pending = !entityManager.createNativeQuery("""
                SELECT a.publication_id FROM event_publication_attempts a
                JOIN event_publication p ON p.id = a.publication_id
                WHERE a.resolver_key = :resolver AND a.operation_id = :operation AND a.episode_id = :episode
                  AND a.scope_type = :scopeType AND a.scope_id = :scopeId
                  AND a.organization_id <=> :organization AND p.completion_date IS NULL
                LIMIT 1
                """).setParameter("resolver", binding.getResolverKey())
                .setParameter("operation", bytes(binding.getOperationId()))
                .setParameter("episode", bytes(binding.getEpisodeId()))
                .setParameter("scopeType", binding.getScopeType().name())
                .setParameter("scopeId", binding.getScopeId())
                .setParameter("organization", binding.getOrganizationId()).getResultList().isEmpty();
        return Optional.of(new Snapshot(descriptor, pending, (String) row[10]));
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID id) {
            return id;
        }
        if (!(value instanceof byte[] raw) || raw.length != 16) {
            throw new IllegalArgumentException("通知の UUID が不正");
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array();
    }

    /** API 内部だけの scalar snapshot。通知側へこの型や Entity を公開しない。 */
    @Getter
    public static final class Snapshot {
        private final ModuleEventNoticeDescriptor descriptor;
        private final boolean pending;
        private final String status;

        public Snapshot(ModuleEventNoticeDescriptor descriptor, boolean pending, String status) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
            this.pending = pending;
            this.status = Objects.requireNonNull(status, "status");
        }
    }
}
