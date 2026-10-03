package com.mannschaft.app.social.announcement.audience;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F01.2.1 部隊 6-A（告知の宛先解決）の IT 共通フィクスチャ。
 *
 * <p>人物は設計書 §16 の記号に揃える: XA＝組織X の ADMIN、XD2＝組織X の DEPUTY_ADMIN（MANAGE_CONTENT なし）、
 * XM＝組織X の MEMBER、XO＝組織X の直属メンバー、YA＝組織Y の ADMIN、N＝どの組織にも属さない。
 * 実 MySQL＋実 Security フィルタ＋ MockMvc で、認可・Service・Repository はモックしない。</p>
 */
abstract class AbstractBroadcastAudienceIT extends AbstractMySqlIntegrationTest {

    protected static final String ORG_BROADCAST = "/api/v1/organizations/{orgId}/broadcast";
    protected static final String TEAM_BROADCAST = "/api/v1/teams/{teamId}/broadcast";
    protected static final String PREVIEW = "/api/v1/organizations/{orgId}/broadcast/audience-preview";

    protected static final Long XA = 940601001L;
    protected static final Long XD2 = 940601002L;
    protected static final Long XM = 940601003L;
    protected static final Long XO = 940601004L;
    protected static final Long YA = 940601005L;
    protected static final Long N = 940601006L;
    protected static final Long ZA = 940601007L;

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

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

    // ───────── 組織・チーム・グループ ─────────

    protected OrganizationEntity newOrg(boolean groupsEnabled) {
        return organizationRepository.saveAndFlush(OrganizationEntity.builder()
                .name("宛先試練組織" + SEQ.incrementAndGet())
                .slug("bc6a-" + System.nanoTime() % 1_000_000_000L + "-" + SEQ.get())
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .teamGroupsEnabled(groupsEnabled)
                .build());
    }

    protected TeamEntity newTeam(String name) {
        return teamRepository.saveAndFlush(TeamEntity.builder()
                .slug("bc6a-t-" + System.nanoTime() % 1_000_000_000L + "-" + SEQ.incrementAndGet())
                .name(name)
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

    protected void softDeleteGroup(UUID groupId) {
        OrgTeamGroupEntity g = groupRepository.findById(groupId).orElseThrow();
        g.softDelete(java.time.Instant.parse("2026-10-01T00:00:00Z"), XA);
        groupRepository.saveAndFlush(g);
    }

    protected void renameGroup(UUID groupId, String newName) {
        OrgTeamGroupEntity g = groupRepository.findById(groupId).orElseThrow();
        g.rename(newName, XA);
        groupRepository.saveAndFlush(g);
    }

    /** 組織に ACTIVE で加盟したチームを作る（groupId は null で未分類）。 */
    protected TeamEntity activeTeam(Long orgId, UUID groupId, String name) {
        TeamEntity team = newTeam(name);
        affiliate(team.getId(), orgId, TeamOrgMembershipEntity.Status.ACTIVE, groupId);
        return team;
    }

    protected TeamOrgMembershipEntity affiliate(Long teamId, Long orgId,
                                                TeamOrgMembershipEntity.Status status, UUID groupId) {
        return membershipRepository.saveAndFlush(TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(orgId)
                .status(status)
                .invitedAt(LocalDateTime.of(2026, 9, 1, 0, 0))
                .groupId(groupId)
                .build());
    }

    /** 大量のチームを1つの組織・グループに ACTIVE で加盟させる（上限の境界値用）。 */
    protected List<Long> activeTeams(Long orgId, UUID groupId, int count) {
        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(activeTeam(orgId, groupId, "上限チーム" + i).getId());
        }
        return ids;
    }

    // ───────── 人物 ─────────

    /** 組織内の人物を seed する（ADMIN / DEPUTY_ADMIN は memberships と user_roles の二重 seed）。 */
    protected void seedOrgPerson(Long userId, Long orgId, String role) {
        MembershipTestHelper.insertActiveUser(em, userId);
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        if (!"MEMBER".equals(role)) {
            MembershipTestHelper.insertUserRole(em, userId, role, null, orgId);
        }
    }

    protected void seedTeamPerson(Long userId, Long teamId, String role) {
        MembershipTestHelper.insertActiveUser(em, userId);
        MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        if (!"MEMBER".equals(role)) {
            MembershipTestHelper.insertUserRole(em, userId, role, teamId, null);
        }
    }

    protected void seedUserOnly(Long userId) {
        MembershipTestHelper.insertActiveUser(em, userId);
    }

    protected void flushAndClear() {
        em.flush();
        em.clear();
    }

    // ───────── リクエスト ─────────

    /** 掲示板チャネルの告知ボディに、宛先項目を足した JSON を作る。 */
    protected String bulletinBody(Map<String, Object> audience) throws Exception {
        return body("BULLETIN_THREAD", audience);
    }

    protected String body(String channel, Map<String, Object> audience) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channel", channel);
        m.put("targetRole", "MEMBERS_AND_ABOVE");
        m.put("priority", "NORMAL");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("title", "宛先試練の告知");
        content.put("body", "本文");
        m.put("content", content);
        m.putAll(audience);
        return objectMapper.writeValueAsString(m);
    }

