package com.mannschaft.app.team.affiliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.visibility.ScopeAncestorResolver;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.support.test.MembershipTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 2-D — 離脱（§6.6 のチーム側）と除名（§6.6 の組織側）の API の統合テスト（試練）。
 *
 * <p>実 MySQL（Testcontainers）・実 Security フィルタ・MockMvc で実 API を叩く。認可・Service・Repository は
 * モックしない。{@code @Transactional} でテストごとにロールバックする。通知の配信（Worker）は
 * コミットを要するため {@link TeamOrgLeaveRemoveCommittedIT} で確かめる。</p>
 *
 * <p>人物は設計書 §16 の記号に揃える: TA＝チーム ADMIN、TG＝権限グループで加盟操作権限を付与された MEMBER、
 * TD＝チーム DEPUTY_ADMIN（付与なし）、TM＝チーム MEMBER（付与なし）、XA＝組織X の ADMIN、XD＝組織X の DEPUTY_ADMIN、
 * XM＝組織X の MEMBER、YA＝組織Y の ADMIN。チームT は組織X・Y の両方に加盟している。</p>
 *
 * <ul>
 *   <li>AC-E01・E02・E03・G103j・G103k・P08・P02（API）</li>
 * </ul>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-D 離脱・除名")
class TeamOrgLeaveRemoveIT extends TeamAffiliationItSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScopeAncestorResolver scopeAncestorResolver;

    @Autowired
    private ScheduleRepository scheduleRepository;

    private TeamFx team;
    private OrgFx orgX;
    private OrgFx orgY;
    private UUID groupA;
    private long ta;
    private long tg;
    private long tm;
    private long td;
    private long xa;
    private long xd;
    private long xm;
    private long ya;
    private long yMember;
    private long idX;
    private long idY;

    @BeforeEach
    void setUp() {
        seedAffiliationPermission();
        team = newTeam();
        orgX = newOrg(true, true, "OPTIONAL", "PUBLIC");
        orgY = newOrg(true, false, "OFF", "PUBLIC");
        groupA = newGroup(orgX.id(), "グループA", false);
        ta = newUser();
        tg = newUser();
        tm = newUser();
        td = newUser();
        xa = newUser();
        xd = newUser();
        xm = newUser();
        ya = newUser();
        yMember = newUser();
        makeTeamAdmin(ta, team.id());
        makeTeamMember(tg, team.id());
        grantAffiliationByPermissionGroup(tg, team.id(), "MEMBER");
        makeTeamMember(tm, team.id());
        makeTeamDeputy(td, team.id());
        makeOrgAdmin(xa, orgX.id());
        makeOrgDeputy(xd, orgX.id());
        makeOrgMember(xm, orgX.id());
        makeOrgAdmin(ya, orgY.id());
        makeOrgMember(yMember, orgY.id());
        // T は X（グループA に所属）と Y（未分類）に ACTIVE で加盟している
        idX = insertMembershipRow(team.id(), orgX.id(), "ACTIVE", "TEAM_APPLY", groupA,
                LocalDateTime.now().minusDays(10));
        idY = insertMembershipRow(team.id(), orgY.id(), "ACTIVE", "ORG_INVITE", null,
                LocalDateTime.now().minusDays(10));
        em.flush();
        em.clear();
    }

    // =====================================================================
    // AC-E01 離脱
    // =====================================================================

    @Test
    @DisplayName("AC-E01 離脱すると 204 で、X の告知は出なくなり、Y の告知は出続ける（T の ORGANIZATION_WIDE の閲覧で確かめる）")
    void 離脱するとXの閲覧権は消えYは残る() throws Exception {
        long organizationWide = saveOrganizationWideSchedule(team.id(), ta);
        readSchedule(xm, organizationWide).andExpect(status().isOk());
        readSchedule(yMember, organizationWide).andExpect(status().isOk());

        leave(ta, team.slug(), orgX.slug())
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        em.flush();
        em.clear();

        assertThat(countMemberships(team.id(), orgX.id())).as("X との加盟行は物理削除される").isZero();
        assertThat(statusOf(idY)).as("Y との加盟は影響を受けない").isEqualTo("ACTIVE");
        readSchedule(xm, organizationWide).andExpect(status().isForbidden());
        readSchedule(yMember, organizationWide).andExpect(status().isOk());
        Set<Long> parents = scopeAncestorResolver
                .resolveParentOrgIds(Set.of(new ScopeKey("TEAM", team.id())))
                .get(new ScopeKey("TEAM", team.id()));
        assertThat(parents).containsExactly(orgY.id());

        MvcResult orgs = mockMvc.perform(get("/api/v1/teams/{slug}/organizations", team.slug())
                        .with(user(String.valueOf(ta))))
                .andExpect(status().isOk()).andReturn();
        List<String> slugs = new ArrayList<>();
        json(orgs).get("data").forEach(n -> slugs.add(n.get("slug").asText()));
        assertThat(slugs).containsExactly(orgY.slug());
    }

    @Test
    @DisplayName("AC-E01・P02 MANAGE_ORG_AFFILIATION を付与された TG も離脱できる（204）")
    void 付与されたメンバーも離脱できる() throws Exception {
        leave(tg, team.slug(), orgX.slug()).andExpect(status().isNoContent());
        em.flush();
        em.clear();
        assertThat(countMemberships(team.id(), orgX.id())).isZero();
        assertThat(statusOf(idY)).isEqualTo("ACTIVE");
    }

    // =====================================================================
    // AC-E02 除名と再加盟
    // =====================================================================

    @Test
    @DisplayName("AC-E02 除名すると 204 で、グループの所属も一緒に消える。Y との加盟は影響を受けない")
    void 除名する() throws Exception {
        long organizationWide = saveOrganizationWideSchedule(team.id(), ta);
        readSchedule(xm, organizationWide).andExpect(status().isOk());

        remove(xa, orgX.slug(), team.slug())
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        em.flush();
        em.clear();

        assertThat(countMemberships(team.id(), orgX.id())).isZero();
        assertThat(statusOf(idY)).isEqualTo("ACTIVE");
        readSchedule(xm, organizationWide).andExpect(status().isForbidden());
        readSchedule(yMember, organizationWide).andExpect(status().isOk());
        MvcResult teams = mockMvc.perform(get("/api/v1/organizations/{slug}/teams", orgX.slug())
                        .with(user(String.valueOf(xa))))
                .andExpect(status().isOk()).andReturn();
        List<String> slugs = new ArrayList<>();
        json(teams).get("data").forEach(n -> slugs.add(n.get("slug").asText()));
        assertThat(slugs).as("除名したチームは X の加盟チーム一覧に出ない").doesNotContain(team.slug());
    }

    @Test
    @DisplayName("AC-E02 除名の後に再加盟（申請→承認）したときは、以前のグループを引き継がず未分類から始まる")
    void 再加盟は未分類から始まる() throws Exception {
        remove(xa, orgX.slug(), team.slug()).andExpect(status().isNoContent());
        em.flush();
        em.clear();

        MvcResult applied = mockMvc.perform(post("/api/v1/teams/{teamSlug}/org-applications", team.slug())
                        .with(user(String.valueOf(ta)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("organizationSlug", orgX.slug()))))
                .andExpect(status().isCreated()).andReturn();
        long newId = json(applied).get("data").get("id").asLong();
        assertThat(newId).as("新しい加盟行（以前の行の再利用ではない）").isNotEqualTo(idX);

        mockMvc.perform(post("/api/v1/organizations/{slug}/team-applications/{id}/approve", orgX.slug(), newId)
                        .with(user(String.valueOf(xa)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrideGroup\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.teamGroup").doesNotExist());
        assertThat(groupIdOf(newId)).isNull();
    }

    // =====================================================================
    // AC-E03 未加盟の組み合わせ
    // =====================================================================

    @Test
    @DisplayName("AC-E03 未加盟の組み合わせで離脱・除名すると 404 TEAM_070。既存の加盟には触れない")
    void 未加盟の組み合わせは404() throws Exception {
        OrgFx orgZ = newOrg();
        long za = newUser();
        makeOrgAdmin(za, orgZ.id());
        TeamFx team2 = newTeam();
        long ta2 = newUser();
        makeTeamAdmin(ta2, team2.id());
        em.flush();
        em.clear();

        // チームT は Z に未加盟
        leave(ta, team.slug(), orgZ.slug())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TEAM_070"));
        remove(za, orgZ.slug(), team.slug())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TEAM_070"));
        // チーム2 はどの組織にも未加盟（X の ADMIN・チーム2 の ADMIN が、それぞれ自分の側から操作する）
        leave(ta2, team2.slug(), orgX.slug())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TEAM_070"));
        remove(xa, orgX.slug(), team2.slug())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TEAM_070"));

        em.flush();
        em.clear();
        assertThat(statusOf(idX)).isEqualTo("ACTIVE");
        assertThat(statusOf(idY)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("AC-E03 離脱・除名を2回続けると、2回目は 404 TEAM_070")
    void 二重の離脱と除名は404() throws Exception {
        leave(ta, team.slug(), orgX.slug()).andExpect(status().isNoContent());
        leave(ta, team.slug(), orgX.slug())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TEAM_070"));

        remove(ya, orgY.slug(), team.slug()).andExpect(status().isNoContent());
        remove(ya, orgY.slug(), team.slug())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TEAM_070"));
    }

    // =====================================================================
    // AC-P02・P08 認可
    // =====================================================================

    @Test
    @DisplayName("AC-P02 離脱は、付与のない TD・TM と、組織 X の ADMIN・他組織の ADMIN・無関係の利用者は 403。行は残る")
    void 離脱の認可() throws Exception {
        long stranger = newUser();
        em.flush();
        em.clear();

        for (long actor : List.of(td, tm, xa, ya, stranger)) {
            leave(actor, team.slug(), orgX.slug()).andExpect(status().isForbidden());
        }
        em.flush();
        em.clear();
        assertThat(statusOf(idX)).isEqualTo("ACTIVE");
        assertThat(statusOf(idY)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("AC-P08 除名は、チーム側で付与されていても組織 ADMIN 以外（XD・XM・TA・TG・他組織の YA・無関係の利用者）は 403。行は残る")
    void 除名の認可() throws Exception {
        long stranger = newUser();
        em.flush();
        em.clear();

        for (long actor : List.of(xd, xm, ta, tg, ya, stranger)) {
            remove(actor, orgX.slug(), team.slug()).andExpect(status().isForbidden());
        }
        em.flush();
        em.clear();
        assertThat(statusOf(idX)).isEqualTo("ACTIVE");
        assertThat(statusOf(idY)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("AC-P08 他組織の ADMIN（YA）が、自組織 Y の slug を使って組織Xとの加盟を消すことはできない（Y の加盟だけが対象）")
    void 別組織のslugでは別組織の加盟しか消えない() throws Exception {
        remove(ya, orgY.slug(), team.slug()).andExpect(status().isNoContent());
        em.flush();
        em.clear();

        assertThat(countMemberships(team.id(), orgY.id())).isZero();
        assertThat(statusOf(idX)).as("X との加盟は消えない").isEqualTo("ACTIVE");
    }

    // =====================================================================
    // AC-G103j・G103k 監査ログ
    // =====================================================================

    @Test
    @DisplayName("AC-G103j 離脱すると TEAM_ORG_MEMBERSHIP_REMOVED が1行残り、reason=TEAM_LEFT・加盟 ID・操作者が入る")
    void 離脱の監査ログ() throws Exception {
        leave(ta, team.slug(), orgX.slug()).andExpect(status().isNoContent());
        em.flush();

        List<?> rows = auditRows("TEAM_ORG_MEMBERSHIP_REMOVED");
        assertThat(rows).hasSize(1);
        Object[] audit = (Object[]) rows.get(0);
        assertThat(((Number) audit[0]).longValue()).as("操作者").isEqualTo(ta);
        assertThat(((Number) audit[1]).longValue()).as("チームコンテキスト").isEqualTo(team.id());
        assertThat(((Number) audit[2]).longValue()).as("組織コンテキスト").isEqualTo(orgX.id());
        JsonNode metadata = objectMapper.readTree(String.valueOf(audit[3]));
        assertThat(metadata.get("membership_id").asLong()).isEqualTo(idX);
        assertThat(metadata.get("reason").asText()).isEqualTo("TEAM_LEFT");
    }

    @Test
    @DisplayName("AC-G103k 除名すると TEAM_ORG_MEMBERSHIP_REMOVED が1行残り、reason=ORG_REMOVED・加盟 ID・操作者が入る")
    void 除名の監査ログ() throws Exception {
        remove(xa, orgX.slug(), team.slug()).andExpect(status().isNoContent());
        em.flush();

        List<?> rows = auditRows("TEAM_ORG_MEMBERSHIP_REMOVED");
        assertThat(rows).hasSize(1);
        Object[] audit = (Object[]) rows.get(0);
        assertThat(((Number) audit[0]).longValue()).as("操作者").isEqualTo(xa);
        assertThat(((Number) audit[1]).longValue()).isEqualTo(team.id());
        assertThat(((Number) audit[2]).longValue()).isEqualTo(orgX.id());
        JsonNode metadata = objectMapper.readTree(String.valueOf(audit[3]));
        assertThat(metadata.get("membership_id").asLong()).isEqualTo(idX);
        assertThat(metadata.get("reason").asText()).isEqualTo("ORG_REMOVED");
    }

    // =====================================================================
    // ヘルパー
    // =====================================================================

    private ResultActions leave(long actor, String teamSlug, String orgSlug) throws Exception {
        return mockMvc.perform(delete("/api/v1/teams/{teamSlug}/organizations/{orgSlug}", teamSlug, orgSlug)
                .with(user(String.valueOf(actor))));
    }

    private ResultActions remove(long actor, String orgSlug, String teamSlug) throws Exception {
        return mockMvc.perform(delete("/api/v1/organizations/{slug}/teams/{teamSlug}", orgSlug, teamSlug)
                .with(user(String.valueOf(actor))));
    }

    private void makeOrgDeputy(long userId, long orgId) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, userId, "DEPUTY_ADMIN", null, orgId);
    }

    private void makeOrgMember(long userId, long orgId) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
    }

    /** チーム T の ORGANIZATION_WIDE（予定では visibility=ORGANIZATION）の予定を作る。 */
    private long saveOrganizationWideSchedule(long teamId, long authorId) {
        long scheduleId = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamId)
                .title("離脱・除名の前後で見え方が変わる組織公開予定")
                .startAt(LocalDateTime.of(2026, 11, 10, 10, 0))
                .endAt(LocalDateTime.of(2026, 11, 10, 12, 0))
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.ORGANIZATION)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .createdBy(authorId)
                .build()).getId();
        em.flush();
        em.clear();
        return scheduleId;
    }

    /** 実際の閲覧 API（実 Security・実認可）で T の予定を読む。 */
    private ResultActions readSchedule(long viewer, long scheduleId) throws Exception {
        em.flush();
        em.clear();
        return mockMvc.perform(get("/api/v1/teams/{slug}/schedules/{id}", team.slug(), scheduleId)
                .with(user(String.valueOf(viewer))));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private UUID groupIdOf(long id) {
        Object raw = em.createNativeQuery("SELECT BIN_TO_UUID(group_id) FROM team_org_memberships WHERE id = :id")
                .setParameter("id", id).getSingleResult();
        return raw == null ? null : UUID.fromString(String.valueOf(raw));
    }

    private String statusOf(long id) {
        List<?> rows = em.createNativeQuery("SELECT status FROM team_org_memberships WHERE id = :id")
                .setParameter("id", id).getResultList();
        return rows.isEmpty() ? null : String.valueOf(rows.get(0));
    }

    /** user_id, team_id, organization_id, metadata。 */
    private List<?> auditRows(String eventType) {
        return em.createNativeQuery(
                        "SELECT user_id, team_id, organization_id, metadata FROM audit_logs "
                                + "WHERE event_type = :type AND team_id = :teamId")
                .setParameter("type", eventType)
                .setParameter("teamId", team.id())
                .getResultList();
    }
}
