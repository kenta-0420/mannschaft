package com.mannschaft.app.organization.teamaffiliation;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.N;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.SA;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.TA;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.XA;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.XD;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.XM;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.YA;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.normalize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 2-A — 申請受付・グループ設定 API（{@code GET/PUT /api/v1/organizations/{slug}/team-affiliation-settings}）の
 * 受け入れテスト（試練）。
 *
 * <p>正本: {@code docs/features/F01.2.1_org_team_groups.md} §5.5・§10.2・§3.1・§16 A。
 * 実 Security フィルタ（{@code addFilters=false} を付けない）・Testcontainers MySQL・実 Service / Repository を通し、
 * DB・認可・自分の Bean はモックしない。</p>
 *
 * <p>担当 AC: A01〜A06・A02（{@code GET /organizations/X} の {@code teamApplication}）・G112（REQUIRED の実効格下げ）・
 * G133（入力検証）・G139（SYSTEM_ADMIN は閲覧のみ）・G111（グループ off なら実効 OFF）。
 * 加えて、存在オラクル（非公開組織・存在しない slug が同じステータス・同じエラーコード・同じ本文）を固定する。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-A 申請受付設定 API 受け入れテスト")
class TeamAffiliationSettingsApiIT extends AbstractMySqlIntegrationTest {

    private static final String PATH = "/api/v1/organizations/{slug}/team-affiliation-settings";

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

