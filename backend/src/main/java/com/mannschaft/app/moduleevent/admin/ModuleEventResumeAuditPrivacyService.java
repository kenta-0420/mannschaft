package com.mannschaft.app.moduleevent.admin;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.moduleevent.api.ModuleEventResumeAuditPrivacyApi.ActorStateData;
import com.mannschaft.app.moduleevent.api.ModuleEventResumeAuditPrivacyApi.PersonalData;
import com.mannschaft.app.moduleevent.repository.ModuleEventResumeActorStateRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 再開監査の本人取得・強匿名化・保持期限削除を moduleevent の短い TX で扱う。 */
@Service
public class ModuleEventResumeAuditPrivacyService {
    private final EntityManager entityManager;
    private final ModuleEventResumeActorStateRepository actorStates;

    public ModuleEventResumeAuditPrivacyService(EntityManager entityManager,
            ModuleEventResumeActorStateRepository actorStates) {
        this.entityManager = entityManager;
        this.actorStates = actorStates;
    }

    /** 外側TXのL1に依存せず、本人の状態0/1件だけをprimaryから取得する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public List<ActorStateData> collectActorStates(Long actorUserId) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        return entityManager.createQuery("""
                SELECT s FROM ModuleEventResumeActorStateEntity s WHERE s.actorUserId = :actorUserId
                """, ModuleEventResumeActorStateEntity.class)
                .setParameter("actorUserId", actorUserId).getResultList().stream()
                .map(s -> new ActorStateData(s.getId(), s.isPurged())).toList();
    }

    /** 監査0件でも不可逆stateを残し、再開と同じactorロックで全理由を置換する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public void redactReasonsForPurgedUser(Long actorUserId) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        actorStates.createIfAbsent(UuidV7.generate().toString(), actorUserId);
        Long purged = actorStates.lockPurged(actorUserId);
        if (purged == null || (purged != 0L && purged != 1L)) {
            throw new IllegalStateException("再開理由の再生防止状態を確認できません");
        }
        actorStates.markPurged(actorUserId);
        entityManager.createQuery("""
                UPDATE ModuleEventResumeAuditEntity a SET a.reason = :reason WHERE a.actorUserId = :actorUserId
                """)
                .setParameter("reason", "[REDACTED]").setParameter("actorUserId", actorUserId).executeUpdate();
    }

    /** 外側が readOnly でも最新の本人データを primary から読む。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public List<PersonalData> collectPersonalData(Long actorUserId) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        return entityManager.createQuery("""
                SELECT a FROM ModuleEventResumeAuditEntity a
                WHERE a.actorUserId = :actorUserId
                ORDER BY a.createdAt DESC, a.id DESC
                """, ModuleEventResumeAuditEntity.class)
                .setParameter("actorUserId", actorUserId).getResultList().stream()
                .map(a -> new PersonalData(a.getId(), a.getPublicationId(), a.getReason(), a.getCreatedAt()))
                .toList();
    }

    /** UTC の暦年2年より厳密に古い監査だけを削除する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false, timeout = 300)
    public int deleteExpired(Instant now) {
        Instant cutoff = Objects.requireNonNull(now, "now").atZone(ZoneOffset.UTC).minusYears(2).toInstant();
        return entityManager.createQuery("DELETE FROM ModuleEventResumeAuditEntity a WHERE a.createdAt < :cutoff")
                .setParameter("cutoff", cutoff).executeUpdate();
    }
}
