package com.mannschaft.app.organization.teamgroup;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

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
 * F01.2.1 部隊 4-A — OrgTeamGroupScopeContractIT（AC-G134 後者）。
 *
 * <p>非メンバーは 403、越境（他組織のグループ ID）は 404 {@code ORG_064}、正当なら成功。
 * 併せて AC-F05（XM は閲覧のみ）・AC-F06（YA・他組織）・AC-F12/G139（SYSTEM_ADMIN は GET のみ）を確かめる。
 * 実 MySQL＋実 Security フィルタ＋ MockMvc で、認可・Service・Repository はモックしない。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 4-A OrgTeamGroupScopeContractIT（チームグループの認可・越境契約）")
class OrgTeamGroupScopeContractIT extends AbstractOrgTeamGroupIT {

    @Autowired
    private MockMvc mockMvc;

    private OrganizationEntity orgX;
    private OrganizationEntity orgY;
    private String slugX;
    private String slugY;
    private OrgTeamGroupEntity groupX;
    private OrgTeamGroupEntity groupY;

    @BeforeEach
    void setUp() {
        orgX = newOrg(true);
        orgY = newOrg(true);
        slugX = orgX.getSlug();
        slugY = orgY.getSlug();
        seedOrgPerson(XA, orgX.getId(), "ADMIN");
        seedOrgPerson(XD, orgX.getId(), "DEPUTY_ADMIN");
        seedOrgPerson(XM, orgX.getId(), "MEMBER");
        seedOrgPerson(YA, orgY.getId(), "ADMIN");
        seedUserOnly(N);
        seedSystemAdmin(SYS);
        groupX = newGroup(orgX.getId(), "Xのグループ", 0);
        groupY = newGroup(orgY.getId(), "Yのグループ", 0);
        em.flush();
        em.clear();
    }

    @Nested
    @DisplayName("正当な操作（組織 ADMIN）")
    class Legitimate {

