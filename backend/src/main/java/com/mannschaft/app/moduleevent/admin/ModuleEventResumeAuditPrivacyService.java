package com.mannschaft.app.moduleevent.admin;

import com.mannschaft.app.moduleevent.api.ModuleEventResumeAuditPrivacyApi.PersonalData;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 再開監査の本人取得と保持期限削除を moduleevent の短い TX で扱う。 */
@Service
public class ModuleEventResumeAuditPrivacyService {
    private final EntityManager entityManager;

    public ModuleEventResumeAuditPrivacyService(EntityManager entityManager) {
        this.entityManager = entityManager;
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
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public int deleteExpired(Instant now) {
        Instant cutoff = Objects.requireNonNull(now, "now").atZone(ZoneOffset.UTC).minusYears(2).toInstant();
        return entityManager.createQuery("DELETE FROM ModuleEventResumeAuditEntity a WHERE a.createdAt < :cutoff")
                .setParameter("cutoff", cutoff).executeUpdate();
    }
}
