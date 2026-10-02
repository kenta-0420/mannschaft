package com.mannschaft.app.organization.teamgroup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 4-B（グループ割当・参照 API 拡張）の IT 共通フィクスチャ。
 *
 * <p>4-A のフィクスチャ（{@link AbstractOrgTeamGroupIT}）に、割当 API の呼び出しと、加盟チームの slug・
 * 監査ログの読み出しを足したもの。人物は設計書 §16 の記号に揃える。</p>
 */
abstract class AbstractOrgTeamGroupAssignmentIT extends AbstractOrgTeamGroupIT {

    protected static final String SINGLE = "/api/v1/organizations/{slug}/teams/{teamSlug}/team-group";
    protected static final String BULK = "/api/v1/organizations/{slug}/team-group-assignments";
    protected static final String ORG_TEAMS = "/api/v1/organizations/{slug}/teams";
    protected static final String TEAM_ORGS = "/api/v1/teams/{slug}/organizations";

    /** 4-A の人物（940401xxx）と重ならない帯。 */
    protected static final Long AXA = 940402001L;
    protected static final Long AXD = 940402002L;
    protected static final Long AXM = 940402003L;
    protected static final Long AYA = 940402004L;
    protected static final Long AN = 940402005L;
    protected static final Long ASYS = 940402006L;
    /** チームのメンバー（組織には属さない）。 */
    protected static final Long ATM = 940402007L;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    /** 加盟チームの slug を返す。 */
    protected String slugOf(TeamOrgMembershipEntity membership) {
        return teamRepository.findById(membership.getTeamId()).orElseThrow().getSlug();
    }

    /** 組織に加盟済みのチームを作り、フィクスチャの slug を返す形で渡す。 */
    protected TeamOrgMembershipEntity activeTeam(Long orgId, UUID groupId) {
        return newMembership(orgId, TeamOrgMembershipEntity.Status.ACTIVE, groupId);
    }

    protected void seedTeamMember(Long userId, Long teamId) {
        MembershipTestHelper.insertActiveUser(em, userId);
        MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
    }

    /** 単体割当。groupId=null は未分類へ戻す。 */
    protected ResultActions assignOne(Long actor, String orgSlug, String teamSlug, UUID groupId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", groupId == null ? null : groupId.toString());
        return mockMvc.perform(put(SINGLE, orgSlug, teamSlug).with(user(actor.toString()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** 一括割当。 */
    protected ResultActions assignBulk(Long actor, String orgSlug, UUID groupId, List<String> teamSlugs)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", groupId == null ? null : groupId.toString());
        body.put("teamSlugs", teamSlugs);
        return mockMvc.perform(put(BULK, orgSlug).with(user(actor.toString()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    protected void expectCode(ResultActions actions, int httpStatus, String code) throws Exception {
        actions.andExpect(status().is(httpStatus)).andExpect(jsonPath("$.error.code").value(code));
    }

    /** DB 上の group_id（無ければ null）。 */
    protected UUID groupIdOf(TeamOrgMembershipEntity membership) {
        em.clear();
        return membershipRepository.findById(membership.getId()).orElseThrow().getGroupId();
    }

    /** 組織の加盟チーム一覧（GET）の本文。 */
    protected JsonNode orgTeams(Long actor, String orgSlug, String query) throws Exception {
        String path = ORG_TEAMS + (query == null ? "" : "?" + query);
        String body = mockMvc.perform(get(path, orgSlug).with(user(actor.toString())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    /** TEAM_ORG_GROUP_CHANGED の監査行（teamId, userId, organizationId, metadata JSON）。 */
    @SuppressWarnings("unchecked")
    protected List<Object[]> groupChangedAudits(Long orgId) {
        em.flush();
        return em.createNativeQuery(
                        "SELECT team_id, user_id, organization_id, metadata FROM audit_logs "
                                + "WHERE event_type = 'TEAM_ORG_GROUP_CHANGED' AND organization_id = :o ORDER BY id")
                .setParameter("o", orgId).getResultList();
    }
}
