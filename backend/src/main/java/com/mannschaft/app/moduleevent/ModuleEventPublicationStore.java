package com.mannschaft.app.moduleevent;

import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 有界SQL・codec・短TXの内部窓口。App試練のred前なので操作は未実装。 */
public class ModuleEventPublicationStore {
    private final EntityManager entityManager;
    private final EventPublicationRepository repository;
    private final EventSerializer serializer;
    private final ModuleEventListenerCatalog catalog;
    private final Clock clock;

    public ModuleEventPublicationStore(EntityManager entityManager, EventPublicationRepository repository,
            EventSerializer serializer, ModuleEventListenerCatalog catalog, Clock clock) {
        this.entityManager = entityManager;
        this.repository = repository;
        this.serializer = serializer;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** 初回補助保存はpublisherと同一物理TX。独立TXへ逃がさない。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public EventPublicationAttemptEntity createInitialAttempt(UUID publicationId, UUID token,
            ModuleEventImpactBinding binding) {
        return null;
    }

    /** readOnly呼出元からも既存routing AspectがPRIMARYを指定する公開Bean境界。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            readOnly = false)
    public List<Claim> claim(Instant cutoff, Instant now, int limit) {
        return List.of();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            readOnly = false)
    public boolean admit(ModuleEventExecutionFrame frame) {
        return false;
    }

    /** 有界な取得行。Catalog許可型との完全一致を確認してから復元する。 */
    public record Claim(UUID publicationId, UUID attemptId, UUID token, String listenerId,
            String eventType, String serializedEvent, Instant publicationDate) { }
}
