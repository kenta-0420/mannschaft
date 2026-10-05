package com.mannschaft.app.organization.teamgroup;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 4-B — グループ割当（単体・一括）と、既存の参照 API 2本の拡張の IT（試練）。
 *
 * <p>実 MySQL（Testcontainers）＋実 Security フィルタ＋ MockMvc。認可・Service・Repository・組織ドメインの窓口は
 * モックしない。テストトランザクション内で動く（コミットを要する並行の検証は
 * {@link OrgTeamGroupAssignmentConcurrencyIT}）。</p>
 *
 * <p>担当 AC: G01〜G07・F10・F12（割当の2経路）・K06・M02・G111（割当の書き込み）・G115（絞り込み）・
 * G103l・G129・C04(a)(b)。G102（レートリミット）は {@link OrgTeamGroupAssignmentRateLimitWiringIT}。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 4-B グループ割当・加盟チーム一覧・チーム所属組織一覧")
class OrgTeamGroupAssignmentIT extends AbstractOrgTeamGroupAssignmentIT {

    private OrganizationEntity org;
    private String slug;
    private OrgTeamGroupEntity groupA;
    private OrgTeamGroupEntity groupB;

    @BeforeEach
    void setUp() {
        org = newOrg(true);
        slug = org.getSlug();
        groupA = newGroup(org.getId(), "A班", 0);
        groupB = newGroup(org.getId(), "B班", 1);
        seedOrgPerson(AXA, org.getId(), "ADMIN");
        seedOrgPerson(AXD, org.getId(), "DEPUTY_ADMIN");
        seedOrgPerson(AXM, org.getId(), "MEMBER");
        em.flush();
        em.clear();
    }

    // ───────── AC-G01 移行直後は全件が未分類 ─────────

    @Test
    @DisplayName("AC-G01: 割当が1件も無い加盟チームは全件が未分類で、unassigned=true で絞ると全件が出る（PENDING・他組織は出ない）")
    void unassignedFilter_returnsEverythingRightAfterMigration() throws Exception {
        TeamOrgMembershipEntity m1 = activeTeam(org.getId(), null);
        TeamOrgMembershipEntity m2 = activeTeam(org.getId(), null);
        TeamOrgMembershipEntity m3 = activeTeam(org.getId(), null);
        newMembership(org.getId(), TeamOrgMembershipEntity.Status.PENDING, null);
        OrganizationEntity other = newOrg(true);
        activeTeam(other.getId(), null);
        em.flush();
        em.clear();

        JsonNode all = orgTeams(AXA, slug, null);
        JsonNode unassigned = orgTeams(AXA, slug, "unassigned=true");

        assertThat(slugs(all)).containsExactlyInAnyOrder(slugOf(m1), slugOf(m2), slugOf(m3));
        assertThat(slugs(unassigned)).containsExactlyInAnyOrder(slugOf(m1), slugOf(m2), slugOf(m3));
        unassigned.forEach(n -> assertThat(n.path("teamGroup").isNull() || n.path("teamGroup").isMissingNode())
                .as("未分類は teamGroup が null").isTrue());
    }

    // ───────── AC-G02 単体割当 ─────────

