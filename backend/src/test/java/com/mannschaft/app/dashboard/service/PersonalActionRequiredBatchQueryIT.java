package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 非空スコープの結果を保ち、未対応がない所属への実SQL増加を防ぐ。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PersonalActionRequiredBatchQueryIT extends AbstractMySqlIntegrationTest {

    private static final Long USER_ID = 8_135_401L;
    @PersistenceContext private EntityManager em;
    @Autowired private PersonalActionRequiredService service;
    @Autowired private PlatformTransactionManager txManager;
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> orgIds = new ArrayList<>();
    private final List<Long> scheduleIds = new ArrayList<>();

    @AfterEach
    void 専用コンテナの今回作成行だけを削除する() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            em.createNativeQuery("DELETE FROM schedule_attendances WHERE user_id = :uid")
                    .setParameter("uid", USER_ID).executeUpdate();
            for (Long id : scheduleIds) {
                em.createNativeQuery("DELETE FROM schedules WHERE id = :id").setParameter("id", id).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM memberships WHERE user_id = :uid")
                    .setParameter("uid", USER_ID).executeUpdate();
            for (Long id : teamIds) {
                em.createNativeQuery("DELETE FROM teams WHERE id = :id").setParameter("id", id).executeUpdate();
            }
            for (Long id : orgIds) {
                em.createNativeQuery("DELETE FROM organizations WHERE id = :id").setParameter("id", id).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM users WHERE id = :uid").setParameter("uid", USER_ID).executeUpdate();
        });
    }

    @Test
    void 非空2scopeを保ち空所属が42さらに417へ増えてもSQL件数を増やさない() {
        // 共通Poolの独立接続にも見えるよう、fixtureだけ先にcommitする。
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            MembershipTestHelper.insertActiveUser(em, USER_ID);
            addScopes(1, 1);
            addPendingAttendance(teamIds.get(0), null);
            addPendingAttendance(null, orgIds.get(0));
        });
        long two = measure();
        new TransactionTemplate(txManager).executeWithoutResult(status -> addScopes(20, 20));
        long fortyTwo = measure();
        System.out.printf("action-required SQL scopes=2,42 statements=%d,%d%n", two, fortyTwo);
        assertThat(fortyTwo).as("未対応2件を保持した2→42scope SQL: %s→%s", two, fortyTwo)
                .isLessThanOrEqualTo(two);
        new TransactionTemplate(txManager).executeWithoutResult(status -> addScopes(267, 108));
        long actual = measure();
        System.out.printf("action-required SQL scopes=417 statements=%d%n", actual);
        assertThat(actual).as("未対応2件を保持した2→417scope SQL: %s→%s", two, actual)
                .isLessThanOrEqualTo(two);
    }

    private long measure() {
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long before = statistics.getPrepareStatementCount();
            var response = service.getPersonalActionRequired(USER_ID);
            assertThat(response.totalCount()).isEqualTo(2);
            assertThat(response.items()).extracting(item -> item.itemType()).containsExactly("ATTENDANCE", "ATTENDANCE");
            assertThat(response.items()).extracting(item -> item.scopeType()).containsExactly("TEAM", "ORGANIZATION");
            assertThat(response.items()).extracting(item -> item.itemId())
                    .containsExactly(String.valueOf(scheduleIds.get(0)), String.valueOf(scheduleIds.get(1)));
            return statistics.getPrepareStatementCount() - before;
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }

    private void addScopes(int teams, int organizations) {
        for (int i = 0; i < teams; i++) {
            TeamEntity team = TeamEntity.builder().slug("action-batch-" + UUID.randomUUID().toString().substring(0, 12))
                    .name("計数チーム").visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build();
            em.persist(team);
            em.flush();
            teamIds.add(team.getId());
            MembershipTestHelper.insertMembership(em, USER_ID, ScopeType.TEAM, team.getId(), RoleKind.MEMBER);
        }
        for (int i = 0; i < organizations; i++) {
            OrganizationEntity org = OrganizationEntity.builder().slug("action-batch-" + UUID.randomUUID().toString().substring(0, 12))
                    .name("計数組織").orgType(OrganizationEntity.OrgType.OTHER)
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build();
            em.persist(org);
            em.flush();
            orgIds.add(org.getId());
            MembershipTestHelper.insertMembership(em, USER_ID, ScopeType.ORGANIZATION, org.getId(), RoleKind.MEMBER);
        }
    }

    private void addPendingAttendance(Long teamId, Long orgId) {
        ScheduleEntity schedule = ScheduleEntity.builder().teamId(teamId).organizationId(orgId)
                .title("未回答の計数予定").startAt(LocalDateTime.now().plusDays(30)).eventType(EventType.EVENT)
                .visibility(ScheduleVisibility.MEMBERS_ONLY).minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED).createdBy(USER_ID).attendanceRequired(true).build();
        em.persist(schedule);
        em.flush();
        scheduleIds.add(schedule.getId());
        em.persist(ScheduleAttendanceEntity.builder().scheduleId(schedule.getId()).userId(USER_ID)
                .status(AttendanceStatus.UNDECIDED).build());
    }
}
