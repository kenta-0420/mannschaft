package com.mannschaft.app.organization.teamgroup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 4-A — チームグループ CRUD・並び替え・件数の IT（試練）。
 *
 * <p>実 MySQL（Testcontainers）＋実 Security フィルタ＋ MockMvc。認可・Service・Repository はモックしない。
 * テストはテストトランザクション内で動くため AFTER_COMMIT のリスナーは発火しない
 * （F08 の「リスナー処理前」の状態をそのまま観測できる。リスナー処理後は
 * {@link OrgTeamGroupAsyncAndConcurrencyIT} が実コミットで確かめる）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 4-A チームグループ CRUD・並び替え・件数")
class OrgTeamGroupCrudIT extends AbstractOrgTeamGroupIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private OrganizationEntity org;
    private String slug;

    @BeforeEach
    void setUp() {
        org = newOrg(true);
        slug = org.getSlug();
        seedOrgPerson(XA, org.getId(), "ADMIN");
        em.flush();
        em.clear();
    }

    // ───────── AC-F01 作成と末尾追加 ─────────

    @Test
    @DisplayName("AC-F01: 作成は 201 で、並び順の末尾（0, 1, 2…）に入り、一覧に反映される")
    void create_appendsToEnd() throws Exception {
        String a = createGroup("平成20年度卒", "説明");
        String b = createGroup("平成21年度卒", null);

        mockMvc.perform(get(BASE, slug).with(user(XA.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(a))
                .andExpect(jsonPath("$.data[0].name").value("平成20年度卒"))
                .andExpect(jsonPath("$.data[0].description").value("説明"))
                .andExpect(jsonPath("$.data[0].sortOrder").value(0))
                .andExpect(jsonPath("$.data[0].teamCount").value(0))
                .andExpect(jsonPath("$.data[1].id").value(b))
                .andExpect(jsonPath("$.data[1].sortOrder").value(1))
                .andExpect(jsonPath("$.meta.limit").value(100))
                .andExpect(jsonPath("$.meta.unassignedTeamCount").value(0));
    }

    @Test
    @DisplayName("AC-F01: 作成の応答本文は 201 で id・name・sortOrder を返す")
    void create_returns201WithBody() throws Exception {
        mockMvc.perform(post(BASE, slug).with(user(XA.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A組\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.name").value("A組"))
                .andExpect(jsonPath("$.data.sortOrder").value(0))
                .andExpect(jsonPath("$.data.teamCount").value(0));
    }

    // ───────── AC-F02 同名 ─────────

    @Test
    @DisplayName("AC-F02: 生存グループと同名なら 409 ORG_065。削除済みと同名なら作成できる")
    void duplicateName_conflictsOnlyWithLive() throws Exception {
        String id = createGroup("重複名", null);

        post409(BASE, "{\"name\":\"重複名\"}", "ORG_065");
        // 照合順序 utf8mb4_0900_ai_ci により大文字小文字は同一視される
        createGroup("Case", null);
        post409(BASE, "{\"name\":\"case\"}", "ORG_065");

        mockMvc.perform(delete(BASE + "/{id}", slug, id).with(user(XA.toString())))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(BASE, slug).with(user(XA.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"重複名\"}"))
                .andExpect(status().isCreated());
    }

    // ───────── AC-F03 上限 100 件（マスター裁可） ─────────

    @Test
    @DisplayName("AC-F03: 100件目は成功し、101件目は 422 ORG_066（削除済みは数えない）")
    void limit_is100LiveGroups() throws Exception {
        for (int i = 0; i < 98; i++) {
            newGroup(org.getId(), "既存" + i, i);
        }
        OrgTeamGroupEntity deleted = newGroup(org.getId(), "削除済み", 98);
        em.createNativeQuery("UPDATE org_team_groups SET deleted_at = UTC_TIMESTAMP() WHERE id = :id")
                .setParameter("id", deleted.getId()).executeUpdate();
        newGroup(org.getId(), "既存98", 99);
        em.flush();
        em.clear();
        assertThat(liveGroupCount(org.getId())).isEqualTo(99);

        mockMvc.perform(post(BASE, slug).with(user(XA.toString()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"100件目\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE, slug).with(user(XA.toString()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"101件目\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORG_066"));
        assertThat(liveGroupCount(org.getId())).isEqualTo(100);
    }

    // ───────── AC-F04 / G111 機能 off ─────────

    @Test
    @DisplayName("AC-F04/G111: グループ機能 off の組織では、一覧・作成・変更・削除・並び替えがすべて 409 ORG_067")
    void featureOff_everyEndpointConflicts() throws Exception {
        OrganizationEntity off = newOrg(false);
        seedOrgPerson(XD, off.getId(), "ADMIN");
        OrgTeamGroupEntity g = newGroup(off.getId(), "残っているグループ", 0);
        em.flush();
        em.clear();
        String offSlug = off.getSlug();

        expectCode(mockMvc.perform(get(BASE, offSlug).with(user(XD.toString()))), 409, "ORG_067");
        expectCode(mockMvc.perform(post(BASE, offSlug).with(user(XD.toString()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")), 409, "ORG_067");
        expectCode(mockMvc.perform(patch(BASE + "/{id}", offSlug, g.getId()).with(user(XD.toString()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"y\"}")), 409, "ORG_067");
        expectCode(mockMvc.perform(delete(BASE + "/{id}", offSlug, g.getId()).with(user(XD.toString()))), 409, "ORG_067");
        expectCode(mockMvc.perform(put(BASE + "/order", offSlug).with(user(XD.toString()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupIds\":[\"" + g.getId() + "\"]}")), 409, "ORG_067");

        // 何も変わっていない
        assertThat(liveGroupCount(off.getId())).isEqualTo(1);
    }

    // ───────── AC-F07 並び替えの ID 集合不一致 ─────────

    @Test
    @DisplayName("AC-F07: 並び替えの ID 集合が生存グループと一致しなければ 409 ORG_068 で、順序は変わらない")
    void reorder_setMismatch_conflicts() throws Exception {
        String a = createGroup("A", null);
        String b = createGroup("B", null);
        String c = createGroup("C", null);
        String deleted = createGroup("D", null);
        mockMvc.perform(delete(BASE + "/{id}", slug, deleted).with(user(XA.toString())))
                .andExpect(status().isNoContent());

        reorder409(List.of(a, b));                              // 不足
        reorder409(List.of(a, b, c, UUID.randomUUID().toString())); // 余分（存在しない）
        reorder409(List.of(a, b, c, deleted));                  // 削除済みを含む
        reorder409(List.of(a, a, b, c));                        // 重複
        reorder409(List.of());                                  // 空（生存グループが残っている）

        assertOrder(a, b, c);
    }

    // ───────── AC-F08 削除（リスナー処理前） ─────────

    @Test
    @DisplayName("AC-F08: 削除すると 204。リスナーの処理前でも、所属チームは未分類として数えられ、グループは一覧から消える")
    void delete_beforeListener_teamsShownAsUnassigned() throws Exception {
        String gid = createGroup("削除対象", null);
        UUID groupId = UUID.fromString(gid);
        newMembership(org.getId(), TeamOrgMembershipEntity.Status.ACTIVE, groupId);
        newMembership(org.getId(), TeamOrgMembershipEntity.Status.ACTIVE, groupId);
        em.flush();
        em.clear();

        mockMvc.perform(get(BASE, slug).with(user(XA.toString())))
                .andExpect(jsonPath("$.data[0].teamCount").value(2))
                .andExpect(jsonPath("$.meta.unassignedTeamCount").value(0));

        mockMvc.perform(delete(BASE + "/{id}", slug, gid).with(user(XA.toString())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE, slug).with(user(XA.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)))
                .andExpect(jsonPath("$.meta.unassignedTeamCount").value(2));

        // テストトランザクションはコミットされないため AFTER_COMMIT リスナーは未発火＝処理前の状態。
        // DB の group_id はまだ残っているが、読み手は削除済みグループを指す行を未分類として扱う。
        Number remaining = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM team_org_memberships WHERE organization_id = :o AND group_id IS NOT NULL")
                .setParameter("o", org.getId()).getSingleResult();
        assertThat(remaining.longValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-F08: 存在しない・削除済みのグループを削除すると 404 ORG_064")
    void delete_unknownOrDeleted_notFound() throws Exception {
        String gid = createGroup("一度だけ", null);
        mockMvc.perform(delete(BASE + "/{id}", slug, gid).with(user(XA.toString()))).andExpect(status().isNoContent());
        expectCode(mockMvc.perform(delete(BASE + "/{id}", slug, gid).with(user(XA.toString()))), 404, "ORG_064");
        expectCode(mockMvc.perform(delete(BASE + "/{id}", slug, UUID.randomUUID()).with(user(XA.toString()))), 404, "ORG_064");
    }

    // ───────── AC-F09 off → on で元に戻る ─────────

    @Test
    @DisplayName("AC-F09: グループ機能を off にしてもグループと割当は消えず、on に戻すと元に戻る")
    void toggleOffThenOn_restoresGroupsAndAssignments() throws Exception {
        String gid = createGroup("残るグループ", null);
        TeamOrgMembershipEntity m = newMembership(org.getId(), TeamOrgMembershipEntity.Status.ACTIVE, UUID.fromString(gid));
        em.flush();
        em.clear();

        setGroupsEnabled(false);
        expectCode(mockMvc.perform(get(BASE, slug).with(user(XA.toString()))), 409, "ORG_067");

        setGroupsEnabled(true);
        mockMvc.perform(get(BASE, slug).with(user(XA.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(gid))
                .andExpect(jsonPath("$.data[0].teamCount").value(1));
        Object groupId = em.createNativeQuery("SELECT group_id FROM team_org_memberships WHERE id = :id")
                .setParameter("id", m.getId()).getSingleResult();
        assertThat(groupId).isNotNull();
    }

    // ───────── AC-G113 入力検証・改名 ─────────

    @Test
    @DisplayName("AC-G113: 名前は前後の空白を除いて 1〜50 コードポイント。51 や空白だけは 400、説明は 200 文字まで")
    void nameAndDescriptionValidation() throws Exception {
        post400("{\"name\":\"" + "あ".repeat(51) + "\"}");
        post400("{\"name\":\"   \"}");
        post400("{\"name\":\"\"}");
        post400("{}");
        post400("{\"name\":\"ok\",\"description\":\"" + "い".repeat(201) + "\"}");

        // 50 コードポイント（サロゲートペア 50 個 = UTF-16 で 100 単位）は通る
        String emoji50 = "😀".repeat(50);
        mockMvc.perform(post(BASE, slug).with(user(XA.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + emoji50 + "\",\"description\":\"" + "い".repeat(200) + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value(emoji50));

        // 前後の空白は除いて保存される
        mockMvc.perform(post(BASE, slug).with(user(XA.toString()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  余白あり  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("余白あり"));
    }

    @Test
    @DisplayName("AC-G113: 改名で生存グループと重複すると 409 ORG_065。自分自身と同じ名前への変更は成功する")
    void rename_conflictsOnlyWithOtherLiveGroup() throws Exception {
        String a = createGroup("A", null);
        createGroup("B", null);

        expectCode(mockMvc.perform(patch(BASE + "/{id}", slug, a).with(user(XA.toString()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"B\"}")), 409, "ORG_065");

        mockMvc.perform(patch(BASE + "/{id}", slug, a).with(user(XA.toString()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\",\"description\":\"追記\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("A"))
                .andExpect(jsonPath("$.data.description").value("追記"));

        // 部分更新: name を送らなければ名前は変わらない
        mockMvc.perform(patch(BASE + "/{id}", slug, a).with(user(XA.toString()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("A"))
                .andExpect(jsonPath("$.data.description").doesNotExist());
    }

    // ───────── AC-G114 並び替え ─────────

    @Test
    @DisplayName("AC-G114: 並び替えが成功すると sort_order が 0 から振り直され、一覧の並びに反映される（削除による欠番も詰める）")
    void reorder_renumbersFromZero() throws Exception {
        String a = createGroup("A", null);
        String b = createGroup("B", null);
        String c = createGroup("C", null);
        String d = createGroup("D", null);
        mockMvc.perform(delete(BASE + "/{id}", slug, b).with(user(XA.toString()))).andExpect(status().isNoContent());

        mockMvc.perform(put(BASE + "/order", slug).with(user(XA.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupIds", List.of(d, a, c)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(d))
                .andExpect(jsonPath("$.data[0].sortOrder").value(0))
                .andExpect(jsonPath("$.data[1].id").value(a))
                .andExpect(jsonPath("$.data[1].sortOrder").value(1))
                .andExpect(jsonPath("$.data[2].id").value(c))
                .andExpect(jsonPath("$.data[2].sortOrder").value(2));

        assertOrder(d, a, c);
        em.clear();
        @SuppressWarnings("unchecked")
        List<Number> orders = em.createNativeQuery(
                        "SELECT sort_order FROM org_team_groups WHERE organization_id = :o AND deleted_at IS NULL ORDER BY sort_order")
                .setParameter("o", org.getId()).getResultList();
        assertThat(orders.stream().map(Number::intValue).toList()).containsExactly(0, 1, 2);
    }

    // ───────── AC-G115 件数 ─────────

    @Test
    @DisplayName("AC-G115: teamCount と unassignedTeamCount は ACTIVE だけを数え、削除済みグループを指す行は未分類として数える")
    void counts_activeOnly_deletedGroupRowsAreUnassigned() throws Exception {
        OrgTeamGroupEntity g1 = newGroup(org.getId(), "G1", 0);
        OrgTeamGroupEntity gDeleted = newGroup(org.getId(), "削除済み", 1);
        em.createNativeQuery("UPDATE org_team_groups SET deleted_at = UTC_TIMESTAMP() WHERE id = :id")
                .setParameter("id", gDeleted.getId()).executeUpdate();
        newMembership(org.getId(), TeamOrgMembershipEntity.Status.ACTIVE, g1.getId());
        newMembership(org.getId(), TeamOrgMembershipEntity.Status.ACTIVE, g1.getId());
        newMembership(org.getId(), TeamOrgMembershipEntity.Status.PENDING, g1.getId());      // 数えない
        newMembership(org.getId(), TeamOrgMembershipEntity.Status.ACTIVE, gDeleted.getId()); // 未分類
        newMembership(org.getId(), TeamOrgMembershipEntity.Status.ACTIVE, null);             // 未分類
        newMembership(org.getId(), TeamOrgMembershipEntity.Status.PENDING, null);            // 数えない
        em.flush();
        em.clear();

        mockMvc.perform(get(BASE, slug).with(user(XA.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].teamCount").value(2))
                .andExpect(jsonPath("$.meta.unassignedTeamCount").value(2));
    }

    // ───────── AC-G129 SQL 本数 ─────────

    @Test
    @DisplayName("AC-G129: グループ一覧（teamCount）の SQL 本数は、グループ数・チーム数に比例して増えない")
    void list_statementCount_isConstant() throws Exception {
        for (int i = 0; i < 3; i++) {
            OrgTeamGroupEntity g = newGroup(org.getId(), "S" + i, i);
            newMembership(org.getId(), TeamOrgMembershipEntity.Status.ACTIVE, g.getId());
        }
        em.flush();
        em.clear();
        long small = statementsForList();

        for (int i = 3; i < 40; i++) {
            OrgTeamGroupEntity g = newGroup(org.getId(), "S" + i, i);
            newMembership(org.getId(), TeamOrgMembershipEntity.Status.ACTIVE, g.getId());
            newMembership(org.getId(), TeamOrgMembershipEntity.Status.ACTIVE, null);
        }
        em.flush();
        em.clear();
        long large = statementsForList();

        assertThat(large).as("3件と40件で SQL 本数が同じ").isEqualTo(small);
    }

    // ───────── AC-G103m〜p 監査ログ ─────────

    @Test
    @DisplayName("AC-G103m〜p: 作成・変更・削除・並び替えのそれぞれで、所定の action の監査ログが1行ずつ残る")
    void auditLogs_oneRowPerOperation() throws Exception {
        String a = createGroup("監査A", null);
        assertThat(auditCount("ORG_TEAM_GROUP_CREATED", org.getId(), XA)).isEqualTo(1);
        Object createdGroupId = em.createNativeQuery(
                        "SELECT JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.groupId')) FROM audit_logs "
                                + "WHERE event_type = 'ORG_TEAM_GROUP_CREATED' AND organization_id = :o")
                .setParameter("o", org.getId()).getSingleResult();
        assertThat(createdGroupId).isEqualTo(a);

        String b = createGroup("監査B", null);

        mockMvc.perform(patch(BASE + "/{id}", slug, a).with(user(XA.toString()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"監査A改\"}"))
                .andExpect(status().isOk());
        assertThat(auditCount("ORG_TEAM_GROUP_UPDATED", org.getId(), XA)).isEqualTo(1);

        mockMvc.perform(put(BASE + "/order", slug).with(user(XA.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("groupIds", List.of(b, a)))))
                .andExpect(status().isOk());
        assertThat(auditCount("ORG_TEAM_GROUP_REORDERED", org.getId(), XA)).isEqualTo(1);

        mockMvc.perform(delete(BASE + "/{id}", slug, a).with(user(XA.toString()))).andExpect(status().isNoContent());
        assertThat(auditCount("ORG_TEAM_GROUP_DELETED", org.getId(), XA)).isEqualTo(1);

        // 失敗した操作（409）では監査ログを残さない
        post409(BASE, "{\"name\":\"監査B\"}", "ORG_065");
        assertThat(auditCount("ORG_TEAM_GROUP_CREATED", org.getId(), XA)).isEqualTo(2);
    }

    // ───────── helpers ─────────

    private String createGroup(String name, String description) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", name);
        if (description != null) {
            body.put("description", description);
        }
        String json = mockMvc.perform(post(BASE, slug).with(user(XA.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(json);
        return node.path("data").path("id").asText();
    }

    private void post409(String path, String body, String code) throws Exception {
        expectCode(mockMvc.perform(post(path, slug).with(user(XA.toString()))
                .contentType(MediaType.APPLICATION_JSON).content(body)), 409, code);
    }

    private void post400(String body) throws Exception {
        mockMvc.perform(post(BASE, slug).with(user(XA.toString()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    private void reorder409(List<String> ids) throws Exception {
        expectCode(mockMvc.perform(put(BASE + "/order", slug).with(user(XA.toString()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("groupIds", ids)))), 409, "ORG_068");
    }

    private void expectCode(ResultActions actions, int httpStatus, String code) throws Exception {
        actions.andExpect(status().is(httpStatus)).andExpect(jsonPath("$.error.code").value(code));
    }

    private void assertOrder(String... ids) throws Exception {
        ResultActions list = mockMvc.perform(get(BASE, slug).with(user(XA.toString()))).andExpect(status().isOk());
        JsonNode data = objectMapper.readTree(list.andReturn().getResponse().getContentAsString()).path("data");
        List<String> actual = new ArrayList<>();
        data.forEach(n -> actual.add(n.path("id").asText()));
        assertThat(actual).containsExactly(ids);
    }

    private void setGroupsEnabled(boolean enabled) {
        em.createNativeQuery("UPDATE organizations SET team_groups_enabled = :v WHERE id = :id")
                .setParameter("v", enabled).setParameter("id", org.getId()).executeUpdate();
        em.clear();
    }

    private long statementsForList() throws Exception {
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        // 初回の認証系キャッシュ等の揺れを避けるため1回ならしてから測る
        mockMvc.perform(get(BASE, slug).with(user(XA.toString()))).andExpect(status().isOk());
        stats.clear();
        mockMvc.perform(get(BASE, slug).with(user(XA.toString()))).andExpect(status().isOk());
        return stats.getPrepareStatementCount();
    }
}