    @Test
    @DisplayName("AC-G02: 1チームをグループAに割り当てると 200 で共通表現が返り、一覧のグループ列が A になる")
    void assignOne_changesTheGroupColumn() throws Exception {
        TeamOrgMembershipEntity m = activeTeam(org.getId(), null);
        TeamOrgMembershipEntity untouched = activeTeam(org.getId(), null);
        em.flush();
        em.clear();

        assignOne(AXA, slug, slugOf(m), groupA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(m.getId()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.team.slug").value(slugOf(m)))
                .andExpect(jsonPath("$.data.organization.slug").value(slug))
                .andExpect(jsonPath("$.data.teamGroup.id").value(groupA.getId().toString()))
                .andExpect(jsonPath("$.data.teamGroup.name").value("A班"));

        assertThat(groupIdOf(m)).isEqualTo(groupA.getId());
        assertThat(groupIdOf(untouched)).as("他のチームは変わらない").isNull();

        JsonNode list = orgTeams(AXA, slug, null);
        JsonNode row = find(list, slugOf(m));
        assertThat(row.path("teamGroup").path("id").asText()).isEqualTo(groupA.getId().toString());
        assertThat(row.path("teamGroup").path("name").asText()).isEqualTo("A班");
        assertThat(row.path("teamGroup").path("sortOrder").asInt()).isEqualTo(0);
    }

    @Test
    @DisplayName("単体割当: groupId=null で未分類へ戻せる。別グループへの付け替えもできる")
    void assignOne_nullUnassigns_andReassigns() throws Exception {
        TeamOrgMembershipEntity m = activeTeam(org.getId(), groupA.getId());
        em.flush();
        em.clear();

        assignOne(AXA, slug, slugOf(m), groupB.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teamGroup.id").value(groupB.getId().toString()));
        assertThat(groupIdOf(m)).isEqualTo(groupB.getId());

        assignOne(AXA, slug, slugOf(m), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teamGroup").doesNotExist());
        assertThat(groupIdOf(m)).isNull();
    }

    // ───────── AC-G03 一括割当 ─────────

    @Test
    @DisplayName("AC-G03: 3チームを一括でグループAに割り当てると updatedCount=3 で、3チームともグループAになる")
    void assignBulk_updatesAllTeams() throws Exception {
        TeamOrgMembershipEntity m1 = activeTeam(org.getId(), null);
        TeamOrgMembershipEntity m2 = activeTeam(org.getId(), groupB.getId());
        TeamOrgMembershipEntity m3 = activeTeam(org.getId(), null);
        TeamOrgMembershipEntity outside = activeTeam(org.getId(), null);
        em.flush();
        em.clear();

        assignBulk(AXA, slug, groupA.getId(), List.of(slugOf(m1), slugOf(m2), slugOf(m3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(3));

        assertThat(groupIdOf(m1)).isEqualTo(groupA.getId());
        assertThat(groupIdOf(m2)).isEqualTo(groupA.getId());
        assertThat(groupIdOf(m3)).isEqualTo(groupA.getId());
        assertThat(groupIdOf(outside)).as("指定していないチームは変わらない").isNull();
    }

    @Test
    @DisplayName("一括割当: groupId=null で複数チームを未分類へ戻せる。同じ slug が重複していても1チームとして数える")
    void assignBulk_nullUnassigns_andDeduplicates() throws Exception {
        TeamOrgMembershipEntity m1 = activeTeam(org.getId(), groupA.getId());
        TeamOrgMembershipEntity m2 = activeTeam(org.getId(), groupA.getId());
        em.flush();
        em.clear();

        assignBulk(AXA, slug, null, List.of(slugOf(m1), slugOf(m2), slugOf(m1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(2));

        assertThat(groupIdOf(m1)).isNull();
        assertThat(groupIdOf(m2)).isNull();
    }

    // ───────── AC-G04 全部か無しか ─────────

    @Test
    @DisplayName("AC-G04: 未加盟（存在しない・他組織・PENDING）のチームが混ざると 400 ORG_069 で該当 slug が返り、他のチームも更新されず監査も残らない")
    void assignBulk_isAllOrNothing() throws Exception {
        TeamOrgMembershipEntity valid1 = activeTeam(org.getId(), null);
        TeamOrgMembershipEntity valid2 = activeTeam(org.getId(), groupB.getId());
        TeamOrgMembershipEntity pending = newMembership(org.getId(), TeamOrgMembershipEntity.Status.PENDING, null);
        OrganizationEntity other = newOrg(true);
        TeamOrgMembershipEntity foreign = activeTeam(other.getId(), null);
        em.flush();
        em.clear();

        String body = assignBulk(AXA, slug, groupA.getId(),
                List.of(slugOf(valid1), "no-such-team-slug", slugOf(pending), slugOf(valid2), slugOf(foreign)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ORG_069"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("no-such-team-slug", slugOf(pending), slugOf(foreign));
        assertThat(body).as("正しい slug は不正として返さない")
                .doesNotContain("\"" + slugOf(valid1) + "\"").doesNotContain("\"" + slugOf(valid2) + "\"");

        assertThat(groupIdOf(valid1)).as("1件も更新されない").isNull();
        assertThat(groupIdOf(valid2)).as("既存の割当も変わらない").isEqualTo(groupB.getId());
        assertThat(groupIdOf(foreign)).isNull();
        assertThat(groupChangedAudits(org.getId())).as("失敗した一括割当は監査を残さない").isEmpty();
    }

    // ───────── AC-G05 / AC-G07 入力検証 ─────────

    @Test
    @DisplayName("AC-G05: teamSlugs が 501 件だと 400。500 件までは入力検証を通る")
    void assignBulk_limitsTo500() throws Exception {
        List<String> slugs501 = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            slugs501.add("t" + i);
        }
        TeamOrgMembershipEntity m = activeTeam(org.getId(), null);
        em.flush();
        em.clear();

        assignBulk(AXA, slug, groupA.getId(), slugs501).andExpect(status().isBadRequest());
        assertThat(groupIdOf(m)).isNull();

        // 500 件は入力検証を通り、存在しない slug が含まれるので業務エラー（ORG_069）になる
        assignBulk(AXA, slug, groupA.getId(), slugs501.subList(0, 500))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ORG_069"));
    }

    @Test
    @DisplayName("AC-G07: teamSlugs=[]・欠落・null・空白の slug は 400 で、更新は0件・既存の割当も変わらない")
    void assignBulk_rejectsEmptyAndMalformedLists() throws Exception {
        TeamOrgMembershipEntity m = activeTeam(org.getId(), groupB.getId());
        em.flush();
        em.clear();

        assignBulk(AXA, slug, groupA.getId(), List.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.updatedCount").doesNotExist());
        mockMvc.perform(put(BULK, slug).with(user(AXA.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + groupA.getId() + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(BULK, slug).with(user(AXA.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + groupA.getId() + "\",\"teamSlugs\":null}"))
                .andExpect(status().isBadRequest());
        assignBulk(AXA, slug, groupA.getId(), List.of(slugOf(m), " "))
                .andExpect(status().isBadRequest());

        assertThat(groupIdOf(m)).as("既存の割当は変わらない").isEqualTo(groupB.getId());
        assertThat(groupChangedAudits(org.getId())).isEmpty();
    }

    // ───────── AC-G06 / AC-F12 認可 ─────────

    @Test
    @DisplayName("AC-G06: XM・XD・他組織の ADMIN・組織に属さない人が割り当てると 403 で、状態は変わらない（単体・一括）")
    void assign_requiresOrgAdmin() throws Exception {
        seedOrgPerson(AYA, newOrg(true).getId(), "ADMIN");
        seedUserOnly(AN);
        TeamOrgMembershipEntity m = activeTeam(org.getId(), null);
        em.flush();
        em.clear();

        for (Long actor : List.of(AXM, AXD, AYA, AN)) {
            expectCode(assignOne(actor, slug, slugOf(m), groupA.getId()), 403, "COMMON_002");
            expectCode(assignBulk(actor, slug, groupA.getId(), List.of(slugOf(m))), 403, "COMMON_002");
        }
        assertThat(groupIdOf(m)).isNull();
        assertThat(groupChangedAudits(org.getId())).isEmpty();
    }

    @Test
    @DisplayName("認可の順序: 未認証は 401。存在しない組織は 404")
    void assign_unauthenticatedAndUnknownOrg() throws Exception {
        TeamOrgMembershipEntity m = activeTeam(org.getId(), null);
        em.flush();
        em.clear();

        mockMvc.perform(put(SINGLE, slug, slugOf(m)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":null}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(BULK, slug).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":null,\"teamSlugs\":[\"x\"]}"))
                .andExpect(status().isUnauthorized());
        assignBulk(AXA, "no-such-org-slug", groupA.getId(), List.of(slugOf(m))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AC-F12: SYSTEM_ADMIN が単体割当・一括割当を行うと 403 で、割当は変わらない（閲覧のみ）")
    void assign_systemAdminIsForbidden() throws Exception {
        seedSystemAdmin(ASYS);
        TeamOrgMembershipEntity m = activeTeam(org.getId(), groupB.getId());
        em.flush();
        em.clear();

        expectCode(assignOne(ASYS, slug, slugOf(m), groupA.getId()), 403, "COMMON_002");
        expectCode(assignBulk(ASYS, slug, groupA.getId(), List.of(slugOf(m))), 403, "COMMON_002");

        assertThat(groupIdOf(m)).isEqualTo(groupB.getId());
        assertThat(groupChangedAudits(org.getId())).isEmpty();
    }

    @Test
    @DisplayName("AC-F12: SYSTEM_ADMIN が組織 ADMIN を兼ねていても、単体・一括の割当は 403 で、割当は変わらない")
    void assign_systemAdminWhoIsAlsoOrgAdminIsForbidden() throws Exception {
        seedSystemAdmin(ASYS);
        seedOrgPerson(ASYS, org.getId(), "ADMIN");
        TeamOrgMembershipEntity m = activeTeam(org.getId(), groupB.getId());
        em.flush();
        em.clear();

        expectCode(assignOne(ASYS, slug, slugOf(m), groupA.getId()), 403, "COMMON_002");
        expectCode(assignBulk(ASYS, slug, groupA.getId(), List.of(slugOf(m))), 403, "COMMON_002");

        assertThat(groupIdOf(m)).isEqualTo(groupB.getId());
        assertThat(groupChangedAudits(org.getId())).isEmpty();
    }

    @Test
    @DisplayName("非公開組織は、見えない人（無所属・他組織の ADMIN）には存在しない組織と同じ 404 ORG_001（単体・一括）")
    void assign_invisibleOrganizationLooksNonexistent() throws Exception {
        OrganizationEntity priv = newOrg(true);
        em.createNativeQuery("UPDATE organizations SET visibility = 'PRIVATE' WHERE id = :id")
                .setParameter("id", priv.getId()).executeUpdate();
        OrgTeamGroupEntity g = newGroup(priv.getId(), "秘密の班", 0);
        TeamOrgMembershipEntity m = activeTeam(priv.getId(), null);
        seedOrgPerson(AYA, newOrg(true).getId(), "ADMIN");
        seedUserOnly(AN);
        em.flush();
        em.clear();

        for (Long outsider : List.of(AN, AYA)) {
            String single = assignOne(outsider, priv.getSlug(), slugOf(m), g.getId())
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ORG_001"))
                    .andReturn().getResponse().getContentAsString();
            String bulk = assignBulk(outsider, priv.getSlug(), g.getId(), List.of(slugOf(m)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ORG_001"))
                    .andReturn().getResponse().getContentAsString();
            String missing = assignBulk(outsider, "no-such-org-slug", g.getId(), List.of(slugOf(m)))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            assertThat(bulk).as("存在しない組織と同じ本文").isEqualTo(missing);
            assertThat(single).isEqualTo(missing);
        }
        assertThat(groupIdOf(m)).isNull();
    }

    // ───────── AC-K06 他組織・削除済み・不在のグループ ─────────

    @Test
    @DisplayName("AC-K06: 他組織・削除済み・存在しないグループ ID は、単体・一括とも同じ 404 ORG_064 で、状態は変わらない")
    void assign_foreignDeletedAndUnknownGroupsAreIndistinguishable() throws Exception {
        OrganizationEntity other = newOrg(true);
        OrgTeamGroupEntity foreignGroup = newGroup(other.getId(), "他組織の班", 0);
        OrgTeamGroupEntity deletedGroup = newGroup(org.getId(), "削除済みの班", 2);
        em.createNativeQuery("UPDATE org_team_groups SET deleted_at = UTC_TIMESTAMP() WHERE id = :id")
                .setParameter("id", deletedGroup.getId()).executeUpdate();
        TeamOrgMembershipEntity m = activeTeam(org.getId(), groupB.getId());
        em.flush();
        em.clear();

        for (UUID bad : List.of(foreignGroup.getId(), deletedGroup.getId(), UUID.randomUUID())) {
            expectCode(assignOne(AXA, slug, slugOf(m), bad), 404, "ORG_064");
            expectCode(assignBulk(AXA, slug, bad, List.of(slugOf(m))), 404, "ORG_064");
        }
        assertThat(groupIdOf(m)).isEqualTo(groupB.getId());
        assertThat(groupChangedAudits(org.getId())).isEmpty();
    }

    @Test
    @DisplayName("単体割当: その組織の ACTIVE 加盟でないチーム（存在しない・他組織・PENDING）は、区別できない同じ 404 TEAM_070")
    void assignOne_nonMemberTeamsAreIndistinguishable() throws Exception {
        TeamOrgMembershipEntity pending = newMembership(org.getId(), TeamOrgMembershipEntity.Status.PENDING, null);
        TeamOrgMembershipEntity foreign = activeTeam(newOrg(true).getId(), null);
        em.flush();
        em.clear();

        for (String teamSlug : List.of("no-such-team-slug", slugOf(pending), slugOf(foreign))) {
            expectCode(assignOne(AXA, slug, teamSlug, groupA.getId()), 404, "TEAM_070");
        }
        assertThat(groupIdOf(pending)).isNull();
        assertThat(groupIdOf(foreign)).isNull();
    }

    // ───────── AC-G111 グループ機能 off ─────────

    @Test
    @DisplayName("AC-G111: グループ機能 off の組織では、単体・一括の割当が 409 ORG_067（未分類へ戻す操作も同じ）で、状態は変わらない")
    void assign_conflictsWhenFeatureOff() throws Exception {
        OrganizationEntity off = newOrg(false);
        seedOrgPerson(AXA, off.getId(), "ADMIN");
        OrgTeamGroupEntity staleGroup = newGroup(off.getId(), "残っているグループ", 0);
        TeamOrgMembershipEntity m = activeTeam(off.getId(), staleGroup.getId());
        em.flush();
        em.clear();

        expectCode(assignOne(AXA, off.getSlug(), slugOf(m), staleGroup.getId()), 409, "ORG_067");
        expectCode(assignOne(AXA, off.getSlug(), slugOf(m), null), 409, "ORG_067");
        expectCode(assignBulk(AXA, off.getSlug(), staleGroup.getId(), List.of(slugOf(m))), 409, "ORG_067");
        expectCode(assignBulk(AXA, off.getSlug(), null, List.of(slugOf(m))), 409, "ORG_067");

        assertThat(groupIdOf(m)).as("off の間も割当は保持される").isEqualTo(staleGroup.getId());
    }

    @Test
    @DisplayName("AC-G111: グループ機能 off の間、加盟チーム一覧の teamGroup は null（割当は消えず、on に戻すと見える）")
    void listShowsNoGroupWhileFeatureOff() throws Exception {
        TeamOrgMembershipEntity m = activeTeam(org.getId(), groupA.getId());
        em.flush();
        em.clear();
        assertThat(find(orgTeams(AXA, slug, null), slugOf(m)).path("teamGroup").path("id").asText())
                .isEqualTo(groupA.getId().toString());

        em.createNativeQuery("UPDATE organizations SET team_groups_enabled = 0 WHERE id = :id")
                .setParameter("id", org.getId()).executeUpdate();
        em.clear();
        JsonNode off = find(orgTeams(AXA, slug, null), slugOf(m));
        assertThat(off.path("teamGroup").isNull() || off.path("teamGroup").isMissingNode()).isTrue();

        em.createNativeQuery("UPDATE organizations SET team_groups_enabled = 1 WHERE id = :id")
                .setParameter("id", org.getId()).executeUpdate();
        em.clear();
        assertThat(find(orgTeams(AXA, slug, null), slugOf(m)).path("teamGroup").path("id").asText())
                .isEqualTo(groupA.getId().toString());
    }

    // ───────── AC-G103l 監査 ─────────

    @Test
    @DisplayName("AC-G103l: 割当の変更ごと（1チームにつき1行）に TEAM_ORG_GROUP_CHANGED が残り、from・to を持つ。変化のない再送は残さない")
    void assign_recordsOneAuditRowPerChangedTeam() throws Exception {
        TeamOrgMembershipEntity m1 = activeTeam(org.getId(), null);
        TeamOrgMembershipEntity m2 = activeTeam(org.getId(), groupB.getId());
        TeamOrgMembershipEntity m3 = activeTeam(org.getId(), groupA.getId());
        em.flush();
        em.clear();

        assignOne(AXA, slug, slugOf(m1), groupA.getId()).andExpect(status().isOk());
        List<Object[]> single = groupChangedAudits(org.getId());
        assertThat(single).hasSize(1);
        assertThat(((Number) single.get(0)[0]).longValue()).isEqualTo(m1.getTeamId());
        assertThat(((Number) single.get(0)[1]).longValue()).as("操作者").isEqualTo(AXA);
        assertThat(((Number) single.get(0)[2]).longValue()).isEqualTo(org.getId());
        JsonNode meta = objectMapper.readTree(String.valueOf(single.get(0)[3]));
        assertThat(meta.path("from").isNull()).as("未分類からの変更は from=null").isTrue();
        assertThat(meta.path("to").asText()).isEqualTo(groupA.getId().toString());

        // m3 は既にグループAなので変化なし。m1 も変化なし。変わるのは m2 だけ
        assignBulk(AXA, slug, groupA.getId(), List.of(slugOf(m1), slugOf(m2), slugOf(m3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(3));
        List<Object[]> afterBulk = groupChangedAudits(org.getId());
        assertThat(afterBulk).as("1件目 + m2 の1件").hasSize(2);
        Object[] last = afterBulk.get(1);
        assertThat(((Number) last[0]).longValue()).isEqualTo(m2.getTeamId());
        JsonNode lastMeta = objectMapper.readTree(String.valueOf(last[3]));
        assertThat(lastMeta.path("from").asText()).isEqualTo(groupB.getId().toString());
        assertThat(lastMeta.path("to").asText()).isEqualTo(groupA.getId().toString());

        assignOne(AXA, slug, slugOf(m3), null).andExpect(status().isOk());
        List<Object[]> afterClear = groupChangedAudits(org.getId());
        assertThat(afterClear).hasSize(3);
        JsonNode clearMeta = objectMapper.readTree(String.valueOf(afterClear.get(2)[3]));
        assertThat(clearMeta.path("from").asText()).isEqualTo(groupA.getId().toString());
        assertThat(clearMeta.path("to").isNull()).as("未分類へ戻すと to=null").isTrue();
    }

    // ───────── AC-G115 絞り込み ─────────

    @Test
    @DisplayName("AC-G115: teamGroupId で絞ると、そのグループの ACTIVE のチームだけが出る（PENDING・他グループ・未分類は出ない）")
    void filterByGroup() throws Exception {
        TeamOrgMembershipEntity inA1 = activeTeam(org.getId(), groupA.getId());
        TeamOrgMembershipEntity inA2 = activeTeam(org.getId(), groupA.getId());
        activeTeam(org.getId(), groupB.getId());
        activeTeam(org.getId(), null);
        newMembership(org.getId(), TeamOrgMembershipEntity.Status.PENDING, groupA.getId());
        em.flush();
        em.clear();

        JsonNode inGroupA = orgTeams(AXA, slug, "teamGroupId=" + groupA.getId());

        assertThat(slugs(inGroupA)).containsExactlyInAnyOrder(slugOf(inA1), slugOf(inA2));
        inGroupA.forEach(n -> assertThat(n.path("teamGroup").path("name").asText()).isEqualTo("A班"));
    }

    @Test
    @DisplayName("AC-G115: 削除済みグループを指す行（付け替えリスナーの処理前）は未分類として扱われ、unassigned=true に出て、teamGroupId では出ない")
    void deletedGroupRowsCountAsUnassigned() throws Exception {
        OrgTeamGroupEntity doomed = newGroup(org.getId(), "削除される班", 2);
        TeamOrgMembershipEntity dangling = activeTeam(org.getId(), doomed.getId());
        TeamOrgMembershipEntity plain = activeTeam(org.getId(), null);
        TeamOrgMembershipEntity inA = activeTeam(org.getId(), groupA.getId());
        em.createNativeQuery("UPDATE org_team_groups SET deleted_at = UTC_TIMESTAMP() WHERE id = :id")
                .setParameter("id", doomed.getId()).executeUpdate();
        em.flush();
        em.clear();

        assertThat(slugs(orgTeams(AXA, slug, "unassigned=true")))
                .containsExactlyInAnyOrder(slugOf(dangling), slugOf(plain));
        assertThat(orgTeams(AXA, slug, "teamGroupId=" + doomed.getId())).as("削除済みグループでは絞れない").isEmpty();
        assertThat(slugs(orgTeams(AXA, slug, "teamGroupId=" + groupA.getId()))).containsExactly(slugOf(inA));

        JsonNode row = find(orgTeams(AXA, slug, null), slugOf(dangling));
        assertThat(row.path("teamGroup").isNull() || row.path("teamGroup").isMissingNode())
                .as("削除済みグループは teamGroup に出さない").isTrue();
    }

    @Test
    @DisplayName("AC-G115: 他組織・存在しないグループ ID で絞っても存在オラクルにならず、空の一覧（200）。teamGroupId と unassigned の併用は 400")
    void filterEdgeCases() throws Exception {
        OrgTeamGroupEntity foreignGroup = newGroup(newOrg(true).getId(), "他組織の班", 0);
        activeTeam(org.getId(), groupA.getId());
        em.flush();
        em.clear();

        assertThat(orgTeams(AXA, slug, "teamGroupId=" + foreignGroup.getId())).isEmpty();
        assertThat(orgTeams(AXA, slug, "teamGroupId=" + UUID.randomUUID())).isEmpty();
        mockMvc.perform(get(ORG_TEAMS + "?teamGroupId=" + groupA.getId() + "&unassigned=true", slug)
                        .with(user(AXA.toString())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(ORG_TEAMS + "?teamGroupId=not-a-uuid", slug).with(user(AXA.toString())))
                .andExpect(status().isBadRequest());
    }

    // ───────── グループ名の見え方（§3.1） ─────────

    @Test
    @DisplayName("グループ名は組織 MEMBER 以上にだけ見える。組織に属さない人には teamGroup が出ず、絞り込みは 403")
    void groupNamesAreHiddenFromNonMembers() throws Exception {
        TeamOrgMembershipEntity m = activeTeam(org.getId(), groupA.getId());
        seedUserOnly(AN);
        em.flush();
        em.clear();

        assertThat(find(orgTeams(AXM, slug, null), slugOf(m)).path("teamGroup").path("name").asText())
                .isEqualTo("A班");
        JsonNode asOutsider = find(orgTeams(AN, slug, null), slugOf(m));
        assertThat(asOutsider.path("teamGroup").isNull() || asOutsider.path("teamGroup").isMissingNode())
                .as("非メンバーにグループ名を見せない").isTrue();
        expectCode(mockMvc.perform(get(ORG_TEAMS + "?unassigned=true", slug).with(user(AN.toString()))),
                403, "COMMON_002");
        expectCode(mockMvc.perform(get(ORG_TEAMS + "?teamGroupId=" + groupA.getId(), slug)
                .with(user(AN.toString()))), 403, "COMMON_002");
    }

    // ───────── AC-M02 既存フィールド不変 ─────────

    @Test
    @DisplayName("AC-M02: 加盟チーム一覧の既存フィールド（id・slug・name・iconUrl・visibility・memberCount）の値が変わらない")
    void orgTeamList_existingFieldsUnchanged() throws Exception {
        TeamOrgMembershipEntity m = activeTeam(org.getId(), groupA.getId());
        var team = teamRepository.findById(m.getTeamId()).orElseThrow();
        MembershipTestHelper.insertActiveUser(em, ATM);
        MembershipTestHelper.insertUserRole(em, ATM, "ADMIN", team.getId(), null);
        em.flush();
        em.clear();

        JsonNode row = find(orgTeams(AXA, slug, null), team.getSlug());

        assertThat(row.path("id").asText()).isEqualTo(team.getSlug());
        assertThat(row.path("slug").asText()).isEqualTo(team.getSlug());
        assertThat(row.path("name").asText()).isEqualTo(team.getName());
        assertThat(row.path("iconUrl").isNull() || row.path("iconUrl").isMissingNode()).isTrue();
        assertThat(row.path("visibility").asText()).isEqualTo("PUBLIC");
        assertThat(row.path("memberCount").asInt()).isEqualTo(1);
    }

    // ───────── AC-F10 / AC-C04(a)(b) チーム側の参照 ─────────

    @Test
    @DisplayName("AC-F10/C04(b): チームのメンバーは GET /teams/T/organizations で、加盟している全組織を、自チームのグループ名だけ付けて見られる")
    void teamSideShowsOwnGroupNameOnly() throws Exception {
        OrganizationEntity orgY = newOrg(true);
        OrgTeamGroupEntity yGroup = newGroup(orgY.getId(), "Y組織の班", 0);
        newGroup(orgY.getId(), "Y組織の他の班", 1);
        OrganizationEntity orgOff = newOrg(false);
        OrgTeamGroupEntity staleGroup = newGroup(orgOff.getId(), "off組織の残骸", 0);

        TeamOrgMembershipEntity inX = activeTeam(org.getId(), groupA.getId());
        Long teamId = inX.getTeamId();
        String teamSlug = slugOf(inX);
        insertActiveMembership(teamId, orgY.getId(), yGroup.getId());
        insertActiveMembership(teamId, orgOff.getId(), staleGroup.getId());
        seedTeamMember(ATM, teamId);
        em.flush();
        em.clear();

        String body = mockMvc.perform(get(TEAM_ORGS, teamSlug).with(user(ATM.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");

        JsonNode x = find(data, org.getSlug());
        assertThat(x.path("teamGroup").path("id").asText()).isEqualTo(groupA.getId().toString());
        assertThat(x.path("teamGroup").path("name").asText()).isEqualTo("A班");
        assertThat(x.path("teamGroup").has("sortOrder")).as("チーム側には並び順を出さない").isFalse();
        assertThat(find(data, orgY.getSlug()).path("teamGroup").path("name").asText()).isEqualTo("Y組織の班");
        JsonNode off = find(data, orgOff.getSlug());
        assertThat(off.path("teamGroup").isNull() || off.path("teamGroup").isMissingNode())
                .as("グループ機能 off の組織のグループは出さない").isTrue();
        assertThat(body).as("自チームが属さない他のグループ名は出ない")
                .doesNotContain("B班").doesNotContain("Y組織の他の班");
        // 既存フィールド（AC-M02）
        assertThat(x.path("slug").asText()).isEqualTo(org.getSlug());
        assertThat(x.path("name").asText()).isEqualTo(org.getName());
        assertThat(x.path("visibility").asText()).isEqualTo("PUBLIC");
    }

    @Test
    @DisplayName("AC-F10: チームに属さない人（組織の ADMIN を含む）には、チーム側の一覧にグループ名が出ない。削除済みグループも出ない")
    void teamSideHidesGroupFromOutsidersAndDeletedGroups() throws Exception {
        TeamOrgMembershipEntity inX = activeTeam(org.getId(), groupA.getId());
        OrganizationEntity orgY = newOrg(true);
        OrgTeamGroupEntity deleted = newGroup(orgY.getId(), "消えた班", 0);
        em.createNativeQuery("UPDATE org_team_groups SET deleted_at = UTC_TIMESTAMP() WHERE id = :id")
                .setParameter("id", deleted.getId()).executeUpdate();
        insertActiveMembership(inX.getTeamId(), orgY.getId(), deleted.getId());
        seedTeamMember(ATM, inX.getTeamId());
        seedUserOnly(AN);
        em.flush();
        em.clear();

        for (Long outsider : List.of(AN, AXA)) {
            String body = mockMvc.perform(get(TEAM_ORGS, slugOf(inX)).with(user(outsider.toString())))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(body).as("チームに属さない人には自チームのグループ名を見せない").doesNotContain("A班");
        }
        String asMember = mockMvc.perform(get(TEAM_ORGS, slugOf(inX)).with(user(ATM.toString())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode y = find(objectMapper.readTree(asMember).path("data"), orgY.getSlug());
        assertThat(y.path("teamGroup").isNull() || y.path("teamGroup").isMissingNode())
                .as("削除済みグループは未分類（null）").isTrue();
    }

    @Test
    @DisplayName("AC-C04(a): 2つの組織に加盟したチームは、両方の組織の加盟チーム一覧に出る（それぞれの組織のグループで）")
    void teamInTwoOrgsAppearsInBothOrgTeamLists() throws Exception {
        OrganizationEntity orgY = newOrg(true);
        OrgTeamGroupEntity yGroup = newGroup(orgY.getId(), "Y組織の班", 0);
        seedOrgPerson(AYA, orgY.getId(), "ADMIN");
        TeamOrgMembershipEntity inX = activeTeam(org.getId(), groupA.getId());
        insertActiveMembership(inX.getTeamId(), orgY.getId(), yGroup.getId());
        em.flush();
        em.clear();

        JsonNode fromX = find(orgTeams(AXA, slug, null), slugOf(inX));
        JsonNode fromY = find(orgTeams(AYA, orgY.getSlug(), null), slugOf(inX));

        assertThat(fromX.path("teamGroup").path("name").asText()).isEqualTo("A班");
        assertThat(fromY.path("teamGroup").path("name").asText()).isEqualTo("Y組織の班");
    }

    // ───────── AC-G129 N+1 ─────────

    @Test
    @DisplayName("AC-G129: 加盟チーム一覧（teamGroup 付き・絞り込み付き）の SQL 本数は、チーム数・グループ数に比例して増えない")
    void orgTeamList_statementCountDoesNotGrowWithRows() throws Exception {
        for (int i = 0; i < 2; i++) {
            activeTeam(org.getId(), i % 2 == 0 ? groupA.getId() : null);
        }
        em.flush();
        em.clear();
        long few = statementsForOrgTeams(null);
        long fewFiltered = statementsForOrgTeams("teamGroupId=" + groupA.getId());

        for (int i = 0; i < 12; i++) {
            OrgTeamGroupEntity g = newGroup(org.getId(), "追加の班" + i, 10 + i);
            activeTeam(org.getId(), g.getId());
            activeTeam(org.getId(), i % 2 == 0 ? groupA.getId() : null);
        }
        em.flush();
        em.clear();
        long many = statementsForOrgTeams(null);
        long manyFiltered = statementsForOrgTeams("teamGroupId=" + groupA.getId());

        assertThat(many).as("チーム 2 → 26 件で SQL 本数が増えない").isEqualTo(few);
        assertThat(manyFiltered).as("絞り込みでも増えない").isEqualTo(fewFiltered);
    }

    @Test
    @DisplayName("AC-G129: チーム所属組織一覧（teamGroup 付き）の SQL 本数は、加盟している組織の数に比例して増えない")
    void teamOrgList_statementCountDoesNotGrowWithRows() throws Exception {
        TeamOrgMembershipEntity inX = activeTeam(org.getId(), groupA.getId());
        seedTeamMember(ATM, inX.getTeamId());
        em.flush();
        em.clear();
        long few = statementsForTeamOrgs(slugOf(inX));

        for (int i = 0; i < 5; i++) {
            OrganizationEntity extra = newOrg(true);
            OrgTeamGroupEntity g = newGroup(extra.getId(), "班" + i, 0);
            insertActiveMembership(inX.getTeamId(), extra.getId(), g.getId());
        }
        em.flush();
        em.clear();
        long many = statementsForTeamOrgs(slugOf(inX));

        assertThat(many).as("組織 1 → 6 件で SQL 本数が増えない").isEqualTo(few);
    }

    // ───────── 内部 ─────────

    private void insertActiveMembership(Long teamId, Long orgId, UUID groupId) {
        membershipRepository.saveAndFlush(TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(orgId)
                .status(TeamOrgMembershipEntity.Status.ACTIVE)
                .invitedAt(java.time.LocalDateTime.now())
                .groupId(groupId)
                .build());
    }

    private long statementsForOrgTeams(String query) throws Exception {
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        orgTeams(AXA, slug, query);
        em.clear();
        stats.clear();
        orgTeams(AXA, slug, query);
        return stats.getPrepareStatementCount();
    }

    private long statementsForTeamOrgs(String teamSlug) throws Exception {
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        mockMvc.perform(get(TEAM_ORGS, teamSlug).with(user(ATM.toString()))).andExpect(status().isOk());
        em.clear();
        stats.clear();
        mockMvc.perform(get(TEAM_ORGS, teamSlug).with(user(ATM.toString()))).andExpect(status().isOk());
        return stats.getPrepareStatementCount();
    }

    private static List<String> slugs(JsonNode data) {
        List<String> result = new ArrayList<>();
        data.forEach(n -> result.add(n.path("slug").asText()));
        return result;
    }

    private static JsonNode find(JsonNode data, String slug) {
        for (JsonNode n : data) {
            if (slug.equals(n.path("slug").asText())) {
                return n;
            }
        }
        throw new AssertionError("一覧に slug=" + slug + " が無い: " + data);
    }
}
