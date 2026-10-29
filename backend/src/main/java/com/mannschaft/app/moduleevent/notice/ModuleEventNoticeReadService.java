package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * M 自領域の有界読取だけを担当する別 Bean。業務 SPI を M TX 内で呼ばない。
 * notice_ref unique の1行を版判定後に DTO 化し、6 scalar と本表未完了を LIMIT 1 で照合する予定。
 * 実 DB assertion red 待ちの骨格。未知版の enum 変換や payload 再 deserialize は行わない。
 */
@Service
public class ModuleEventNoticeReadService {
    private final EntityManager entityManager;
    private final ModuleEventImpactResolverRegistry registry;

    public ModuleEventNoticeReadService(EntityManager entityManager, ModuleEventImpactResolverRegistry registry) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Snapshot> snapshot(long noticeRef) {
        return Optional.empty();
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
