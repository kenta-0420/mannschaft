package com.mannschaft.app.organization.teamgroup;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.organization.teamgroup.repository.OrgTeamGroupRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * F01.2.1 部隊 4-A（チームグループ CRUD）の IT 共通フィクスチャ。
 *
 * <p>人物は設計書 §16 の記号に揃える: XA＝組織 ADMIN、XD＝組織 DEPUTY_ADMIN、XM＝組織 MEMBER、
 * YA＝別組織の ADMIN、N＝どの組織にも属さない、SYS＝SYSTEM_ADMIN。</p>
 */
abstract class AbstractOrgTeamGroupIT extends AbstractMySqlIntegrationTest {

    protected static final String BASE = "/api/v1/organizations/{slug}/team-groups";

    protected static final Long XA = 940401001L;
    protected static final Long XD = 940401002L;
    protected static final Long XM = 940401003L;
    protected static final Long YA = 940401004L;
    protected static final Long N = 940401005L;
    protected static final Long SYS = 940401006L;

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @Autowired
    protected OrganizationRepository organizationRepository;

    @Autowired
    protected OrgTeamGroupRepository groupRepository;

    @Autowired
    protected TeamRepository teamRepository;

    @Autowired
    protected TeamOrgMembershipRepository membershipRepository;

    @PersistenceContext
    protected EntityManager em;

    protected OrganizationEntity newOrg(boolean groupsEnabled) {
        return organizationRepository.saveAndFlush(OrganizationEntity.builder()
                .name("グループ試練組織" + SEQ.incrementAndGet())
                .slug("tg4a-" + System.nanoTime() % 1_000_000_000L + "-" + SEQ.get())
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .teamGroupsEnabled(groupsEnabled)
                .build());
    }

    protected TeamEntity newTeam() {
        return teamRepository.saveAndFlush(TeamEntity.builder()
                .slug("tg4a-t-" + System.nanoTime() % 1_000_000_000L + "-" + SEQ.incrementAndGet())
                .name("グループ試練チーム" + SEQ.get())
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(false)
                .build());
    }

    protected OrgTeamGroupEntity newGroup(Long orgId, String name, int sortOrder) {
        return groupRepository.saveAndFlush(OrgTeamGroupEntity.builder()
                .organizationId(orgId)
                .name(name)
                .sortOrder(sortOrder)
                .build());
    }

    /** 組織に加盟済み（status 指定）のチームを作り、groupId を直接差す。 */
    protected TeamOrgMembershipEntity newMembership(Long orgId, TeamOrgMembershipEntity.Status status, UUID groupId) {
        TeamEntity team = newTeam();
        return membershipRepository.saveAndFlush(TeamOrgMembershipEntity.builder()
                .teamId(team.getId())
                .organizationId(orgId)
                .status(status)
                .invitedAt(LocalDateTime.now())
                .groupId(groupId)
                .build());
    }

    /** 組織内の人物を seed する（ADMIN / DEPUTY_ADMIN は memberships と user_roles の二重 seed）。 */
    protected void seedOrgPerson(Long userId, Long orgId, String role) {
        MembershipTestHelper.insertActiveUser(em, userId);
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        if (!"MEMBER".equals(role)) {
            MembershipTestHelper.insertUserRole(em, userId, role, null, orgId);
        }
    }

    protected void seedSystemAdmin(Long userId) {
        MembershipTestHelper.insertActiveUser(em, userId);
        MembershipTestHelper.insertUserRole(em, userId, "SYSTEM_ADMIN", null, null);
    }

    protected void seedUserOnly(Long userId) {
        MembershipTestHelper.insertActiveUser(em, userId);
    }

    protected long liveGroupCount(Long orgId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM org_team_groups WHERE organization_id = :o AND deleted_at IS NULL")
                .setParameter("o", orgId).getSingleResult()).longValue();
    }

    protected long auditCount(String eventType, Long orgId, Long userId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM audit_logs WHERE event_type = :t AND organization_id = :o AND user_id = :u")
                .setParameter("t", eventType).setParameter("o", orgId).setParameter("u", userId)
                .getSingleResult()).longValue();
    }
}
