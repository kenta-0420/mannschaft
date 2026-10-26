package com.mannschaft.app.role.controller;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 §9.2 #7（部隊 3-B）— {@code GET /api/v1/me/teams} の複数親組織対応の契約テスト（試練・先行 red）。
 *
 * <ul>
 *   <li>AC-N03・AC-C04(d): T が X と Y の両方に ACTIVE なら、{@code organizations} に両組織が出る。</li>
 *   <li>AC-N14: {@code organizationId}（非推奨の互換フィールド）は代表親組織（§9.3）に固定され、
 *       10 回呼んでも変わらない。</li>
 *   <li>AC-N05（代表親組織）: 代表親組織は「最初に成立した ACTIVE 加盟」。organization_id の大小や
 *       INSERT 順ではない。</li>
 *   <li>AC-G131: 親組織が 0 件のチームは {@code organizations = []}・{@code organizationId = null}。</li>
 * </ul>
 *
 * <p>実 Security（@WithMockUser）・実 MySQL。Repository・Service・Controller はモックしない。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 3-B /me/teams 複数親組織・代表親組織の契約")
class MeControllerMultiParentContractIT extends AbstractMySqlIntegrationTest {

    private static final long USER_ID = 3801L;

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    private Long orgX;
    private Long orgY;
    private String slugX;
    private String slugY;
    /** X（先に成立）と Y（後に成立）の両方に ACTIVE で加盟するチーム。 */
    private Long teamT;
    /** どの組織にも加盟していないチーム（G131）。 */
    private Long teamOrphan;

    @BeforeEach
    void setUp() {
        String sfx = String.valueOf(System.nanoTime());
        slugX = "me3b-x-" + sfx;
        slugY = "me3b-y-" + sfx;
        orgX = TeamOrgFixtureHelper.insertOrganization(em, "3B組織X " + sfx, slugX);
        orgY = TeamOrgFixtureHelper.insertOrganization(em, "3B組織Y " + sfx, slugY);
        teamT = TeamOrgFixtureHelper.insertTeam(em, "3BチームT " + sfx, "me3b-t-" + sfx);
        teamOrphan = TeamOrgFixtureHelper.insertTeam(em, "3Bチーム無所属 " + sfx, "me3b-o-" + sfx);

        // X を先に・Y を後に成立させる。INSERT 順も X→Y（PK は X が小さい）。
        // 旧実装の HashMap 後勝ちは Y に倒れるため、代表親組織=X の assert が red になる。
        LocalDateTime base = LocalDateTime.of(2026, 4, 1, 9, 0);
        TeamOrgFixtureHelper.insertTeamOrgMembership(em, teamT, orgX, "ACTIVE", base);
        TeamOrgFixtureHelper.insertTeamOrgMembership(em, teamT, orgY, "ACTIVE", base.plusDays(1));

        MembershipTestHelper.insertActiveUser(em, USER_ID);
        MembershipTestHelper.insertMembership(em, USER_ID, ScopeType.TEAM, teamT, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, USER_ID, ScopeType.TEAM, teamOrphan, RoleKind.MEMBER);
        em.flush();
        em.clear();
    }

    @Test
    @WithMockUser(username = "3801")
    @DisplayName("AC-N03・C04(d) T が X・Y の両方に ACTIVE なら organizations に両組織（id・slug・name）が出る")
    void 複数親組織が_organizations_に出る() throws Exception {
        mockMvc.perform(get("/api/v1/me/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + teamT + ")].organizations[*].id",
                        contains(orgX.intValue(), orgY.intValue())))
                .andExpect(jsonPath("$.data[?(@.id == " + teamT + ")].organizations[*].slug",
                        contains(slugX, slugY)))
                .andExpect(jsonPath("$.data[?(@.id == " + teamT + ")].organizations[*].name",
                        hasSize(2)));
    }

    @Test
    @WithMockUser(username = "3801")
    @DisplayName("AC-N05(代表)・N14 organizationId は代表親組織（最初に成立した加盟=X）で、PK 順・後勝ちではない")
    void organizationId_は代表親組織() throws Exception {
        mockMvc.perform(get("/api/v1/me/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + teamT + ")].organizationId",
                        contains(orgX.intValue())));
    }

    @Test
    @WithMockUser(username = "3801")
    @DisplayName("AC-N14 同じデータで /me/teams を10回呼んでも organizationId は毎回同じ（代表親組織）")
    void 十回呼んでもorganizationIdが変わらない() throws Exception {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            em.clear();
            MvcResult result = mockMvc.perform(get("/api/v1/me/teams"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id == " + teamT + ")].organizationId",
                            contains(orgX.intValue())))
                    .andReturn();
            seen.add(com.jayway.jsonpath.JsonPath
                    .read(result.getResponse().getContentAsString(),
                            "$.data[?(@.id == " + teamT + ")].organizationId")
                    .toString());
        }
        assertThat(seen).hasSize(1);
    }

    @Test
    @WithMockUser(username = "3801")
    @DisplayName("AC-G131 親組織が0件のチームは organizations=[]・organizationId=null（例外にならない）")
    void 親組織0件のチーム() throws Exception {
        mockMvc.perform(get("/api/v1/me/teams"))
                .andExpect(status().isOk())
                // organizations は「空配列」として存在する（欠落ではない）
                .andExpect(jsonPath("$.data[?(@.id == " + teamOrphan + ")].organizations", hasSize(1)))
                .andExpect(jsonPath("$.data[?(@.id == " + teamOrphan + ")].organizations[*]", hasSize(0)))
                .andExpect(jsonPath("$.data[?(@.id == " + teamOrphan + ")].organizationId",
                        not(hasItem(notNullValue()))));
    }

    @Test
    @WithMockUser(username = "3801")
    @DisplayName("PENDING の加盟は organizations にも代表親組織にも数えない（ACTIVE のみ）")
    void ACTIVE以外は含めない() throws Exception {
        String sfx = String.valueOf(System.nanoTime());
        Long orgZ = TeamOrgFixtureHelper.insertOrganization(em, "3B組織Z " + sfx, "me3b-z-" + sfx);
        // 最も古い responded_at を持つ PENDING。状態を見ずに時刻だけで選ぶ誤実装を弾く。
        TeamOrgFixtureHelper.insertTeamOrgMembership(
                em, teamT, orgZ, "PENDING", LocalDateTime.of(2026, 1, 1, 9, 0));
        em.flush();
        em.clear();

        mockMvc.perform(get("/api/v1/me/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + teamT + ")].organizations[*].id",
                        contains(orgX.intValue(), orgY.intValue())))
                .andExpect(jsonPath("$.data[?(@.id == " + teamT + ")].organizationId",
                        contains(orgX.intValue())));
    }
}