    protected static Map<String, Object> range(UUID from, UUID to) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("fromGroupId", from == null ? null : from.toString());
        r.put("toGroupId", to == null ? null : to.toString());
        return r;
    }

    protected static List<String> ids(UUID... groupIds) {
        List<String> list = new ArrayList<>();
        for (UUID g : groupIds) {
            list.add(g.toString());
        }
        return list;
    }

    protected ResultActions broadcastToOrg(Long actor, Long orgId, String json) throws Exception {
        return mockMvc.perform(post(ORG_BROADCAST, orgId).with(user(actor.toString()))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions preview(Long actor, Long orgId, String json) throws Exception {
        return mockMvc.perform(post(PREVIEW, orgId).with(user(actor.toString()))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected long feedIdOf(ResultActions result) throws Exception {
        JsonNode root = objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
        return root.path("data").path("announcementFeedId").asLong();
    }

    // ───────── DB の観測 ─────────

    protected long feedCount(Long orgId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM announcement_feeds WHERE scope_type = 'ORGANIZATION' AND scope_id = :o")
                .setParameter("o", orgId).getSingleResult()).longValue();
    }

    protected List<Long> savedTargetTeamIds(long feedId) throws Exception {
        Object raw = em.createNativeQuery("SELECT target_team_ids FROM announcement_feeds WHERE id = :id")
                .setParameter("id", feedId).getSingleResult();
        if (raw == null) {
            return null;
        }
        return objectMapper.readValue(raw.toString(), new TypeReference<List<Long>>() { });
    }

    protected List<String> savedTargetGroupIds(long feedId) throws Exception {
        Object raw = em.createNativeQuery("SELECT target_group_ids FROM announcement_feeds WHERE id = :id")
                .setParameter("id", feedId).getSingleResult();
        if (raw == null) {
            return null;
        }
        return objectMapper.readValue(raw.toString(), new TypeReference<List<String>>() { });
    }

    protected boolean savedIncludeUnassigned(long feedId) {
        Object raw = em.createNativeQuery("SELECT include_unassigned FROM announcement_feeds WHERE id = :id")
                .setParameter("id", feedId).getSingleResult();
        if (raw instanceof Boolean b) {
            return b;
        }
        return ((Number) raw).intValue() != 0;
    }

    protected JsonNode savedTargetAudience(long feedId) throws Exception {
        Object raw = em.createNativeQuery("SELECT target_audience FROM announcement_feeds WHERE id = :id")
                .setParameter("id", feedId).getSingleResult();
        return raw == null ? null : objectMapper.readTree(raw.toString());
    }

    /** スナップショットの (group_id, team_id) を "groupId:teamId" の形で返す。 */
    @SuppressWarnings("unchecked")
    protected List<String> snapshotPairs(long feedId) {
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT group_id, team_id FROM announcement_feed_group_snapshots WHERE feed_id = :f "
                                + "ORDER BY group_id, team_id")
                .setParameter("f", feedId).getResultList();
        List<String> pairs = new ArrayList<>();
        for (Object[] r : rows) {
            pairs.add(r[0].toString() + ":" + ((Number) r[1]).longValue());
        }
        return pairs;
    }

    protected static String pair(UUID groupId, Long teamId) {
        return groupId.toString() + ":" + teamId;
    }
}
