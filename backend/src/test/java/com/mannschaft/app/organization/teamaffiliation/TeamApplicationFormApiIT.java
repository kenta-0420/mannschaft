package com.mannschaft.app.organization.teamaffiliation;

import com.jayway.jsonpath.JsonPath;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.N;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.TA;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.TD;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.TG;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.TM;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.YA;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.normalize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 2-A — 申請フォーム API（{@code GET /api/v1/organizations/{slug}/team-application-form}）の
 * 受け入れテスト（試練）。
 *
 * <p>正本: {@code docs/features/F01.2.1_org_team_groups.md} §10.3・§5.5・§4.4・§16 B/C。
 * 実 Security フィルタ・Testcontainers MySQL・実 Service / Repository を通し、DB・認可・自分の Bean はモックしない。</p>
 *
 * <p>担当 AC: B03（受付 off は 403 TEAM_064）・C11（制限中は UNAVAILABLE、冷却とブロックを区別しない）・
 * G116（myTeams の状態と上限100件、groups の並び順、OFF なら空配列）・G111（グループ機能 off は実効 OFF）。
 * 存在オラクル（非公開・存在しない slug は同じ 404・同じエラーコード・同じ本文。受付 off より先に判定）も固定する。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-A 申請フォーム API 受け入れテスト")
class TeamApplicationFormApiIT extends AbstractMySqlIntegrationTest {

    private static final String PATH = "/api/v1/organizations/{slug}/team-application-form";

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    private TeamAffiliationApiFixture fx;

    @BeforeEach
    void setUp() {
        fx = new TeamAffiliationApiFixture(em).seed();
    }

    private ResultActions getAs(Long userId, String slug) throws Exception {
        return mockMvc.perform(get(PATH, slug).with(user(userId.toString())));
    }

