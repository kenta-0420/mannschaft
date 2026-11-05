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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実MySQLで本人への開示と強匿名化の境界を検証する。 */
@ModuleEventMySqlTest
@Import(ModuleEventResumeAuditPrivacyIT.AfterCommitConfiguration.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventResumeAuditPrivacyIT extends AbstractMySqlIntegrationTest {
    private static final long ACTOR = 919301L;
    private static final long OTHER = 919302L;
    @Autowired private ModuleEventResumeAuditPrivacyApi api;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationEventPublisher events;
    @PersistenceContext private EntityManager entityManager;
    private final List<UUID> publications = new ArrayList<>();

    @AfterEach
    void 検体を削除する() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            publications.forEach(id ->
                entityManager.createQuery("delete from ModuleEventResumeAuditEntity a where a.publicationId = :id")
                        .setParameter("id", id).executeUpdate());
            entityManager.createQuery("delete from ModuleEventResumeActorStateEntity s "
                            + "where s.actorUserId in :actors")
                    .setParameter("actors", List.of(ACTOR, OTHER, 919303L)).executeUpdate();
        });
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

    @Test
    void 保持期限はUTC暦年で計算し境界より古い行だけ削除する() {
        Instant now = Instant.parse("2024-02-29T12:00:00Z");
        Instant cutoff = Instant.parse("2022-02-28T12:00:00Z");
        fixture(ACTOR, "期限前", cutoff.minusNanos(1_000));
        fixture(ACTOR, "等号", cutoff);
        fixture(ACTOR, "期限後", cutoff.plusNanos(1_000));
        api.deleteExpired(now);
        assertThat(api.collectPersonalData(ACTOR)).extracting(row -> row.reason())
                .containsExactlyInAnyOrder("等号", "期限後");
        api.deleteExpired(now);
        assertThat(api.collectPersonalData(ACTOR)).hasSize(2);
        assertThatThrownBy(() -> api.deleteExpired(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 監査0件でも消去状態を作り本人のidとpurgedだけを冪等に返す() {
        assertThat(api.collectActorStates(ACTOR)).isEmpty();
        api.redactReasonsForPurgedUser(ACTOR);
        var first = api.collectActorStates(ACTOR);
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().id().version()).isEqualTo(7);
        assertThat(first.getFirst().purged()).isTrue();
        api.redactReasonsForPurgedUser(ACTOR);
        assertThat(api.collectActorStates(ACTOR)).isEqualTo(first);
        assertThat(api.collectActorStates(OTHER)).isEmpty();
        assertThat(ModuleEventResumeAuditPrivacyApi.ActorStateData.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName).containsExactly("id", "purged");
        assertThatThrownBy(() -> api.collectActorStates(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 読取専用の外側がrollbackしても強匿名化は独立primaryTXでcommitする() {
        fixture(ACTOR, "独立commit", Instant.now());
        TransactionTemplate outer = new TransactionTemplate(transactions);
        outer.setReadOnly(true);
        outer.executeWithoutResult(status -> {
            api.redactReasonsForPurgedUser(ACTOR);
            status.setRollbackOnly();
        });
        assertThat(api.collectPersonalData(ACTOR)).extracting(row -> row.reason()).containsExactly("[REDACTED]");
        assertThat(api.collectActorStates(ACTOR)).singleElement().satisfies(row -> assertThat(row.purged()).isTrue());
    }

    @Test
    void 実AFTER_COMMITから強匿名化を呼んだ結果が独立commitされる() {
        fixture(ACTOR, "配送後に消去", Instant.now());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            events.publishEvent(new PurgeFixtureEvent(ACTOR));
            assertThat(jdbc.queryForObject("SELECT reason FROM module_event_resume_audits "
                    + "WHERE actor_user_id = ?", String.class, ACTOR)).isEqualTo("配送後に消去");
        });
        assertThat(api.collectPersonalData(ACTOR)).extracting(row -> row.reason()).containsExactly("[REDACTED]");
        assertThat(api.collectActorStates(ACTOR)).singleElement().satisfies(row -> assertThat(row.purged()).isTrue());
    }

    @Test
    void 理由置換途中のDB失敗は初回fence作成と全置換をrollbackしretryできる() {
        fixture(ACTOR, "原文1", Instant.now());
        fixture(ACTOR, "原文2", Instant.now());
        jdbc.execute("""
                CREATE TRIGGER module_resume_privacy_fail BEFORE UPDATE ON module_event_resume_audits
                FOR EACH ROW
                BEGIN
                    IF OLD.reason = '原文2' AND NEW.reason = '[REDACTED]' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'privacy fixture failure';
                    END IF;
                END
                """);
        try {
            assertThatThrownBy(() -> api.redactReasonsForPurgedUser(ACTOR)).hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThat(jdbc.queryForList("SELECT reason FROM module_event_resume_audits WHERE actor_user_id = ?",
                    String.class, ACTOR)).containsExactlyInAnyOrder("原文1", "原文2");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM module_event_resume_actor_states "
                    + "WHERE actor_user_id = ?", Long.class, ACTOR)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS module_resume_privacy_fail");
        }
        api.redactReasonsForPurgedUser(ACTOR);
        assertThat(api.collectPersonalData(ACTOR)).extracting(row -> row.reason()).containsOnly("[REDACTED]");
        assertThat(api.collectActorStates(ACTOR)).singleElement().satisfies(row -> assertThat(row.purged()).isTrue());
    }

    /** C本体を変更せず、実際のAFTER_COMMIT配送境界だけを通す検体。 */
    record PurgeFixtureEvent(Long actorUserId) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class AfterCommitConfiguration {
        @Bean
        PurgeFixtureListener purgeFixtureListener(ModuleEventResumeAuditPrivacyApi api) {
            return new PurgeFixtureListener(api);
        }
    }

    /** listenerにはTXを付けず、M公開proxyへ配送する。 */
    static class PurgeFixtureListener {
        private final ModuleEventResumeAuditPrivacyApi api;

        PurgeFixtureListener(ModuleEventResumeAuditPrivacyApi api) {
            this.api = api;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onPurge(PurgeFixtureEvent event) {
            api.redactReasonsForPurgedUser(event.actorUserId());
        }
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
