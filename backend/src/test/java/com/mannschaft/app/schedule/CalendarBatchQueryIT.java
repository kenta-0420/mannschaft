package com.mannschaft.app.schedule;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.schedule.service.ScheduleQueryService;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 予定が空でも所属数に比例していた横断カレンダーの実SQL件数を検証する。 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class CalendarBatchQueryIT extends AbstractMySqlIntegrationTest {

    @PersistenceContext private EntityManager em;
    @Autowired private ScheduleQueryService service;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private OrganizationRepository organizationRepository;

    private static final Long USER_ID = 8_135_300L;
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime TO = FROM.plusMonths(1);

    @Test
    void 所属が1から21さらに417へ増えても空カレンダーのSQL件数を増やさない() {
        MembershipTestHelper.insertActiveUser(em, USER_ID);
        addScopes(1, 1);
        long one = measureEmptyCalendar();
        addScopes(20, 20);
        long twentyOne = measureEmptyCalendar();
        addScopes(267, 108);
        long actualSize = measureEmptyCalendar();
        assertThat(twentyOne).as("1+1 → 21+21 scope のSQL: %s → %s", one, twentyOne)
                .isLessThanOrEqualTo(one);
        assertThat(actualSize).as("1+1 → 288+129 scope のSQL: %s → %s", one, actualSize)
                .isLessThanOrEqualTo(one);
    }

    @Test
    void 継続予定と日時境界と取消状態と論理削除の既存抽出契約を維持する() {
        long team = 9_135_301L;
        long organization = 9_135_302L;
        ScheduleEntity spanning = saveSchedule(team, organization, FROM.minusDays(1), FROM.plusHours(1));
        saveSchedule(team, organization, FROM.minusHours(1), FROM);
        ScheduleEntity atFrom = saveSchedule(team, organization, FROM, null);
        saveSchedule(team, organization, FROM.minusHours(1), null);
        ScheduleEntity cancelled = saveSchedule(team, organization, FROM.plusDays(1), FROM.plusDays(2));
        cancelled.cancel();
        ScheduleEntity atTo = saveSchedule(team, organization, TO, null);
        saveSchedule(team, organization, TO.plusSeconds(1), TO.plusHours(1));
        ScheduleEntity deleted = saveSchedule(team, organization, FROM.plusHours(1), FROM.plusHours(2));
        deleted.softDelete();
        em.flush();
        em.clear();
        assertThat(scheduleRepository.findCalendarByTeamId(team, FROM, TO))
                .extracting(ScheduleEntity::getId)
                .containsExactly(spanning.getId(), atFrom.getId(), cancelled.getId(), atTo.getId());
        assertThat(scheduleRepository.findCalendarByOrganizationId(organization, FROM, TO))
                .extracting(ScheduleEntity::getId)
                .containsExactly(spanning.getId(), atFrom.getId(), cancelled.getId(), atTo.getId());
    }

    private ScheduleEntity saveSchedule(long team, long organization, LocalDateTime start, LocalDateTime end) {
        return scheduleRepository.save(ScheduleEntity.builder().teamId(team).organizationId(organization)
                .title("日時境界").startAt(start).endAt(end).eventType(EventType.EVENT)
                .visibility(ScheduleVisibility.MEMBERS_ONLY).minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED).createdBy(USER_ID).build());
    }
    private void addScopes(int teams, int organizations) {
        for (int i = 0; i < teams; i++) {
            TeamEntity team = teamRepository.save(TeamEntity.builder()
                    .slug("calendar-batch-" + UUID.randomUUID().toString().substring(0, 12)).name("計数チーム").build());
            MembershipTestHelper.insertMembership(em, USER_ID, ScopeType.TEAM, team.getId(), RoleKind.MEMBER);
        }
        for (int i = 0; i < organizations; i++) {
            OrganizationEntity organization = organizationRepository.save(OrganizationEntity.builder()
                    .slug("calendar-batch-" + UUID.randomUUID().toString().substring(0, 12)).name("計数組織")
                    .orgType(OrganizationEntity.OrgType.OTHER)
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).build());
            MembershipTestHelper.insertMembership(em, USER_ID, ScopeType.ORGANIZATION,
                    organization.getId(), RoleKind.MEMBER);
        }
    }

    private long measureEmptyCalendar() {
        em.flush();
        em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long before = statistics.getPrepareStatementCount();
            assertThat(service.getMyCalendar(USER_ID, FROM, TO)).isEmpty();
            return statistics.getPrepareStatementCount() - before;
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }
}