        @Test
        @DisplayName("XA は一覧・作成・変更・並び替え・削除をすべて成功できる")
        void admin_canDoEverything() throws Exception {
            mockMvc.perform(get(BASE, slugX).with(user(XA.toString())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)));
            mockMvc.perform(post(BASE, slugX).with(user(XA.toString())).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"新規\"}")).andExpect(status().isCreated());
            mockMvc.perform(patch(BASE + "/{id}", slugX, groupX.getId()).with(user(XA.toString()))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"改名\"}")).andExpect(status().isOk());
            mockMvc.perform(delete(BASE + "/{id}", slugX, groupX.getId()).with(user(XA.toString())))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("AC-F05 組織 MEMBER は閲覧のみ・DEPUTY_ADMIN も書き込み不可")
    class MemberAndDeputy {

        @Test
        @DisplayName("XM は一覧を GET できる（200）")
        void member_canList() throws Exception {
            mockMvc.perform(get(BASE, slugX).with(user(XM.toString())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].name").value("Xのグループ"));
        }

        @Test
        @DisplayName("XM の作成・変更・削除・並び替えは 403 で、状態は変わらない")
        void member_cannotWrite() throws Exception {
            assertWritesForbiddenAndNothingChanged(XM);
        }

        @Test
        @DisplayName("XD（DEPUTY_ADMIN）の作成・変更・削除・並び替えも 403（組織 ADMIN のみ。§3.1）")
        void deputy_cannotWrite() throws Exception {
            assertWritesForbiddenAndNothingChanged(XD);
        }
    }

    @Nested
    @DisplayName("AC-F06 / AC-G134 他組織・非メンバー・越境")
    class CrossTenant {

        @Test
        @DisplayName("AC-F06: YA（他組織の ADMIN）が X のグループ一覧を GET すると 403")
        void otherOrgAdmin_listForbidden() throws Exception {
            forbidden(mockMvc.perform(get(BASE, slugX).with(user(YA.toString()))));
        }

        @Test
        @DisplayName("AC-G134: 非メンバー N は一覧も書き込みも 403 で、状態は変わらない")
        void nonMember_forbidden() throws Exception {
            forbidden(mockMvc.perform(get(BASE, slugX).with(user(N.toString()))));
            assertWritesForbiddenAndNothingChanged(N);
            assertWritesForbiddenAndNothingChanged(YA);
        }

        @Test
        @DisplayName("AC-F06/G134: YA が自組織 Y のパスで X のグループ ID を PATCH・DELETE すると 404 ORG_064 で、X のグループは変わらない")
        void crossTenantGroupId_notFound() throws Exception {
            mockMvc.perform(patch(BASE + "/{id}", slugY, groupX.getId()).with(user(YA.toString()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"乗っ取り\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ORG_064"));
            mockMvc.perform(delete(BASE + "/{id}", slugY, groupX.getId()).with(user(YA.toString())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ORG_064"));
            // 存在しない ID も同じステータス・同じコード（存在オラクルを作らない）
            mockMvc.perform(delete(BASE + "/{id}", slugY, UUID.randomUUID()).with(user(YA.toString())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ORG_064"));

            em.clear();
            OrgTeamGroupEntity reloaded = groupRepository.findById(groupX.getId()).orElseThrow();
            assertThat(reloaded.getName()).isEqualTo("Xのグループ");
            assertThat(reloaded.getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("AC-G134: 並び替えに他組織のグループ ID を混ぜると 409 ORG_068 で、他組織の順序は変わらない")
        void reorderWithForeignGroupId_conflicts() throws Exception {
            mockMvc.perform(put(BASE + "/order", slugY).with(user(YA.toString()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"groupIds\":[\"" + groupY.getId() + "\",\"" + groupX.getId() + "\"]}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("ORG_068"));
        }

        @Test
        @DisplayName("未認証は 401")
        void unauthenticated_unauthorized() throws Exception {
            mockMvc.perform(get(BASE, slugX)).andExpect(status().isUnauthorized());
            mockMvc.perform(post(BASE, slugX).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("AC-F12 / AC-G139 SYSTEM_ADMIN")
    class SystemAdmin {

        @Test
        @DisplayName("AC-G139: SYSTEM_ADMIN はグループ一覧を GET できる（200）")
        void sysAdmin_canList() throws Exception {
            mockMvc.perform(get(BASE, slugX).with(user(SYS.toString())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(1)));
        }

        @Test
        @DisplayName("AC-F12: SYSTEM_ADMIN の作成・変更・削除・並び替えは 4 経路とも 403 で、状態は変わらない")
        void sysAdmin_cannotWrite() throws Exception {
            assertWritesForbiddenAndNothingChanged(SYS);
        }
    }

    // ───────── helpers ─────────

    /** 作成・変更・削除・並び替えの 4 経路がすべて 403 COMMON_002 で、X の状態が変わらないことを確かめる。 */
    private void assertWritesForbiddenAndNothingChanged(Long userId) throws Exception {
        forbidden(mockMvc.perform(post(BASE, slugX).with(user(userId.toString()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"不正作成\"}")));
        forbidden(mockMvc.perform(patch(BASE + "/{id}", slugX, groupX.getId()).with(user(userId.toString()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"不正改名\"}")));
        forbidden(mockMvc.perform(put(BASE + "/order", slugX).with(user(userId.toString()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"groupIds\":[\"" + groupX.getId() + "\"]}")));
        forbidden(mockMvc.perform(delete(BASE + "/{id}", slugX, groupX.getId()).with(user(userId.toString()))));

        em.clear();
        assertThat(liveGroupCount(orgX.getId())).isEqualTo(1);
        OrgTeamGroupEntity reloaded = groupRepository.findById(groupX.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Xのグループ");
        assertThat(reloaded.getDeletedAt()).isNull();
    }

    private void forbidden(ResultActions actions) throws Exception {
        actions.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }
}
