package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * M 通知予約だけの先行骨格。実 DB assertion red 前なので全操作は未実装。
 * lock 順は helper current PK →対応 main current read→impact notice→REC notice。
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
        return List.of();
    }

    /** 外側 coordinator が候補ごとに Bean を呼ぶ。同 Bean 自己呼出しで短 TX を失わない。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
    public boolean reserveDelayed(UUID attemptId, Instant now) {
        return false;
    }

    /** core が現在世代を lock/隔離遷移した同 M TX に参加し、2 audience の Q を予約する。 */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = false)
    public void reserveQuarantined(ModuleEventImpactBinding binding, Instant now) { }

    /** core の成功削除前と同 M TX。既存 impact がある audience だけ RECOVERED/WAITING を予約する。 */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = false)
    public void beforeSuccessfulRemoval(ModuleEventImpactBinding binding, Instant now) { }
}
