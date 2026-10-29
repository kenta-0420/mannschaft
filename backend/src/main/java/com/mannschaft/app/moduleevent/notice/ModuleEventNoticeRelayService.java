package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeAudience;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeClaim;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticePhase;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeScope;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 公開 facade の別 Bean として relay の PRIMARY 独立 TX を成立させる内部窓口。
 * method の明示 annotation を既存 routing Aspect が読み、外側 REPLICA hint を新接続へ持ち込まない。
 * Store はこの TX に参加する。業務 SPI・通知実装・HTTP・retention は扱わない。
 */
@Service
public class ModuleEventNoticeRelayService {
    private static final String RELAY_FAILED = "RELAY_FAILED";
    private final ModuleEventNoticeOutboxStore store;
    private final ModuleEventImpactResolverRegistry registry;

    public ModuleEventNoticeRelayService(EntityManager entityManager, ModuleEventImpactResolverRegistry registry) {
        store = new ModuleEventNoticeOutboxStore(Objects.requireNonNull(entityManager, "entityManager"));
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
    public List<ModuleEventNoticeClaim> claim(int limit, Instant now) {
        return store.claim(limit, now).stream().map(this::snapshot).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
    public boolean markRelayed(UUID id, UUID claimToken, Instant now) {
        return store.markRelayed(id, claimToken, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
    public boolean markFailed(UUID id, UUID claimToken, Instant nextAttemptAt, boolean dead, Instant now) {
        return store.markFailed(id, claimToken, RELAY_FAILED, nextAttemptAt, dead, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
    public boolean deferUnsupportedVersion(UUID id, UUID claimToken, Instant nextAttemptAt, Instant now) {
        return store.deferUnsupportedVersion(id, claimToken, nextAttemptAt, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
    public int recoverStuck(Instant claimedBefore, Instant now) {
        return store.recoverStuck(claimedBefore, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
    public Optional<Instant> oldestPendingCreatedAt() {
        return store.oldestPendingCreatedAt();
    }

    private ModuleEventNoticeClaim snapshot(ModuleEventNoticeOutboxEntity row) {
        Optional<ModuleEventNoticeDescriptor> descriptor = Optional.empty();
        if (row.getDescriptorVersion() == 1) {
            try {
                ModuleEventImpactBinding binding = new ModuleEventImpactBinding(row.getResolverKey(),
                        row.getOperationId(), row.getEpisodeId(), ModuleEventNoticeScope.valueOf(row.getScopeType()),
                        row.getScopeId(), row.getOrganizationId());
                // 登録 provider の有無は構造 claim の条件にしない。業務 SPI は呼ばず scalar だけ検証する。
                registry.validateBinding(binding, row.getResolverKey());
                descriptor = Optional.of(new ModuleEventNoticeDescriptor(row.getNoticeKey(), row.getNoticeRef(), binding,
                        ModuleEventNoticePhase.valueOf(row.getPhase()), ModuleEventNoticeAudience.valueOf(row.getAudience())));
            } catch (IllegalArgumentException | IllegalStateException | NullPointerException failure) {
                // 既知版破損は元の版を保持する。adapter の固定 invalid payload から有界失敗へ進める。
                descriptor = Optional.empty();
            }
        }
        return new ModuleEventNoticeClaim(row.getId(), row.getClaimToken(), row.getNoticeRef(),
                row.getDescriptorVersion(), row.getAttemptCount(), descriptor);
    }
}
