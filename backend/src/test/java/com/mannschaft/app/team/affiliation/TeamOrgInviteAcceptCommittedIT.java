package com.mannschaft.app.team.affiliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 2-C — 招待の<b>承諾が成立する</b>経路の統合テスト（コミットを伴う検証）。
 *
 * <p>承諾は加盟行をロックした後に、組織の最新のコミット済みの閉鎖の状態を組織ドメインの<b>新しい</b> tx
 * （{@code REQUIRES_NEW}・プライマリ）で読み直す（§4.5。2-D 検分4〜5巡目）。テストメソッドを {@code @Transactional} で
 * 包んでロールバックする {@link TeamOrgInviteIT} の形では、フィクスチャの組織がコミットされておらず新しい tx から見えないので、
 * 本番（組織は必ずコミット済み）と前提が食い違う。そこで承諾が成立する検証は本クラスに移し、フィクスチャはトランザクションを分けて
 * コミットし、{@link #cleanUp()} で物理削除する。拒否側（403・404・409）の検証は、読み直しの前に判定が決まるので
 * {@link TeamOrgInviteIT} に残す。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-C 招待の承諾（コミットを伴う検証）")
class TeamOrgInviteAcceptCommittedIT extends TeamOrgInviteItSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private TeamFx team;
    private OrgFx org;
    private long xa;
    private long ta;
    private long tg;

    @BeforeEach
    void setUp() {
        team = inTx(this::newTeam);
        org = inTx(this::newOrg);
        long[] users = inTx(() -> {
            seedAffiliationPermission();
            long orgAdmin = newUser();
            long teamAdmin = newUser();
            long granted = newUser();
            makeOrgAdmin(orgAdmin, org.id());
            makeTeamAdmin(teamAdmin, team.id());
            makeTeamMember(granted, team.id());
            grantAffiliationByPermissionGroup(granted, team.id(), "MEMBER");
            return new long[]{orgAdmin, teamAdmin, granted};
        });
        xa = users[0];
        ta = users[1];
        tg = users[2];
    }

    @AfterEach
    void cleanUp() {
        deleteCommittedFixtures(jdbc);
    }

    // =====================================================================
    // AC-D02・G103c・G104(ACTIVE 化) 承諾
    // =====================================================================

    @Test
    @DisplayName("AC-D02 TA が承諾すると 200 で ACTIVE になり、招待時に指定したグループに所属する（message は NULL に戻る）")
    void 承諾すると招待時のグループに所属する() throws Exception {
        GroupedOrg grouped = groupedOrgWithGroup("G1");

        MvcResult invited = invite(grouped.admin(), grouped.org().slug(), team.slug(), grouped.groupId(), "添え書き")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.teamGroup.id").value(grouped.groupId().toString()))
                .andReturn();
        long membershipId = json(invited).get("data").get("id").asLong();

        accept(ta, team.slug(), membershipId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.direction").value("ORG_INVITE"))
                .andExpect(jsonPath("$.data.teamGroup.id").value(grouped.groupId().toString()))
                .andExpect(jsonPath("$.data.teamGroup.name").value("G1"))
                .andExpect(jsonPath("$.data.respondedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.message").doesNotExist());

        Map<String, Object> row = row(membershipId);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(uuidOf(row.get("group_id"))).isEqualTo(grouped.groupId());
        assertThat(row.get("message")).as("ACTIVE になると添え書きは NULL に戻る（AC-G104）").isNull();
        assertThat(((Number) row.get("responded_by")).longValue()).as("responded_by").isEqualTo(ta);
        listReceived(ta, team.slug()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("AC-G103c 承諾すると TEAM_ORG_MEMBERSHIP_CREATED（via=ORG_INVITE）が audit_logs に1行残る")
    void 承諾の監査ログが残る() throws Exception {
        long membershipId = inviteAndGetId(xa, org.slug(), null);

        accept(ta, team.slug(), membershipId).andExpect(status().isOk());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, organization_id, metadata FROM audit_logs "
                        + "WHERE event_type = 'TEAM_ORG_MEMBERSHIP_CREATED' AND team_id = ?", team.id());
        assertThat(rows).hasSize(1);
        Map<String, Object> audit = rows.get(0);
        assertThat(((Number) audit.get("user_id")).longValue()).isEqualTo(ta);
        assertThat(((Number) audit.get("organization_id")).longValue()).isEqualTo(org.id());
        JsonNode metadata = objectMapper.readTree(String.valueOf(audit.get("metadata")));
        assertThat(metadata.get("via").asText()).isEqualTo("ORG_INVITE");
        assertThat(metadata.get("membership_id").asLong()).isEqualTo(membershipId);
    }

    @Test
    @DisplayName("AC-D08 移行前からある PENDING 招待（invited_by なし）を TA が承諾でき、ORG_INVITE として扱われる")
    void 移行前の招待を承諾できる() throws Exception {
        long legacyId = inTx(() -> insertMembershipRow(team.id(), org.id(), "PENDING", "ORG_INVITE", null,
                LocalDateTime.of(2026, 1, 10, 9, 0)));

        listReceived(ta, team.slug()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].direction").value("ORG_INVITE"))
                .andExpect(jsonPath("$.data[0].requestedBy").doesNotExist());
        accept(ta, team.slug(), legacyId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.direction").value("ORG_INVITE"));
        assertThat(row(legacyId).get("status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("AC-D11 招待で指定したグループが承諾の前に削除されたら、承諾は 200 で未分類（group_id=NULL）のまま ACTIVE")
    void 削除済みグループの招待は未分類で承諾される() throws Exception {
        GroupedOrg grouped = groupedOrgWithGroup("消えるグループ");
        long membershipId = json(invite(grouped.admin(), grouped.org().slug(), team.slug(), grouped.groupId(), null)
                .andExpect(status().isCreated()).andReturn()).get("data").get("id").asLong();

        inTx(() -> {
            softDeleteGroup(grouped.groupId());
            return null;
        });

        accept(ta, team.slug(), membershipId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.teamGroup").doesNotExist());
        assertThat(row(membershipId).get("group_id")).as("未分類で加盟が成立する").isNull();
    }

    @Test
    @DisplayName("承諾の時点でグループ機能が off なら group_id は NULL で ACTIVE になる")
    void グループ機能offなら承諾は未分類() throws Exception {
        GroupedOrg grouped = groupedOrgWithGroup("G1");
        long membershipId = json(invite(grouped.admin(), grouped.org().slug(), team.slug(), grouped.groupId(), null)
                .andExpect(status().isCreated()).andReturn()).get("data").get("id").asLong();
        // 別のリクエストで組織のグループ機能が off にされた（コミット済み）
        jdbc.update("UPDATE organizations SET team_groups_enabled = 0 WHERE id = ?", grouped.org().id());

        accept(ta, team.slug(), membershipId).andExpect(status().isOk());
        assertThat(row(membershipId).get("group_id")).isNull();
    }

    @Test
    @DisplayName("AC-P04 MEMBER 向けの権限グループで付与された TG は承諾できる")
    void 付与されたメンバーは承諾できる() throws Exception {
        long membershipId = inviteAndGetId(xa, org.slug(), null);

        listReceived(tg, team.slug()).andExpect(status().isOk());
        accept(tg, team.slug(), membershipId).andExpect(status().isOk());
    }

    // =====================================================================
    // ヘルパー
    // =====================================================================

    private record GroupedOrg(OrgFx org, long admin, UUID groupId) {
    }

    /** グループ機能 on（OPTIONAL）の組織と、その ADMIN・グループ1つをコミットする。 */
    private GroupedOrg groupedOrgWithGroup(String groupName) {
        OrgFx grouped = inTx(() -> newOrg(true, true, "OPTIONAL", "PUBLIC"));
        long admin = inTx(() -> {
            long u = newUser();
            makeOrgAdmin(u, grouped.id());
            return u;
        });
        UUID groupId = inTx(() -> newGroup(grouped.id(), groupName, false));
        return new GroupedOrg(grouped, admin, groupId);
    }

    private <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private Map<String, Object> row(long membershipId) {
        return jdbc.queryForMap(
                "SELECT status, direction, group_id, message, responded_by FROM team_org_memberships WHERE id = ?",
                membershipId);
    }

    private ResultActions invite(long actor, String orgSlug, String teamSlug, UUID groupId, String message)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamSlug", teamSlug);
        body.put("groupId", groupId == null ? null : groupId.toString());
        body.put("message", message);
        return mockMvc.perform(post("/api/v1/organizations/{slug}/team-invites", orgSlug)
                .with(user(String.valueOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long inviteAndGetId(long actor, String orgSlug, String message) throws Exception {
        MvcResult result = invite(actor, orgSlug, team.slug(), null, message)
                .andExpect(status().isCreated()).andReturn();
        return json(result).get("data").get("id").asLong();
    }

    private ResultActions listReceived(long actor, String teamSlug) throws Exception {
        return mockMvc.perform(get("/api/v1/teams/{teamSlug}/org-invites", teamSlug)
                .with(user(String.valueOf(actor))));
    }

    private ResultActions accept(long actor, String teamSlug, long membershipId) throws Exception {
        return mockMvc.perform(post("/api/v1/teams/{teamSlug}/org-invites/{id}/accept", teamSlug, membershipId)
                .with(user(String.valueOf(actor))));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
