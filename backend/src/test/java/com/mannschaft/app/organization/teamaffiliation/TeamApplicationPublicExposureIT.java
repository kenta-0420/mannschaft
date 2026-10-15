package com.mannschaft.app.organization.teamaffiliation;

import com.jayway.jsonpath.JsonPath;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.normalize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 2-A — 公開 API への受付フラグの露出（{@code GET /api/v1/public/organizations/{slug}} の
 * {@code acceptingTeamApplications}、{@code GET /api/v1/public/organizations/search} のクエリと各要素）の
 * 受け入れテスト（試練）。
 *
 * <p>正本: {@code docs/features/F01.2.1_org_team_groups.md} §10.1 既存 API の拡張・§10.3・§16 A。
 * 未ログイン（認証なし）で実 Security フィルタを通す。</p>
 *
 * <p>担当 AC: A12（公開 API は受付 off の公開組織に false を返し、非公開・存在しない組織は受付フラグの有無に
 * かかわらず従来と同じ不在応答）・A10(API)（{@code acceptingTeamApplications=true} で受付中の組織だけに絞り、
 * 各要素に受付フラグが付く）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-A 公開 API の受付フラグ 受け入れテスト")
class TeamApplicationPublicExposureIT extends AbstractMySqlIntegrationTest {

    private static final String DETAIL = "/api/v1/public/organizations/{slug}";
    private static final String SEARCH = "/api/v1/public/organizations/search";

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    private TeamAffiliationApiFixture fx;

    @BeforeEach
    void setUp() {
        fx = new TeamAffiliationApiFixture(em).seed();
    }

    @Test
    @DisplayName("AC-A12: 公開 API は受付 off の公開組織に acceptingTeamApplications=false、受付 on なら true を返す")
    void publicDetailCarriesFlag() throws Exception {
        mockMvc.perform(get(DETAIL, fx.orgXSlug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptingTeamApplications").value(false));

        fx.setOrgSettings(fx.orgXId, true, false, "OFF");

        mockMvc.perform(get(DETAIL, fx.orgXSlug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptingTeamApplications").value(true));
    }

    @Test
    @DisplayName("AC-A12: 非公開組織は受付 on/off のどちらでも、存在しない slug と同じ不在応答（PUBLIC_001・同じ本文）")
    void privateOrgNotRevealedByFlag() throws Exception {
        String absentBody = mockMvc.perform(get(DETAIL, fx.absentSlug))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(absentBody).contains("PUBLIC_001");

        String closedBody = mockMvc.perform(get(DETAIL, fx.orgPSlug))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        fx.setOrgSettings(fx.orgPId, true, false, "OFF");
        String openBody = mockMvc.perform(get(DETAIL, fx.orgPSlug))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(normalize(closedBody, fx.orgPSlug)).isEqualTo(normalize(absentBody, fx.absentSlug));
        assertThat(normalize(openBody, fx.orgPSlug)).isEqualTo(normalize(absentBody, fx.absentSlug));
    }

    @Test
    @DisplayName("AC-A10(API): acceptingTeamApplications=true で受付 on の公開組織だけに絞られ、各要素に受付フラグが付く。非公開は出ない")
    void searchFiltersByAcceptingFlag() throws Exception {
        // 検索語で本テストの組織だけに絞る（共有 DB に他テストの組織が残っていても影響しない）
        String keyword = "加盟受付テスト";
        fx.setOrgSettings(fx.orgXId, true, false, "OFF");
        fx.setOrgSettings(fx.orgPId, true, false, "OFF");
        // 組織Y は受付 off のまま

        MvcResult all = mockMvc.perform(get(SEARCH).param("keyword", keyword).param("size", "100"))
                .andExpect(status().isOk()).andReturn();
        String allJson = all.getResponse().getContentAsString();
        List<String> allSlugs = JsonPath.read(allJson, "$.content[*].slug");
        assertThat(allSlugs).contains(fx.orgXSlug, fx.orgYSlug).doesNotContain(fx.orgPSlug);
        List<Boolean> xFlag = JsonPath.read(allJson,
                "$.content[?(@.slug == '" + fx.orgXSlug + "')].acceptingTeamApplications");
        List<Boolean> yFlag = JsonPath.read(allJson,
                "$.content[?(@.slug == '" + fx.orgYSlug + "')].acceptingTeamApplications");
        assertThat(xFlag).containsExactly(true);
        assertThat(yFlag).containsExactly(false);

        MvcResult accepting = mockMvc.perform(get(SEARCH)
                        .param("keyword", keyword)
                        .param("size", "100")
                        .param("acceptingTeamApplications", "true"))
                .andExpect(status().isOk()).andReturn();
        String acceptingJson = accepting.getResponse().getContentAsString();
        List<String> acceptingSlugs = JsonPath.read(acceptingJson, "$.content[*].slug");
        assertThat(acceptingSlugs).contains(fx.orgXSlug).doesNotContain(fx.orgYSlug, fx.orgPSlug);
        List<Boolean> flags = JsonPath.read(acceptingJson, "$.content[*].acceptingTeamApplications");
        assertThat(flags).isNotEmpty().allMatch(Boolean.TRUE::equals);
    }

    @Test
    @DisplayName("AC-A10(API): acceptingTeamApplications=false または省略なら絞り込まない")
    void searchWithoutFilter() throws Exception {
        fx.setOrgSettings(fx.orgXId, true, false, "OFF");
        String keyword = "加盟受付テスト";

        MvcResult r = mockMvc.perform(get(SEARCH)
                        .param("keyword", keyword)
                        .param("size", "100")
                        .param("acceptingTeamApplications", "false"))
                .andExpect(status().isOk()).andReturn();
        List<String> slugs = JsonPath.read(r.getResponse().getContentAsString(), "$.content[*].slug");
        assertThat(slugs).contains(fx.orgXSlug, fx.orgYSlug);
    }
}
