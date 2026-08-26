package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.CalendarSyncScopeType;
import com.mannschaft.app.schedule.ScheduledTaskStatus;
import com.mannschaft.app.schedule.ScheduledTaskType;
import com.mannschaft.app.schedule.entity.ScheduleScheduledTaskEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 機能55 第一陣 — {@link ScheduleScheduledTaskRepository} 結合テスト。
 *
 * <p>UUIDv7 主キーの Entity マッピング・JSON カラム・
 * {@link com.mannschaft.app.common.repository.AbstractTenantAwareRepository} 継承の派生クエリ・
 * カスタム finder がランタイムで解決され、永続化/検索が通ることを検証する。</p>
 */
@Transactional
@DisplayName("ScheduleScheduledTaskRepository 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ScheduleScheduledTaskRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ScheduleScheduledTaskRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long SCHEDULE_ID = 9101L;
    private static final Long ORG_A = 2001L;
    private static final Long ORG_B = 2002L;

    private ScheduleScheduledTaskEntity persist(Long scheduleId, Long orgId,
                                                ScheduledTaskType taskType,
                                                ScheduledTaskStatus status,
                                                LocalDateTime scheduledAt) {
        ScheduleScheduledTaskEntity e = ScheduleScheduledTaskEntity.builder()
                .scheduleId(scheduleId)
                .organizationId(orgId)
                .scopeType(CalendarSyncScopeType.TEAM)
                .scopeId(3001L)
                .taskType(taskType)
                .scheduledAt(scheduledAt)
                .status(status)
                .payloadJson("{\"title\":\"出欠確認\",\"options\":[\"出席\",\"欠席\"]}")
                .createdBy(7001L)
                .build();
        em.persist(e);
        em.flush();
        em.clear();
        return e;
    }

    @Test
    @DisplayName("保存_全フィールドが永続化される")
    void 保存_全フィールドが永続化される() {
        LocalDateTime at = LocalDateTime.of(2026, 7, 1, 9, 0);
        ScheduleScheduledTaskEntity saved =
                persist(SCHEDULE_ID, ORG_A, ScheduledTaskType.SURVEY, ScheduledTaskStatus.PENDING, at);

        ScheduleScheduledTaskEntity found = em.find(ScheduleScheduledTaskEntity.class, saved.getId());
        assertThat(found).isNotNull();
        assertThat(found.getId()).isNotNull();
        assertThat(found.getScheduleId()).isEqualTo(SCHEDULE_ID);
        assertThat(found.getOrganizationId()).isEqualTo(ORG_A);
        assertThat(found.getScopeType()).isEqualTo(CalendarSyncScopeType.TEAM);
        assertThat(found.getScopeId()).isEqualTo(3001L);
        assertThat(found.getTaskType()).isEqualTo(ScheduledTaskType.SURVEY);
        assertThat(found.getScheduledAt()).isEqualTo(at);
        assertThat(found.getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
        assertThat(found.getPayloadJson()).contains("出欠確認");
        assertThat(found.getAttemptCount()).isZero();
        assertThat(found.getCreatedBy()).isEqualTo(7001L);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("findByStatusAndScheduledAtBeforeAndDeletedAtIsNull — materialize 対象のみ取得できる")
    void materialize対象のみ取得できる() {
        LocalDateTime base = LocalDateTime.of(2026, 7, 1, 9, 0);
        // 到来済み PENDING（対象）
        persist(SCHEDULE_ID, ORG_A, ScheduledTaskType.ATTENDANCE, ScheduledTaskStatus.PENDING, base.minusMinutes(10));
        // 未到来 PENDING（対象外）
        persist(SCHEDULE_ID, ORG_A, ScheduledTaskType.ATTENDANCE, ScheduledTaskStatus.PENDING, base.plusHours(1));
        // 到来済みだが CREATED（対象外）
        persist(SCHEDULE_ID, ORG_A, ScheduledTaskType.SURVEY, ScheduledTaskStatus.CREATED, base.minusMinutes(10));

        List<ScheduleScheduledTaskEntity> due =
                repository.findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(ScheduledTaskStatus.PENDING, base);

        assertThat(due).hasSize(1);
        assertThat(due.get(0).getTaskType()).isEqualTo(ScheduledTaskType.ATTENDANCE);
    }

    @Test
    @DisplayName("findByScheduleIdAndDeletedAtIsNull — 親予定の予約タスクを取得できる")
    void 親予定の予約タスクを取得できる() {
        LocalDateTime at = LocalDateTime.of(2026, 7, 1, 9, 0);
        persist(SCHEDULE_ID, ORG_A, ScheduledTaskType.SURVEY, ScheduledTaskStatus.PENDING, at);
        persist(SCHEDULE_ID, ORG_A, ScheduledTaskType.ATTENDANCE, ScheduledTaskStatus.PENDING, at);
        persist(9999L, ORG_A, ScheduledTaskType.SURVEY, ScheduledTaskStatus.PENDING, at);

        List<ScheduleScheduledTaskEntity> result =
                repository.findByScheduleIdAndDeletedAtIsNull(SCHEDULE_ID);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("findByIdAndOrganizationIdAndDeletedAtIsNull — 別組織からは見えない（IDOR対策）")
    void 別組織からは見えない() {
        LocalDateTime at = LocalDateTime.of(2026, 7, 1, 9, 0);
        ScheduleScheduledTaskEntity saved =
                persist(SCHEDULE_ID, ORG_A, ScheduledTaskType.SURVEY, ScheduledTaskStatus.PENDING, at);

        assertThat(repository.findByIdAndOrganizationIdAndDeletedAtIsNull(saved.getId(), ORG_A)).isPresent();
        assertThat(repository.findByIdAndOrganizationIdAndDeletedAtIsNull(saved.getId(), ORG_B)).isEmpty();
    }

    @Test
    @DisplayName("markCreated() で status と materializedEntityId が更新される")
    void markCreated状態遷移() {
        LocalDateTime at = LocalDateTime.of(2026, 7, 1, 9, 0);
        ScheduleScheduledTaskEntity saved =
                persist(SCHEDULE_ID, ORG_A, ScheduledTaskType.SURVEY, ScheduledTaskStatus.PENDING, at);
        ScheduleScheduledTaskEntity managed = em.find(ScheduleScheduledTaskEntity.class, saved.getId());

        managed.markCreated(55001L);
        em.flush();
        em.clear();

        ScheduleScheduledTaskEntity reloaded = em.find(ScheduleScheduledTaskEntity.class, saved.getId());
        assertThat(reloaded.getStatus()).isEqualTo(ScheduledTaskStatus.CREATED);
        assertThat(reloaded.getMaterializedEntityId()).isEqualTo(55001L);
    }

    @Test
    @DisplayName("softDelete() 後は派生クエリ・@SQLRestriction で除外される")
    void 論理削除されたタスクは除外される() {
        LocalDateTime at = LocalDateTime.of(2026, 7, 1, 9, 0);
        ScheduleScheduledTaskEntity saved =
                persist(SCHEDULE_ID, ORG_A, ScheduledTaskType.SURVEY, ScheduledTaskStatus.PENDING, at);
        ScheduleScheduledTaskEntity managed = em.find(ScheduleScheduledTaskEntity.class, saved.getId());

        managed.softDelete();
        em.flush();
        em.clear();

        assertThat(repository.findByScheduleIdAndDeletedAtIsNull(SCHEDULE_ID)).isEmpty();
    }
}