    /** myTeams を slug → affiliationStatus の対応にして返す。 */
    private Map<String, String> statusesOf(MvcResult result) throws Exception {
        String json = result.getResponse().getContentAsString();
        List<String> slugs = JsonPath.read(json, "$.data.myTeams[*].slug");
        List<String> statuses = JsonPath.read(json, "$.data.myTeams[*].affiliationStatus");
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < slugs.size(); i++) {
            map.put(slugs.get(i), statuses.get(i));
        }
        return map;
    }

    /** TA が ADMIN を務めるチームを追加で作る。 */
    private String addTeamAdministeredByTa(String prefix) {
        String slug = fx.slug(prefix);
        Long teamId = fx.insertTeam("TA のチーム " + slug, slug);
        MembershipTestHelper.insertMembership(em, TA, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, TA, "ADMIN", teamId, null);
        return slug;
    }

    private Long teamIdOf(String slug) {
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :s")
                .setParameter("s", slug).getSingleResult()).longValue();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-B03
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-B03: 受付 off の組織Xの申請フォーム API を TA が直打ちすると 403 TEAM_064")
    void applicationClosed() throws Exception {
        getAs(TA, fx.orgXSlug)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("TEAM_064"));
    }

    @Test
    @DisplayName("§10.3: 受付 on なら 200 で、組織の slug・名前と案内文を返す")
    void applicationOpen() throws Exception {
        fx.setOrgSettings(fx.orgXId, true, false, "OFF");
        fx.setGuidance(fx.orgXId, "卒業年度を選んでください");

        getAs(TA, fx.orgXSlug)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organization.slug").value(fx.orgXSlug))
                .andExpect(jsonPath("$.data.organization.name").exists())
                .andExpect(jsonPath("$.data.guidance").value("卒業年度を選んでください"))
                .andExpect(jsonPath("$.data.groupMode").value("OFF"))
                .andExpect(jsonPath("$.data.groups", hasSize(0)));
    }

    @Test
    @DisplayName("§10.1: 未認証で呼ぶと 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(get(PATH, fx.orgXSlug)).andExpect(status().isUnauthorized());
    }

    // ═════════════════════════════════════════════════════════════════════
    // 存在オラクル
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("存在オラクル（可視性 404 は受付状態 403 より先）")
    class ExistenceOracle {

        @Test
        @DisplayName("受付 on の非公開組織を非所属の TA・YA・N が開くと、存在しない slug と同じ 404・同じコード・同じ本文（TEAM_064 は出ない）")
        void privateOrgIsIndistinguishableFromAbsent() throws Exception {
            fx.setOrgSettings(fx.orgPId, true, false, "OFF");
            for (Long actor : new Long[] {TA, YA, N}) {
                MvcResult absent = getAs(actor, fx.absentSlug).andExpect(status().isNotFound()).andReturn();
                MvcResult priv = getAs(actor, fx.orgPSlug).andExpect(status().isNotFound()).andReturn();
                String absentBody = absent.getResponse().getContentAsString();
                assertThat(absentBody).contains("ORG_001");
                assertThat(normalize(priv.getResponse().getContentAsString(), fx.orgPSlug))
                        .isEqualTo(normalize(absentBody, fx.absentSlug));
            }
        }

        @Test
        @DisplayName("受付 off の非公開組織も、受付 on の非公開組織と同じ 404（受付状態が漏れない）")
        void closedPrivateOrgAlsoNotFound() throws Exception {
            MvcResult closed = getAs(TA, fx.orgPSlug).andExpect(status().isNotFound()).andReturn();
            fx.setOrgSettings(fx.orgPId, true, false, "OFF");
            MvcResult open = getAs(TA, fx.orgPSlug).andExpect(status().isNotFound()).andReturn();
            assertThat(closed.getResponse().getContentAsString())
                    .isEqualTo(open.getResponse().getContentAsString());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-G116 / AC-C11 myTeams
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-G116/C11 myTeams")
    class MyTeams {

        @BeforeEach
        void open() {
            fx.setOrgSettings(fx.orgXId, true, false, "OFF");
        }

        @Test
        @DisplayName("AC-G116: myTeams が NONE・APPLYING・INVITED・ACTIVE を正しく返す")
        void statuses() throws Exception {
            String applying = addTeamAdministeredByTa("apl");
            String invited = addTeamAdministeredByTa("inv");
            String active = addTeamAdministeredByTa("act");
            fx.insertTeamOrgMembership(teamIdOf(applying), fx.orgXId, "PENDING", "TEAM_APPLY");
            fx.insertTeamOrgMembership(teamIdOf(invited), fx.orgXId, "PENDING", "ORG_INVITE");
            fx.insertTeamOrgMembership(teamIdOf(active), fx.orgXId, "ACTIVE", "ORG_INVITE");
            // 他組織との関係は、この組織の状態に影響しない
            fx.insertTeamOrgMembership(fx.teamTId, fx.orgYId, "ACTIVE", "ORG_INVITE");
            em.flush();
            em.clear();

            MvcResult r = getAs(TA, fx.orgXSlug).andExpect(status().isOk()).andReturn();
            assertThat(statusesOf(r))
                    .containsEntry(fx.teamTSlug, "NONE")
                    .containsEntry(applying, "APPLYING")
                    .containsEntry(invited, "INVITED")
                    .containsEntry(active, "ACTIVE")
                    .hasSize(4);
        }

        @Test
        @DisplayName("AC-C11: 制限中のチームは UNAVAILABLE になり、冷却（COOLDOWN）かブロック（BLOCK）かを区別しない")
        void restrictedTeamsAreUnavailable() throws Exception {
            String cooled = addTeamAdministeredByTa("cool");
            String blocked = addTeamAdministeredByTa("blk");
            fx.insertApplyRestriction(teamIdOf(cooled), fx.orgXId, "COOLDOWN", "REJECTED");
            fx.insertApplyRestriction(teamIdOf(blocked), fx.orgXId, "BLOCK", "REJECTED");
            em.flush();
            em.clear();

            MvcResult r = getAs(TA, fx.orgXSlug).andExpect(status().isOk()).andReturn();
            assertThat(statusesOf(r))
                    .containsEntry(cooled, "UNAVAILABLE")
                    .containsEntry(blocked, "UNAVAILABLE")
                    .containsEntry(fx.teamTSlug, "NONE");
            String json = r.getResponse().getContentAsString();
            assertThat(json).doesNotContain("COOLDOWN").doesNotContain("BLOCK");
        }

        @Test
        @DisplayName("AC-C11: 期限切れの冷却は判定で無視され NONE になる。他組織への制限・招待方向の制限も無関係")
        void expiredOrUnrelatedRestrictionsIgnored() throws Exception {
            String expired = addTeamAdministeredByTa("exp");
            String otherOrg = addTeamAdministeredByTa("oth");
            fx.insertApplyRestriction(teamIdOf(expired), fx.orgXId, "COOLDOWN", "WITHDRAWN");
            em.createNativeQuery("UPDATE team_org_affiliation_restrictions "
                            + "SET restricted_until = DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 DAY) WHERE team_id = :t")
                    .setParameter("t", teamIdOf(expired))
                    .executeUpdate();
            fx.insertApplyRestriction(teamIdOf(otherOrg), fx.orgYId, "BLOCK", "REJECTED");
            em.flush();
            em.clear();

            MvcResult r = getAs(TA, fx.orgXSlug).andExpect(status().isOk()).andReturn();
            assertThat(statusesOf(r))
                    .containsEntry(expired, "NONE")
                    .containsEntry(otherOrg, "NONE");
        }

        @Test
        @DisplayName("AC-G116: myTeams は加盟操作権限を持つチームだけ。TG（付与あり）は T を得て、TM・TD・N は空")
        void onlyOperableTeams() throws Exception {
            MvcResult tg = getAs(TG, fx.orgXSlug).andExpect(status().isOk()).andReturn();
            assertThat(statusesOf(tg)).containsOnlyKeys(fx.teamTSlug);

            for (Long actor : new Long[] {TM, TD, N}) {
                getAs(actor, fx.orgXSlug)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.myTeams", hasSize(0)));
            }
        }

        @Test
        @DisplayName("§10.3: アーカイブ済み・削除済み・承諾前（PROVISIONED）のチームは myTeams に出ない")
        void invalidTeamsExcluded() throws Exception {
            fx.seedAdminOfInvalidTeamsOnly();

            getAs(TeamAffiliationApiFixture.IA, fx.orgXSlug)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.myTeams", hasSize(0)));
        }

        @Test
        @DisplayName("AC-G116: myTeams の上限は100件")
        void myTeamsCappedAt100() throws Exception {
            for (int i = 0; i < 100; i++) {
                addTeamAdministeredByTa("cap");
            }
            em.flush();
            em.clear();

            getAs(TA, fx.orgXSlug)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.myTeams", hasSize(100)));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-G116 groups / AC-G111
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-G116/G111 groups と groupMode（実効値）")
    class Groups {

        @Test
        @DisplayName("AC-G116: groups は sortOrder の並び順どおりで、削除済みは出ない")
        void groupsInSortOrder() throws Exception {
            UUID third = fx.insertTeamGroup(fx.orgXId, "2022年度卒", "三番目", 3);
            UUID first = fx.insertTeamGroup(fx.orgXId, "2024年度卒", "一番目", 1);
            UUID second = fx.insertTeamGroup(fx.orgXId, "2023年度卒", null, 2);
            UUID deleted = fx.insertTeamGroup(fx.orgXId, "削除済み", null, 0);
            fx.softDeleteTeamGroup(deleted);
            fx.setOrgSettings(fx.orgXId, true, true, "OPTIONAL");

            getAs(TA, fx.orgXSlug)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.groupMode").value("OPTIONAL"))
                    .andExpect(jsonPath("$.data.groups", hasSize(3)))
                    .andExpect(jsonPath("$.data.groups[0].id").value(first.toString()))
                    .andExpect(jsonPath("$.data.groups[0].name").value("2024年度卒"))
                    .andExpect(jsonPath("$.data.groups[0].description").value("一番目"))
                    .andExpect(jsonPath("$.data.groups[1].id").value(second.toString()))
                    .andExpect(jsonPath("$.data.groups[2].id").value(third.toString()));
        }

        @Test
        @DisplayName("AC-G116: 保存値が OFF なら groups は空配列")
        void offMeansEmptyGroups() throws Exception {
            fx.insertTeamGroup(fx.orgXId, "2024年度卒", null, 1);
            fx.setOrgSettings(fx.orgXId, true, true, "OFF");

            getAs(TA, fx.orgXSlug)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.groupMode").value("OFF"))
                    .andExpect(jsonPath("$.data.groups", hasSize(0)));
        }

        @Test
        @DisplayName("AC-G111（実効値）: グループ機能 off の間は、保存値 REQUIRED でも groupMode=OFF・groups=[]")
        void groupsFeatureOffMeansEffectiveOff() throws Exception {
            fx.insertTeamGroup(fx.orgXId, "2024年度卒", null, 1);
            fx.setOrgSettings(fx.orgXId, true, false, "REQUIRED");

            getAs(TA, fx.orgXSlug)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.groupMode").value("OFF"))
                    .andExpect(jsonPath("$.data.groups", hasSize(0)));
        }

        @Test
        @DisplayName("AC-G112: REQUIRED のまま全グループが削除されていれば groupMode は実効 OPTIONAL")
        void requiredWithoutGroupsDowngraded() throws Exception {
            UUID g = fx.insertTeamGroup(fx.orgXId, "2024年度卒", null, 1);
            fx.softDeleteTeamGroup(g);
            fx.setOrgSettings(fx.orgXId, true, true, "REQUIRED");

            getAs(TA, fx.orgXSlug)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.groupMode").value("OPTIONAL"))
                    .andExpect(jsonPath("$.data.groups", hasSize(0)));
        }
    }
}
