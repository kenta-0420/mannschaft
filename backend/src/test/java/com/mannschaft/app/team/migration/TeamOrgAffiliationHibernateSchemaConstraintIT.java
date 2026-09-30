package com.mannschaft.app.team.migration;

import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.organization.teamgroup.repository.OrgTeamGroupRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionKind;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionReason;
import com.mannschaft.app.team.repository.TeamOrgAffiliationRestrictionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F01.2.1 1-A: Hibernate が Entity から生成するスキーマ（通常 IT の ddl-auto=create）で、
 * DDL の UNIQUE が効くことを確かめる。Flyway 側は {@code OrgTeamGroupsFlywayDdlTest} が見るため、
 * ここでは「Entity に制約を写し忘れると試練の DB にだけ穴が開く」ことを防ぐ。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Transactional
@DisplayName("F01.2.1 1-A Entity 由来スキーマの UNIQUE 発火")
class TeamOrgAffiliationHibernateSchemaConstraintIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private OrgTeamGroupRepository groupRepository;

    @Autowired
    private TeamOrgAffiliationRestrictionRepository restrictionRepository;

    @PersistenceContext
    private EntityManager em;

    @Test
    @DisplayName("同一組織に同名の生存グループ2件目は DataIntegrityViolationException、論理削除後は同名で作れる")
    void 同名グループの一意制約() {
        groupRepository.saveAndFlush(group(9101L, "重複名"));

        assertThatThrownBy(() -> groupRepository.saveAndFlush(group(9101L, "重複名")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("論理削除したグループと同名のグループは作成できる")
    void 論理削除後は同名を作れる() {
        OrgTeamGroupEntity first = groupRepository.saveAndFlush(group(9102L, "再利用名"));
        groupRepository.saveAndFlush(first.toBuilder().deletedAt(LocalDateTime.of(2026, 9, 1, 0, 0)).build());
        em.clear();

        OrgTeamGroupEntity second = groupRepository.saveAndFlush(group(9102L, "再利用名"));

        assertThat(second.getId()).isNotNull();
        assertThat(groupRepository.findByOrganizationIdAndDeletedAtIsNull(9102L)).hasSize(1);
    }

    @Test
    @DisplayName("制限: (organization_id, team_id, direction) の重複は DataIntegrityViolationException")
    void 制限の一意制約() {
        restrictionRepository.saveAndFlush(restriction(9201L, 9301L, TeamOrgAffiliationDirection.TEAM_APPLY));

        assertThatThrownBy(() -> restrictionRepository.saveAndFlush(
                restriction(9201L, 9301L, TeamOrgAffiliationDirection.TEAM_APPLY)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static OrgTeamGroupEntity group(long orgId, String name) {
        return OrgTeamGroupEntity.builder().organizationId(orgId).name(name).sortOrder(0).build();
    }

    private static TeamOrgAffiliationRestrictionEntity restriction(
            long orgId, long teamId, TeamOrgAffiliationDirection direction) {
        return TeamOrgAffiliationRestrictionEntity.builder()
                .organizationId(orgId)
                .teamId(teamId)
                .direction(direction)
                .kind(TeamOrgAffiliationRestrictionKind.BLOCK)
                .reason(TeamOrgAffiliationRestrictionReason.REJECTED)
                .build();
    }
}
