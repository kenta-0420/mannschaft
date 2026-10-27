package com.mannschaft.app.schedule;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.schedule.service.ScheduleQueryService;
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

    private void addScopes(int teams, int organizations) {
        for (int i = 0; i < teams; i++) {
            TeamEntity team = teamRepository.save(TeamEntity.builder()
                    .slug("calendar-batch-" + UUID.randomUUID()).name("計数チーム").build());
            MembershipTestHelper.insertMembership(em, USER_ID, ScopeType.TEAM, team.getId(), RoleKind.MEMBER);
        }
        for (int i = 0; i < organizations; i++) {
            OrganizationEntity organization = organizationRepository.save(OrganizationEntity.builder()
                    .slug("calendar-batch-" + UUID.randomUUID()).name("計数組織")
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