    private ResultActions putAs(Long userId, String slug, String body) throws Exception {
        return mockMvc.perform(put(PATH, slug)
                .with(user(userId.toString()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String body(boolean enabled, boolean groupsEnabled, String mode, String guidance) {
        String g = guidance == null ? "null" : "\"" + guidance + "\"";
        return "{\"teamApplicationEnabled\":" + enabled + ",\"teamGroupsEnabled\":" + groupsEnabled
                + ",\"applicationGroupMode\":\"" + mode + "\",\"applicationGuidance\":" + g + "}";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> storedSettings(Long orgId) {
        em.flush();
        em.clear();
        Object[] row = (Object[]) em.createNativeQuery("SELECT team_application_enabled, team_groups_enabled, "
                        + "team_application_group_mode, team_application_guidance FROM organizations WHERE id = :id")
                .setParameter("id", orgId)
                .getSingleResult();
        return Map.of(
                "enabled", ((Number) toNumber(row[0])).intValue() == 1,
                "groupsEnabled", ((Number) toNumber(row[1])).intValue() == 1,
                "mode", row[2],
                "guidance", row[3] == null ? "<null>" : row[3]);
    }

    private static Object toNumber(Object value) {
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return value;
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A01 / AC-A02
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-A01: 新規作成した組織の設定を XA が GET すると既定値（受付 off・グループ off・OFF）が返る")
    void defaultSettings() throws Exception {
        getAs(XA, fx.orgXSlug)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teamApplicationEnabled").value(false))
                .andExpect(jsonPath("$.data.teamGroupsEnabled").value(false))
                .andExpect(jsonPath("$.data.applicationGroupMode").value("OFF"))
                .andExpect(jsonPath("$.data.effectiveApplicationGroupMode").value("OFF"))
                .andExpect(jsonPath("$.data.applicationGuidance").doesNotExist())
                .andExpect(jsonPath("$.data.pendingApplicationCount").value(0));
    }

    @Test
    @DisplayName("AC-A02: XA が受付 on を PUT すると 200 で GET と同じ形が返り、GET /organizations/X の teamApplication.enabled が true になる")
    void enableApplication() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{slug}", fx.orgXSlug).with(user(XA.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teamApplication.enabled").value(false));

        putAs(XA, fx.orgXSlug, body(true, false, "OFF", "卒業年度を書いてください"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teamApplicationEnabled").value(true))
                .andExpect(jsonPath("$.data.teamGroupsEnabled").value(false))
                .andExpect(jsonPath("$.data.applicationGroupMode").value("OFF"))
                .andExpect(jsonPath("$.data.effectiveApplicationGroupMode").value("OFF"))
                .andExpect(jsonPath("$.data.applicationGuidance").value("卒業年度を書いてください"))
                .andExpect(jsonPath("$.data.pendingApplicationCount").value(0));

        assertThat(storedSettings(fx.orgXId)).containsEntry("enabled", true);

        mockMvc.perform(get("/api/v1/organizations/{slug}", fx.orgXSlug).with(user(XM.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teamApplication.enabled").value(true));
    }

    @Test
    @DisplayName("§10.2: pendingApplicationCount は組織宛ての PENDING 申請（TEAM_APPLY）だけを数える")
    void pendingApplicationCountCountsOnlyTeamApplications() throws Exception {
        Long t2 = fx.insertTeam("申請中チーム2", fx.slug("aft2"));
        Long t3 = fx.insertTeam("招待中チーム3", fx.slug("aft3"));
        Long t4 = fx.insertTeam("加盟済みチーム4", fx.slug("aft4"));
        fx.insertTeamOrgMembership(fx.teamTId, fx.orgXId, "PENDING", "TEAM_APPLY");
        fx.insertTeamOrgMembership(t2, fx.orgXId, "PENDING", "TEAM_APPLY");
        fx.insertTeamOrgMembership(t3, fx.orgXId, "PENDING", "ORG_INVITE");
        fx.insertTeamOrgMembership(t4, fx.orgXId, "ACTIVE", "TEAM_APPLY");
        // 他組織宛ての申請は数えない
        fx.insertTeamOrgMembership(t4, fx.orgYId, "PENDING", "TEAM_APPLY");
        em.flush();
        em.clear();

        getAs(XA, fx.orgXSlug)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingApplicationCount").value(2));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A03 / AC-A04 / AC-G139
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-A03/A04/G139 認可")
    class Authorization {

        @Test
        @DisplayName("AC-A03: XD・XM・TA・YA が GET すると 403 COMMON_002")
        void nonAdminGetForbidden() throws Exception {
            for (Long actor : new Long[] {XD, XM, TA, YA, N}) {
                getAs(actor, fx.orgXSlug)
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value("COMMON_002"));
            }
        }

        @Test
        @DisplayName("AC-A03: XD・XM・TA・YA が PUT すると 403 COMMON_002 で、設定は変わらない")
        void nonAdminPutForbidden() throws Exception {
            for (Long actor : new Long[] {XD, XM, TA, YA, N}) {
                putAs(actor, fx.orgXSlug, body(true, true, "OPTIONAL", "x"))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value("COMMON_002"));
            }
            assertThat(storedSettings(fx.orgXId))
                    .containsEntry("enabled", false)
                    .containsEntry("groupsEnabled", false)
                    .containsEntry("mode", "OFF");
        }

        @Test
        @DisplayName("AC-A04: 未認証で GET/PUT すると 401")
        void unauthenticated() throws Exception {
            mockMvc.perform(get(PATH, fx.orgXSlug)).andExpect(status().isUnauthorized());
            mockMvc.perform(put(PATH, fx.orgXSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(true, false, "OFF", null)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("AC-G139: SYSTEM_ADMIN は設定を GET できる（200）が PUT は 403 で、設定は変わらない")
        void systemAdminReadOnly() throws Exception {
            getAs(SA, fx.orgXSlug)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.teamApplicationEnabled").value(false));
            putAs(SA, fx.orgXSlug, body(true, false, "OFF", null))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
            assertThat(storedSettings(fx.orgXId)).containsEntry("enabled", false);
        }

        @Test
        @DisplayName("AC-G139: SYSTEM_ADMIN は非公開組織の設定も GET できる（監査用）")
        void systemAdminCanReadPrivateOrg() throws Exception {
            getAs(SA, fx.orgPSlug).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 存在オラクル
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("存在オラクル（§10 共通事項: 存在と可視性 404 → 権限 403）")
    class ExistenceOracle {

        @Test
        @DisplayName("非公開組織に所属しない YA・N・TA が GET/PUT すると、存在しない slug と同じ 404・同じエラーコード・同じ本文")
        void privateOrgIsIndistinguishableFromAbsent() throws Exception {
            for (Long actor : new Long[] {YA, N, TA}) {
                MvcResult absentGet = getAs(actor, fx.absentSlug).andExpect(status().isNotFound()).andReturn();
                MvcResult privateGet = getAs(actor, fx.orgPSlug).andExpect(status().isNotFound()).andReturn();
                assertSameNotFound(absentGet, privateGet);

                MvcResult absentPut = putAs(actor, fx.absentSlug, body(true, false, "OFF", null))
                        .andExpect(status().isNotFound()).andReturn();
                MvcResult privatePut = putAs(actor, fx.orgPSlug, body(true, false, "OFF", null))
                        .andExpect(status().isNotFound()).andReturn();
                assertSameNotFound(absentPut, privatePut);
            }
            assertThat(storedSettings(fx.orgPId)).containsEntry("enabled", false);
        }

        @Test
        @DisplayName("非公開組織の権限なしユーザーへの応答は、入力が不正でも 404（入力検証より先に可視性で落とす）")
        void privateOrgHidesValidationErrors() throws Exception {
            MvcResult absent = putAs(YA, fx.absentSlug, "{}").andExpect(status().isNotFound()).andReturn();
            MvcResult priv = putAs(YA, fx.orgPSlug, "{}").andExpect(status().isNotFound()).andReturn();
            assertSameNotFound(absent, priv);
        }

        private void assertSameNotFound(MvcResult absent, MvcResult priv) throws Exception {
            String absentBody = absent.getResponse().getContentAsString();
            String privateBody = priv.getResponse().getContentAsString();
            assertThat(absentBody).contains("ORG_001");
            assertThat(normalize(privateBody, fx.orgPSlug))
                    .as("非公開組織の応答は存在しない slug の応答と区別できてはならない")
                    .isEqualTo(normalize(absentBody, fx.absentSlug));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A05 / AC-A06 / AC-G112 / AC-G111
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-A05/A06/G112/G111 グループ選択モード")
    class GroupMode {

        @Test
        @DisplayName("AC-A05: グループ機能 off のまま REQUIRED を PUT すると 422 ORG_070 で、設定は変わらない")
        void requiredWithGroupsOff() throws Exception {
            fx.insertTeamGroup(fx.orgXId, "2024年度卒", null, 1);
            em.flush();
            em.clear();

            putAs(XA, fx.orgXSlug, body(true, false, "REQUIRED", "変わらない"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("ORG_070"));

            assertThat(storedSettings(fx.orgXId))
                    .containsEntry("enabled", false)
                    .containsEntry("groupsEnabled", false)
                    .containsEntry("mode", "OFF")
                    .containsEntry("guidance", "<null>");
        }

        @Test
        @DisplayName("AC-A06: グループ機能 on・生存グループ0件（削除済みのみ）で REQUIRED を PUT すると 422 ORG_070")
        void requiredWithNoLiveGroups() throws Exception {
            UUID deleted = fx.insertTeamGroup(fx.orgXId, "削除済み", null, 1);
            fx.softDeleteTeamGroup(deleted);

            putAs(XA, fx.orgXSlug, body(true, true, "REQUIRED", null))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("ORG_070"));

            assertThat(storedSettings(fx.orgXId))
                    .containsEntry("groupsEnabled", false)
                    .containsEntry("mode", "OFF");
        }

        @Test
        @DisplayName("§5.5: グループ機能 on・生存グループ1件以上なら REQUIRED を保存でき、実効値も REQUIRED")
        void requiredAccepted() throws Exception {
            fx.insertTeamGroup(fx.orgXId, "2024年度卒", null, 1);
            em.flush();
            em.clear();

            putAs(XA, fx.orgXSlug, body(true, true, "REQUIRED", null))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.applicationGroupMode").value("REQUIRED"))
                    .andExpect(jsonPath("$.data.effectiveApplicationGroupMode").value("REQUIRED"));
            assertThat(storedSettings(fx.orgXId)).containsEntry("mode", "REQUIRED");
        }

        @Test
        @DisplayName("AC-G112: REQUIRED のまま全グループを削除すると、GET の effectiveApplicationGroupMode が OPTIONAL に落ちる（保存値は REQUIRED のまま）")
        void requiredDowngradedWhenAllGroupsDeleted() throws Exception {
            UUID g = fx.insertTeamGroup(fx.orgXId, "2024年度卒", null, 1);
            fx.setOrgSettings(fx.orgXId, true, true, "REQUIRED");
            getAs(XA, fx.orgXSlug)
                    .andExpect(jsonPath("$.data.effectiveApplicationGroupMode").value("REQUIRED"));

            fx.softDeleteTeamGroup(g);

            getAs(XA, fx.orgXSlug)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.applicationGroupMode").value("REQUIRED"))
                    .andExpect(jsonPath("$.data.effectiveApplicationGroupMode").value("OPTIONAL"));
        }

        @Test
        @DisplayName("AC-G111（実効値）: グループ機能 off の間は、保存値が OPTIONAL でも実効値は OFF（保存値は変えない）")
        void groupsOffMeansEffectiveOff() throws Exception {
            fx.insertTeamGroup(fx.orgXId, "2024年度卒", null, 1);
            fx.setOrgSettings(fx.orgXId, true, false, "OPTIONAL");

            getAs(XA, fx.orgXSlug)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.applicationGroupMode").value("OPTIONAL"))
                    .andExpect(jsonPath("$.data.effectiveApplicationGroupMode").value("OFF"));
        }

        @Test
        @DisplayName("§4.4: グループ機能を off にして PUT しても、保存済みのグループは消えない")
        void turningGroupsOffKeepsGroups() throws Exception {
            fx.insertTeamGroup(fx.orgXId, "2024年度卒", null, 1);
            fx.setOrgSettings(fx.orgXId, true, true, "OPTIONAL");

            putAs(XA, fx.orgXSlug, body(true, false, "OPTIONAL", null))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.teamGroupsEnabled").value(false))
                    .andExpect(jsonPath("$.data.effectiveApplicationGroupMode").value("OFF"));

            em.flush();
            em.clear();
            Number live = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM org_team_groups WHERE organization_id = :o AND deleted_at IS NULL")
                    .setParameter("o", fx.orgXId)
                    .getSingleResult();
            assertThat(live.longValue()).isEqualTo(1L);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-G133 入力検証
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-G133 入力検証")
    class Validation {

        @Test
        @DisplayName("AC-G133: 必須項目（teamApplicationEnabled / teamGroupsEnabled / applicationGroupMode）が欠けていれば 400")
        void missingRequiredFields() throws Exception {
            String[] bodies = {
                    "{\"teamGroupsEnabled\":false,\"applicationGroupMode\":\"OFF\"}",
                    "{\"teamApplicationEnabled\":true,\"applicationGroupMode\":\"OFF\"}",
                    "{\"teamApplicationEnabled\":true,\"teamGroupsEnabled\":false}",
                    "{}"
            };
            for (String b : bodies) {
                putAs(XA, fx.orgXSlug, b).andExpect(status().isBadRequest());
            }
            assertThat(storedSettings(fx.orgXId)).containsEntry("enabled", false);
        }

        @Test
        @DisplayName("AC-G133: applicationGuidance が 501 文字なら 400、500 文字なら 200")
        void guidanceLength() throws Exception {
            putAs(XA, fx.orgXSlug, body(true, false, "OFF", "あ".repeat(501)))
                    .andExpect(status().isBadRequest());
            assertThat(storedSettings(fx.orgXId)).containsEntry("enabled", false);

            putAs(XA, fx.orgXSlug, body(true, false, "OFF", "あ".repeat(500)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.applicationGuidance").value("あ".repeat(500)));
        }

        @Test
        @DisplayName("AC-G133: applicationGroupMode に未知の値を送ると 400")
        void unknownMode() throws Exception {
            putAs(XA, fx.orgXSlug, body(true, false, "SOMETIMES", null))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("§10 共通事項: 空文字の案内文は null として保存する")
        void blankGuidanceIsNull() throws Exception {
            putAs(XA, fx.orgXSlug, body(true, false, "OFF", ""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.applicationGuidance").doesNotExist());
            assertThat(storedSettings(fx.orgXId)).containsEntry("guidance", "<null>");
        }
    }
}
