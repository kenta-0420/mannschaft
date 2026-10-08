package com.mannschaft.app.activity;

import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.common.activityschedule.AutomaticScheduleCompletionBatch;
import com.mannschaft.app.common.activityschedule.AutomaticScheduleCompletionFacade;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.service.ScheduleCompletionService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CMP-261008-1203の完了を実MySQL・commit後別TXで検証する。
 * 実ShedLock試験だけ小さな独立AOP contextを組み、共通Spring/MySQL contextを増やさない。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ActivityAutomaticCompletionIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-16T01:00:34+09:00");
    private static final long TEAM = 940208301L;
    private static final long ORGANIZATION = 940208302L;
    @Autowired private ScheduleCompletionService schedules;
    @Autowired private AutomaticScheduleCompletionFacade completion;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager em;
    private TransactionTemplate tx;
    private final List<Long> scheduleIds = new ArrayList<>();
    private final List<Long> activityIds = new ArrayList<>();

    @BeforeEach
    void setup() { tx = new TransactionTemplate(transactions); }

    @AfterEach
    void cleanup() {
        tx.executeWithoutResult(status -> {
            if (!activityIds.isEmpty()) {
                em.createNativeQuery("DELETE FROM activity_results WHERE id IN :ids")
                        .setParameter("ids", activityIds).executeUpdate();
            }
            if (!scheduleIds.isEmpty()) {
                em.createNativeQuery("DELETE FROM schedules WHERE id IN :ids")
                        .setParameter("ids", scheduleIds).executeUpdate();
            }
        });
    }

    @Test
    void 終了が同時刻と未来は残し直前だけを完了する() {
        Pair past = fixture(NOW.minusSeconds(1), "TEAM", ScheduleStatus.SCHEDULED, false, false);
        Pair same = fixture(NOW, "TEAM", ScheduleStatus.SCHEDULED, false, false);
        Pair future = fixture(NOW.plusSeconds(1), "ORGANIZATION", ScheduleStatus.SCHEDULED, false, false);
        assertThat(schedules.findDueIds(NOW, 100)).contains(past.scheduleId()).doesNotContain(same.scheduleId(), future.scheduleId());
        assertThat(completion.completeOne(past.scheduleId(), NOW)).isTrue();
        assertThat(completion.completeOne(same.scheduleId(), NOW)).isFalse();
        assertThat(completion.completeOne(future.scheduleId(), NOW)).isFalse();
        assertState(past, ScheduleStatus.COMPLETED, false);
        assertState(same, ScheduleStatus.SCHEDULED, true);
        assertState(future, ScheduleStatus.SCHEDULED, true);
    }

    @Test
    void null取消削除繰返し親と個人は候補から除外する() {
        Pair noEnd = fixture(null, "TEAM", ScheduleStatus.SCHEDULED, false, false);
        Pair cancelled = fixture(NOW.minusSeconds(1), "TEAM", ScheduleStatus.CANCELLED, false, false);
        Pair deleted = fixture(NOW.minusSeconds(1), "TEAM", ScheduleStatus.SCHEDULED, false, true);
        Pair parent = fixture(NOW.minusSeconds(1), "TEAM", ScheduleStatus.SCHEDULED, true, false);
        Pair personal = fixture(NOW.minusSeconds(1), "PERSONAL", ScheduleStatus.SCHEDULED, false, false);
        assertThat(schedules.findDueIds(NOW, 100)).doesNotContain(noEnd.scheduleId(), cancelled.scheduleId(),
                deleted.scheduleId(), parent.scheduleId(), personal.scheduleId());
        for (Pair pair : List.of(noEnd, cancelled, deleted, parent, personal)) {
            assertThat(completion.completeOne(pair.scheduleId(), NOW)).isFalse();
        }
        assertState(noEnd, ScheduleStatus.SCHEDULED, true);
        assertState(cancelled, ScheduleStatus.CANCELLED, true);
        assertState(parent, ScheduleStatus.SCHEDULED, true);
        assertState(personal, ScheduleStatus.SCHEDULED, true);
    }

    @Test
    void 候補取得後の延期を行ロック後に再確認する() {
        Pair pair = fixture(NOW.minusSeconds(1), "TEAM", ScheduleStatus.SCHEDULED, false, false);
        assertThat(schedules.findDueIds(NOW, 100)).contains(pair.scheduleId());
        tx.executeWithoutResult(status -> {
            ScheduleEntity value = em.find(ScheduleEntity.class, pair.scheduleId());
            value.updateScheduleFields(value.getTitle(), value.getDescription(), value.getLocation(),
                    value.getStartAt(), NOW.plusHours(1).toLocalDateTime(), value.getColor());
        });
        assertThat(completion.completeOne(pair.scheduleId(), NOW)).isFalse();
        assertState(pair, ScheduleStatus.SCHEDULED, true);
    }

    @Test
    void 完了は実績と公開状態を保護し再処理でversionを増やさない() {
        Pair pair = fixture(NOW.minusSeconds(1), "ORGANIZATION", ScheduleStatus.SCHEDULED, false, false);
        long before = version(pair);
        assertThat(completion.completeOne(pair.scheduleId(), NOW)).isTrue();
        assertThat(version(pair)).isEqualTo(before + 1);
        assertThat(completion.completeOne(pair.scheduleId(), NOW)).isFalse();
        assertThat(version(pair)).isEqualTo(before + 1);
        tx.executeWithoutResult(status -> {
            ActivityResultEntity value = em.find(ActivityResultEntity.class, pair.activityId());
            assertThat(value.getStatus()).isEqualTo(ActivityStatus.PUBLISHED);
            assertThat(value.getVisibility()).isEqualTo(ActivityVisibility.PUBLIC);
            assertThat(value.getTitle()).isEqualTo("実績手編集");
            assertThat(value.getDescription()).isEqualTo("実績本文");
            assertThat(value.getFieldValues()).isEqualTo("{\"zero\":0,\"flag\":false,\"empty\":\"\"}");
            assertThat(value.getAttachments()).isEqualTo("{\"file_ids\":[9001]}");
            assertThat(value.getTemplateId()).isEqualTo(9002L);
        });
    }

    @Test
    void 未来と同時刻への延期で予定へ戻りnull延期では戻さない() {
        for (OffsetDateTime end : List.of(NOW, NOW.plusSeconds(1))) {
            Pair pair = fixture(end, "TEAM", ScheduleStatus.COMPLETED, false, false);
            tx.executeWithoutResult(status -> {
                // fixtureのcompleted活動はplanned=falseで保存済み。
                completion.reopenFuture(List.of(pair.scheduleId()), NOW);
            });
            assertState(pair, ScheduleStatus.SCHEDULED, true);
        }
        Pair noEnd = fixture(null, "TEAM", ScheduleStatus.COMPLETED, false, false);
        tx.executeWithoutResult(status -> completion.reopenFuture(List.of(noEnd.scheduleId()), NOW));
        assertState(noEnd, ScheduleStatus.COMPLETED, false);
    }

    @Test
    void 異offsetの同じinstantは同じ期限境界になる() {
        Pair pair = fixture(NOW.minusSeconds(1), "TEAM", ScheduleStatus.SCHEDULED, false, false);
        assertThat(completion.completeOne(pair.scheduleId(), NOW.withOffsetSameInstant(java.time.ZoneOffset.UTC))).isTrue();
        assertState(pair, ScheduleStatus.COMPLETED, false);
    }

    @Test
    void 旧予定も完了するが既存手動活動は変更も追加もしない() {
        Pair pair = fixture(NOW.minusSeconds(1), "TEAM", ScheduleStatus.SCHEDULED, false, false, false);
        assertThat(completion.completeOne(pair.scheduleId(), NOW)).isTrue();
        assertState(pair, ScheduleStatus.COMPLETED, false);
        assertThat(version(pair)).isZero();
        assertThat(countRecords(pair)).isEqualTo(1L);
    }

    @Test
    void 百一件は二回で収束し三回目は無更新になる() {
        for (int i = 0; i < 101; i++) fixture(NOW.minusSeconds(2), "TEAM", ScheduleStatus.SCHEDULED, false, false);
        AutomaticScheduleCompletionBatch batch = batch(completion);
        assertThat(schedules.findDueIds(NOW, 100)).hasSize(100).isSorted();
        assertThat(batch.runBatch()).isEqualTo(100);
        assertThat(schedules.findDueIds(NOW, 100)).hasSize(1);
        assertThat(batch.runBatch()).isEqualTo(1);
        assertThat(batch.runBatch()).isZero();
    }

    @Test
    void 活動削除後の予定完了は記録を復活させない() {
        Pair pair = fixture(NOW.minusSeconds(1), "TEAM", ScheduleStatus.SCHEDULED, false, false);
        tx.executeWithoutResult(status -> em.find(ActivityResultEntity.class, pair.activityId()).softDelete());
        assertThat(completion.completeOne(pair.scheduleId(), NOW)).isTrue();
        assertThat(countRecords(pair)).isEqualTo(1L);
        ActivityResultEntity deleted = tx.execute(status -> em.find(ActivityResultEntity.class, pair.activityId()));
        assertThat(deleted).isNull();
    }

    @Test
    void 外側TXの失敗は予定完了と活動表示を一緒に戻す() {
        Pair pair = fixture(NOW.minusSeconds(1), "TEAM", ScheduleStatus.SCHEDULED, false, false);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            assertThat(completion.completeOne(pair.scheduleId(), NOW)).isTrue();
            throw new IllegalStateException("途中失敗の試練");
        })).isInstanceOf(IllegalStateException.class);
        assertState(pair, ScheduleStatus.SCHEDULED, true);
        assertThat(version(pair)).isZero();
    }

    @Test
    void 一行失敗しても後続の短TXは完了して実成功数を返す() {
        Pair first = fixture(NOW.minusSeconds(2), "TEAM", ScheduleStatus.SCHEDULED, false, false);
        Pair second = fixture(NOW.minusSeconds(1), "TEAM", ScheduleStatus.SCHEDULED, false, false);
        AutomaticScheduleCompletionFacade failing = mock(AutomaticScheduleCompletionFacade.class);
        when(failing.completeOne(any(), any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            if (id.equals(first.scheduleId())) throw new IllegalStateException("一行失敗の試練");
            return completion.completeOne(id, invocation.getArgument(1));
        });
        assertThat(batch(failing).runBatch()).isEqualTo(1);
        assertState(first, ScheduleStatus.SCHEDULED, true);
        assertState(second, ScheduleStatus.COMPLETED, false);
    }

    @Test
    void 実ShedLockの別保有者がいる間はskipし解放後だけ完了する() {
        Pair pair = fixture(NOW.minusSeconds(1), "TEAM", ScheduleStatus.SCHEDULED, false, false);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String table = "completion_lock_" + UUID.randomUUID().toString().replace("-", "");
        // 固有の試験テーブルのみ。INSERTは実LockProviderが実行する。
        jdbc.execute("CREATE TABLE " + table + " (name VARCHAR(64) NOT NULL PRIMARY KEY, lock_until TIMESTAMP(3) NOT NULL,"
                + " locked_at TIMESTAMP(3) NOT NULL, locked_by VARCHAR(255) NOT NULL)");
        LockProvider provider = new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc).withTableName(table).usingDbTime().build());
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(LockProxyConfiguration.class);
            context.registerBean(LockProvider.class, () -> provider);
            context.registerBean(AutomaticScheduleCompletionBatch.class, () -> batch(completion));
            context.refresh();
            var batch = context.getBean(AutomaticScheduleCompletionBatch.class);
            var lock = provider.lock(new LockConfiguration(java.time.Instant.now(), AutomaticScheduleCompletionBatch.LOCK_NAME,
                    Duration.ofMinutes(2), Duration.ZERO)).orElseThrow();
            try {
                assertThat(batch.runBatch()).isNull();
                assertState(pair, ScheduleStatus.SCHEDULED, true);
            } finally {
                lock.unlock();
            }
            assertThat(batch.runBatch()).isEqualTo(1);
            assertState(pair, ScheduleStatus.COMPLETED, false);
        } finally {
            jdbc.execute("DROP TABLE " + table);
        }
    }

    @TestConfiguration
    @EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
    static class LockProxyConfiguration { }

    private AutomaticScheduleCompletionBatch batch(AutomaticScheduleCompletionFacade facade) {
        return new AutomaticScheduleCompletionBatch(schedules, facade,
                Clock.fixed(NOW.toInstant(), UserZoneLocalDateTimeParser.SERVER_ZONE));
    }

    private Pair fixture(OffsetDateTime end, String scope, ScheduleStatus status, boolean parent, boolean deleted) {
        return fixture(end, scope, status, parent, deleted, true);
    }

    private Pair fixture(OffsetDateTime end, String scope, ScheduleStatus status, boolean parent, boolean deleted,
                         boolean automatic) {
        return tx.execute(transaction -> {
            ScheduleEntity schedule = ScheduleEntity.builder().title("元予定")
                    .eventType(EventType.PRACTICE).visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .teamId("TEAM".equals(scope) ? TEAM : null)
                    .organizationId("ORGANIZATION".equals(scope) ? ORGANIZATION : null)
                    .userId("PERSONAL".equals(scope) ? TEAM : null)
                    .createdBy(TEAM).startAt(NOW.minusHours(2).toLocalDateTime())
                    .endAt(end == null ? null : end.toLocalDateTime()).status(status)
                    .recurrenceRule(parent ? "{\"frequency\":\"DAILY\"}" : null)
                    .deletedAt(deleted ? NOW.toLocalDateTime() : null).build();
            em.persist(schedule);
            em.flush();
            scheduleIds.add(schedule.getId());
            ActivityResultEntity activity = ActivityResultEntity.builder().scopeType("ORGANIZATION".equals(scope)
                    ? ActivityScopeType.ORGANIZATION : ActivityScopeType.TEAM).scopeId("ORGANIZATION".equals(scope) ? ORGANIZATION : TEAM)
                    .scheduleId(schedule.getId()).createdBy(TEAM).autoGeneratedFromSchedule(automatic)
                    .planned(automatic && status != ScheduleStatus.COMPLETED).title("実績手編集")
                    .activityDate(NOW.toLocalDate()).activityTimeStart(java.time.LocalTime.of(23, 0))
                    .activityTimeEnd(java.time.LocalTime.of(1, 0)).description("実績本文")
                    .fieldValues("{\"zero\":0,\"flag\":false,\"empty\":\"\"}").attachments("{\"file_ids\":[9001]}")
                    .templateId(9002L).status(ActivityStatus.PUBLISHED).visibility(ActivityVisibility.PUBLIC).build();
            em.persist(activity);
            em.flush();
            activityIds.add(activity.getId());
            return new Pair(schedule.getId(), activity.getId());
        });
    }

    private long version(Pair pair) {
        return tx.execute(status -> em.find(ActivityResultEntity.class, pair.activityId()).getVersion());
    }

    private long countRecords(Pair pair) {
        return tx.execute(status -> ((Number) em.createNativeQuery("SELECT COUNT(*) FROM activity_results WHERE schedule_id=:id")
                .setParameter("id", pair.scheduleId()).getSingleResult()).longValue());
    }

    private void assertState(Pair pair, ScheduleStatus scheduleStatus, boolean planned) {
        tx.executeWithoutResult(status -> {
            assertThat(em.find(ScheduleEntity.class, pair.scheduleId()).getStatus()).isEqualTo(scheduleStatus);
            assertThat(em.find(ActivityResultEntity.class, pair.activityId()).isPlanned()).isEqualTo(planned);
        });
    }

    private record Pair(Long scheduleId, Long activityId) { }
}
