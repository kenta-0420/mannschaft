package com.mannschaft.app.moduleevent;

import com.mannschaft.app.moduleevent.admin.ModuleEventResumeAuditEntity;
import com.mannschaft.app.moduleevent.api.ModuleEventResumeAuditPrivacyApi;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実MySQLで本人への開示と強匿名化の境界を検証する。 */
@ModuleEventMySqlTest
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventResumeAuditPrivacyIT extends AbstractMySqlIntegrationTest {
    private static final long ACTOR = 919301L;
    private static final long OTHER = 919302L;
    @Autowired private ModuleEventResumeAuditPrivacyApi api;
    @Autowired private PlatformTransactionManager transactions;
    @PersistenceContext private EntityManager entityManager;
    private final List<UUID> publications = new ArrayList<>();

    @AfterEach
    void 検体を削除する() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> publications.forEach(id ->
                entityManager.createQuery("delete from ModuleEventResumeAuditEntity a where a.publicationId = :id")
                        .setParameter("id", id).executeUpdate()));
    }

    @Test
    void 本人の全履歴だけを時刻とIDの降順で返す() {
        Instant time = Instant.parse("2024-02-29T12:00:00Z");
        for (int i = 0; i < 110; i++) {
            fixture(ACTOR, "本人" + i, time.plusSeconds(i / 2));
        }
        fixture(OTHER, "他人", time.plusSeconds(1000));
        var rows = api.collectPersonalData(ACTOR);
        assertThat(rows).hasSize(110).allSatisfy(row -> assertThat(row.reason()).startsWith("本人"));
        for (int i = 1; i < rows.size(); i++) {
            var previous = rows.get(i - 1);
            var current = rows.get(i);
            assertThat(previous.createdAt()).isAfterOrEqualTo(current.createdAt());
            if (previous.createdAt().equals(current.createdAt())) {
                assertThat(previous.id().toString()).isGreaterThan(current.id().toString());
            }
        }
        assertThat(api.collectPersonalData(919303L)).isEmpty();
        assertThatThrownBy(() -> api.collectPersonalData(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 強匿名化は本人の理由だけを置換し再試行できる() {
        Instant time = Instant.parse("2024-02-29T12:00:00Z");
        fixture(ACTOR, "秘匿", time);
        fixture(OTHER, "他人の理由", time);
        var before = api.collectPersonalData(ACTOR).getFirst();
        var other = api.collectPersonalData(OTHER);
        api.redactReasonsForPurgedUser(ACTOR);
        api.redactReasonsForPurgedUser(ACTOR);
        api.redactReasonsForPurgedUser(919303L);
        var after = api.collectPersonalData(ACTOR).getFirst();
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.publicationId()).isEqualTo(before.publicationId());
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(after.reason()).isEqualTo("[REDACTED]");
        assertThat(api.collectPersonalData(OTHER)).isEqualTo(other);
        assertThatThrownBy(() -> api.redactReasonsForPurgedUser(null)).isInstanceOf(NullPointerException.class);
    }

    private void fixture(long actor, String reason, Instant createdAt) {
        UUID publication = UUID.randomUUID();
        publications.add(publication);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            entityManager.persist(ModuleEventResumeAuditEntity.builder().publicationId(publication)
                    .actorUserId(actor).reason(reason).createdAt(createdAt).build());
            entityManager.flush();
        });
    }
}
