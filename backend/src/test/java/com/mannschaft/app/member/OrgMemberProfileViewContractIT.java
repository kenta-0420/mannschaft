package com.mannschaft.app.member;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.member.entity.MemberProfileEntity;
import com.mannschaft.app.member.entity.MemberSubtabRoleVisibilityEntity;
import com.mannschaft.app.member.entity.TeamPageEntity;
import com.mannschaft.app.member.entity.TeamPageSectionEntity;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import com.mannschaft.app.member.repository.MemberSubtabRoleVisibilityRepository;
import com.mannschaft.app.member.repository.TeamPageRepository;
import com.mannschaft.app.member.repository.TeamPageSectionRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR #3387 試練（判定分離）— 組織スコープのメンバー紹介 API を実 DB で確かめる結合テスト。
 *
 * <p>正本: 軍議書 gungi-3387.md（■ 確定事項を優先）。金型: {@link MemberScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL + 手動 SecurityContext）。</p>
 *
 * <p>判定の分け方:</p>
 * <ul>
 *   <li>閲覧（V1 getPage・V2 listSections・V3 listProfiles・V4 getProfile）は「紹介」サブタブの
 *       最低ロール（min_role）による緩和が効く。</li>
 *   <li>操作（O1 lookup・O2 copy-members のコピー元）は緩和が効かない。O1 は MEMBER 以上、O2 は
 *       コピー先とコピー元が同じスコープで、かつコピー元に MEMBER 以上。</li>
 * </ul>
 *
 * <p>ロール: OUT=組織B の MEMBER で組織A 未所属、SUP=組織A の SUPPORTER、MEM=組織A の MEMBER、
 * DEP=組織A の DEPUTY_ADMIN、ADM=組織A の ADMIN、SYS=所属のない SYSTEM_ADMIN。</p>
 *
 * <p>注意（既知の地雷・{@link MemberScopeContractIT}:121）: isAdminOrAbove は user_roles、isMember は
 * memberships を見る別系統のため、ADMIN/DEPUTY_ADMIN にも memberships 行を張る。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR #3387 試練: 組織スコープのメンバー紹介 閲覧／操作の認可契約（実 DB）")
class OrgMemberProfileViewContractIT extends AbstractMySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TeamPageRepository pageRepository;
    @Autowired private TeamPageSectionRepository sectionRepository;
    @Autowired private MemberProfileRepository profileRepository;
    @Autowired private MemberSubtabRoleVisibilityRepository subtabRepository;
    @Autowired private AccessControlService accessControlService;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long orgBId;
    private String orgASlug;

    private Long outId;  // 組織B の MEMBER・組織A 未所属
    private Long supId;  // 組織A の SUPPORTER
    private Long memId;  // 組織A の MEMBER
    private Long depId;  // 組織A の DEPUTY_ADMIN
    private Long admId;  // 組織A の ADMIN（コピー先の管理者）
    private Long sysId;  // 所属のない SYSTEM_ADMIN

    private Long pubAId;   // 組織A: PUBLISHED + PUBLIC
    private Long moAId;    // 組織A: PUBLISHED + MEMBERS_ONLY
    private Long drAId;    // 組織A: DRAFT
    private Long emptyAId; // 組織A: PUBLISHED + PUBLIC、セクション0件・表示中プロフィール0件（非表示1件のみ）
    private Long tgtAId;   // 組織A: コピー先（空）
    private Long pubBId;   // 組織B: PUBLISHED + PUBLIC

    private Long visAId;   // pubA の表示中
    private Long hidAId;   // pubA の非表示
    private Long visMoId;  // moA の表示中
    private Long hidMoId;  // moA の非表示
    private Long visDrId;  // drA の表示中
    private Long hidDrId;  // drA の非表示

    @BeforeEach
    void setUp() {
        orgAId = insertOrganization("ORGV 組織A");
        orgBId = insertOrganization("ORGV 組織B");
        orgASlug = (String) em.createNativeQuery("SELECT slug FROM organizations WHERE id = :id")
                .setParameter("id", orgAId).getSingleResult();

        outId = insertUser("orgv-out@example.com");
        supId = insertUser("orgv-sup@example.com");
        memId = insertUser("orgv-mem@example.com");
        depId = insertUser("orgv-dep@example.com");
        admId = insertUser("orgv-adm@example.com");
        sysId = insertUser("orgv-sys@example.com");

        MembershipTestHelper.insertMembership(em, outId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, supId, ScopeType.ORGANIZATION, orgAId, RoleKind.SUPPORTER);
        MembershipTestHelper.insertMembership(em, memId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, depId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, depId, "DEPUTY_ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, admId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, admId, "ADMIN", null, orgAId);
        // 所属のない SYSTEM_ADMIN（プラットフォームロール: team_id・organization_id とも NULL）
        MembershipTestHelper.insertUserRole(em, sysId, "SYSTEM_ADMIN", null, null);

        pubAId = savePage(orgAId, "ORGV 公開A", PageStatus.PUBLISHED, PageVisibility.PUBLIC);
        moAId = savePage(orgAId, "ORGV 会員限定A", PageStatus.PUBLISHED, PageVisibility.MEMBERS_ONLY);
        drAId = savePage(orgAId, "ORGV 下書きA", PageStatus.DRAFT, PageVisibility.PUBLIC);
        emptyAId = savePage(orgAId, "ORGV 空A", PageStatus.PUBLISHED, PageVisibility.PUBLIC);
        tgtAId = savePage(orgAId, "ORGV コピー先A", PageStatus.DRAFT, PageVisibility.MEMBERS_ONLY);
        pubBId = savePage(orgBId, "ORGV 公開B", PageStatus.PUBLISHED, PageVisibility.PUBLIC);

        sectionRepository.save(TeamPageSectionEntity.builder()
                .teamPageId(pubAId).sectionType(SectionType.HEADING).title("ORGV 見出しA").build());
        sectionRepository.save(TeamPageSectionEntity.builder()
                .teamPageId(moAId).sectionType(SectionType.HEADING).title("ORGV 見出しMO").build());
        sectionRepository.save(TeamPageSectionEntity.builder()
                .teamPageId(drAId).sectionType(SectionType.HEADING).title("ORGV 見出しDR").build());

        visAId = saveProfile(pubAId, "ORGV 選手 表示A", true, 0);
        hidAId = saveProfile(pubAId, "ORGV 選手 非表示A", false, 1);
        visMoId = saveProfile(moAId, "ORGV 選手 表示MO", true, 0);
        hidMoId = saveProfile(moAId, "ORGV 選手 非表示MO", false, 1);
        visDrId = saveProfile(drAId, "ORGV 選手 表示DR", true, 0);
        hidDrId = saveProfile(drAId, "ORGV 選手 非表示DR", false, 1);
        saveProfile(emptyAId, "ORGV 選手 非表示空", false, 0);
        saveProfile(pubBId, "ORGV 選手 表示B", true, 0);
        saveProfile(pubBId, "ORGV 選手 非表示B", false, 1);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 0. フィクスチャの自己検証
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("前提: 所属のない SYSTEM_ADMIN を insertUserRole(SYSTEM_ADMIN,null,null) で作れる／各ロールの判定が意図どおり")
    void フィクスチャの自己検証() {
        assertThat(accessControlService.isSystemAdmin(sysId)).isTrue();
        assertThat(accessControlService.isMember(sysId, orgAId, "ORGANIZATION")).isFalse();
        assertThat(accessControlService.isAdminOrAbove(sysId, orgAId, "ORGANIZATION")).isFalse();
        assertThat(accessControlService.isMember(outId, orgAId, "ORGANIZATION")).isFalse();
        assertThat(accessControlService.isMember(supId, orgAId, "ORGANIZATION")).isTrue();
        assertThat(accessControlService.hasRoleOrAbove(supId, orgAId, "ORGANIZATION", "MEMBER")).isFalse();
        assertThat(accessControlService.hasRoleOrAbove(memId, orgAId, "ORGANIZATION", "MEMBER")).isTrue();
        assertThat(accessControlService.isAdminOrAbove(depId, orgAId, "ORGANIZATION")).isTrue();
        assertThat(accessControlService.isAdminOrAbove(admId, orgAId, "ORGANIZATION")).isTrue();
        assertThat(accessControlService.isAdminOrAbove(admId, orgBId, "ORGANIZATION")).isFalse();
    }

    // ═════════════════════════════════════════════════════════════════════
    // B. 閲覧経路（V1〜V4）では緩和が効く
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-02/03: OUT × サブタブ PUBLIC × PUB")
    class OutsiderPublicSubtabPublishedPublic {

        @BeforeEach
        void subtab() {
            setProfilesSubtab(orgAId, MinRole.PUBLIC);
            setAuth(outId);
        }

        @Test
        @DisplayName("AC-02 V1: 200・members は表示中のみ")
        void AC02_V1_200_表示中のみ() throws Exception {
            getPage(pubAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.members.length()").value(1))
                    .andExpect(jsonPath("$.data.members[0].id").value(visAId));
        }

        @Test
        @DisplayName("AC-02 V2: 200")
        void AC02_V2_200() throws Exception {
            listSections(pubAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("AC-02 V3: 200・is_visible=true の行のみ")
        void AC02_V3_200_表示中のみ() throws Exception {
            listProfiles(pubAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(visAId));
        }

        @Test
        @DisplayName("AC-02 V4: 表示中プロフィールは 200")
        void AC02_V4_表示中は200() throws Exception {
            getProfile(visAId).andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-03 V4: 非表示プロフィールは 404(MEMBER_003)")
        void AC03_V4_非表示は404_003() throws Exception {
            expect404(getProfile(hidAId), "MEMBER_003");
        }
    }

    @Nested
    @DisplayName("AC-04: OUT × サブタブ PUBLIC × MEMBERS_ONLY → V1〜V4 すべて 404")
    class OutsiderPublicSubtabMembersOnly {

        @BeforeEach
        void subtab() {
            setProfilesSubtab(orgAId, MinRole.PUBLIC);
            setAuth(outId);
        }

        @Test
        @DisplayName("AC-04 V1: 404(MEMBER_001)")
        void AC04_V1_404() throws Exception {
            expect404(getPage(moAId), "MEMBER_001");
        }

        @Test
        @DisplayName("AC-04 V2: 404(MEMBER_001)")
        void AC04_V2_404() throws Exception {
            expect404(listSections(moAId), "MEMBER_001");
        }

        @Test
        @DisplayName("AC-04 V3: 404(MEMBER_001)")
        void AC04_V3_404() throws Exception {
            expect404(listProfiles(moAId), "MEMBER_001");
        }

        @Test
        @DisplayName("AC-04 V4: 表示中プロフィールでもページ判定で 404(MEMBER_001)")
        void AC04_V4_404_001() throws Exception {
            expect404(getProfile(visMoId), "MEMBER_001");
        }
    }

    @Nested
    @DisplayName("AC-05: OUT × サブタブ既定（MEMBER）× PUB → V1〜V3 404")
    class OutsiderDefaultSubtab {

        @BeforeEach
        void auth() {
            setAuth(outId); // サブタブ行なし＝既定 MEMBER
        }

        @Test
        @DisplayName("AC-05 V1: 404(MEMBER_001)")
        void AC05_V1_404() throws Exception {
            expect404(getPage(pubAId), "MEMBER_001");
        }

        @Test
        @DisplayName("AC-05 V2: 404(MEMBER_001)")
        void AC05_V2_404() throws Exception {
            expect404(listSections(pubAId), "MEMBER_001");
        }

        @Test
        @DisplayName("AC-05 V3: 404(MEMBER_001)")
        void AC05_V3_404() throws Exception {
            expect404(listProfiles(pubAId), "MEMBER_001");
        }
    }

    @Nested
    @DisplayName("AC-06/24: サブタブ SUPPORTER の境界")
    class SupporterSubtab {

        @BeforeEach
        void subtab() {
            setProfilesSubtab(orgAId, MinRole.SUPPORTER);
        }

        @Test
        @DisplayName("AC-06/24 SUP × PUB V1: 200")
        void AC06_SUP_V1_200() throws Exception {
            setAuth(supId);
            getPage(pubAId).andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-06 SUP × PUB V3: 200・表示中のみ")
        void AC06_SUP_V3_200() throws Exception {
            setAuth(supId);
            listProfiles(pubAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("AC-06 SUP × MEMBERS_ONLY V1: 404(MEMBER_001)")
        void AC06_SUP_会員限定_V1_404() throws Exception {
            setAuth(supId);
            expect404(getPage(moAId), "MEMBER_001");
        }

        @Test
        @DisplayName("AC-24 OUT × PUB V1: 404(MEMBER_001)（サブタブ SUPPORTER は非会員を通さない）")
        void AC24_OUT_V1_404() throws Exception {
            setAuth(outId);
            expect404(getPage(pubAId), "MEMBER_001");
        }
    }

    @Nested
    @DisplayName("AC-07/24: サブタブ既定（MEMBER）の境界")
    class DefaultSubtabBoundary {

        @Test
        @DisplayName("AC-07/24 SUP × PUB V1: 404(MEMBER_001)（F06.6 §6）")
        void AC07_SUP_V1_404() throws Exception {
            setAuth(supId);
            expect404(getPage(pubAId), "MEMBER_001");
        }

        @Test
        @DisplayName("AC-24 MEM × PUB V1: 200")
        void AC24_MEM_V1_200() throws Exception {
            setAuth(memId);
            getPage(pubAId).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("AC-08: MEM × 既定 × MEMBERS_ONLY")
    class MemberMembersOnly {

        @BeforeEach
        void auth() {
            setAuth(memId);
        }

        @Test
        @DisplayName("AC-08 V1: 200")
        void AC08_V1_200() throws Exception {
            getPage(moAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.members.length()").value(1));
        }

        @Test
        @DisplayName("AC-08 V2: 200")
        void AC08_V2_200() throws Exception {
            listSections(moAId).andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-08 V3: 200・表示中のみ")
        void AC08_V3_200_表示中のみ() throws Exception {
            listProfiles(moAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(visMoId));
        }

        @Test
        @DisplayName("AC-08 V4: 表示中は 200")
        void AC08_V4_表示中200() throws Exception {
            getProfile(visMoId).andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-08 V4: 非表示は 404(MEMBER_003)")
        void AC08_V4_非表示404_003() throws Exception {
            expect404(getProfile(hidMoId), "MEMBER_003");
        }
    }

    @Nested
    @DisplayName("AC-09: MEM × DRAFT → V1〜V4 404(MEMBER_001)")
    class MemberDraft {

        @BeforeEach
        void auth() {
            setAuth(memId);
        }

        @Test
        @DisplayName("AC-09 V1: 404(MEMBER_001)")
        void AC09_V1_404() throws Exception {
            expect404(getPage(drAId), "MEMBER_001");
        }

        @Test
        @DisplayName("AC-09 V2: 404(MEMBER_001)")
        void AC09_V2_404() throws Exception {
            expect404(listSections(drAId), "MEMBER_001");
        }

        @Test
        @DisplayName("AC-09 V3: 404(MEMBER_001)")
        void AC09_V3_404() throws Exception {
            expect404(listProfiles(drAId), "MEMBER_001");
        }

        @Test
        @DisplayName("AC-09 V4: 下書き配下の表示中プロフィールも 404(MEMBER_001)")
        void AC09_V4_404_001() throws Exception {
            expect404(getProfile(visDrId), "MEMBER_001");
        }
    }

    @Nested
    @DisplayName("AC-10: DEP・ADM は DRAFT でも V1〜V4 200（V3 は非表示込み、V4 は非表示も 200）")
    class AdminsSeeEverything {

        @Test
        @DisplayName("AC-10 ADM V1 DRAFT: 200")
        void AC10_ADM_V1_200() throws Exception {
            setAuth(admId);
            getPage(drAId).andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-10 ADM V2 DRAFT: 200")
        void AC10_ADM_V2_200() throws Exception {
            setAuth(admId);
            listSections(drAId).andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-10 ADM V3 DRAFT: 200・非表示込みの全件（2件）")
        void AC10_ADM_V3_全件() throws Exception {
            setAuth(admId);
            listProfiles(drAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2));
        }

        @Test
        @DisplayName("AC-10 ADM V4 DRAFT: 非表示も 200")
        void AC10_ADM_V4_非表示200() throws Exception {
            setAuth(admId);
            getProfile(hidDrId).andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-10 DEP V1 DRAFT: 200")
        void AC10_DEP_V1_200() throws Exception {
            setAuth(depId);
            getPage(drAId).andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-10 DEP V3 DRAFT: 200・非表示込みの全件（2件）")
        void AC10_DEP_V3_全件() throws Exception {
            setAuth(depId);
            listProfiles(drAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2));
        }

        @Test
        @DisplayName("AC-10 DEP V4 DRAFT: 非表示も 200")
        void AC10_DEP_V4_非表示200() throws Exception {
            setAuth(depId);
            getProfile(hidDrId).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("AC-11: 所属のない SYS は DRAFT・MEMBERS_ONLY・非表示のいずれでも 200")
    class SystemAdminSeesEverything {

        @BeforeEach
        void auth() {
            setAuth(sysId);
        }

        @Test
        @DisplayName("AC-11 V1 DRAFT: 200")
        void AC11_V1_下書き200() throws Exception {
            getPage(drAId).andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-11 V1 MEMBERS_ONLY: 200")
        void AC11_V1_会員限定200() throws Exception {
            getPage(moAId).andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-11 V2 DRAFT: 200")
        void AC11_V2_下書き200() throws Exception {
            listSections(drAId).andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-11 V3 MEMBERS_ONLY: 200・全件（2件）")
        void AC11_V3_全件() throws Exception {
            listProfiles(moAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2));
        }

        @Test
        @DisplayName("AC-11 V4 非表示: 200")
        void AC11_V4_非表示200() throws Exception {
            getProfile(hidMoId).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // C. 操作経路（O1 lookup・O2 copy-members）では緩和が効かない
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-12/13: O1 lookup は MEMBER 以上（裁可2）")
    class Lookup {

        @Test
        @DisplayName("AC-12 OUT × サブタブ PUBLIC × PUB: 404(MEMBER_001)（HEAD では通る＝red）")
        void AC12_OUT_サブタブPUBLIC_404() throws Exception {
            setProfilesSubtab(orgAId, MinRole.PUBLIC);
            setAuth(outId);
            expect404(lookup(pubAId, "ORGV", 10), "MEMBER_001");
        }

        @Test
        @DisplayName("AC-13 SUP × サブタブ SUPPORTER × PUB: 404(MEMBER_001)（HEAD では通る＝red）")
        void AC13_SUP_サブタブSUPPORTER_404() throws Exception {
            setProfilesSubtab(orgAId, MinRole.SUPPORTER);
            setAuth(supId);
            expect404(lookup(pubAId, "ORGV", 10), "MEMBER_001");
        }

        @Test
        @DisplayName("AC-13 MEM × PUB: 200・表示中のみ")
        void AC13_MEM_200_表示中のみ() throws Exception {
            setAuth(memId);
            lookup(pubAId, "ORGV", 10).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].memberProfileId").value(visAId));
        }

        @Test
        @DisplayName("AC-13 所属のない SYS × DRAFT: 200（hasRoleOrAbove が SYSTEM_ADMIN を通す）")
        void AC13_SYS_200() throws Exception {
            setAuth(sysId);
            lookup(drAId, "ORGV", 10).andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-28 O1 MEM × 一致0件: 200・空配列")
        void AC28_O1_一致0件_空配列() throws Exception {
            setAuth(memId);
            lookup(pubAId, "該当なしORGV", 10).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    @Nested
    @DisplayName("AC-14〜16/23/29: O2 copy-members のコピー元（裁可1: 同一スコープ必須 + MEMBER 以上）")
    class CopyMembers {

        @Test
        @DisplayName("AC-14 ADM(組織B では OUT) × 組織B サブタブ PUBLIC × PUB: 404(MEMBER_001)・コピー先0件（HEAD では通る＝red）")
        void AC14_越境_非会員_404() throws Exception {
            setProfilesSubtab(orgBId, MinRole.PUBLIC);
            setAuth(admId);
            expect404(copy(tgtAId, pubBId), "MEMBER_001");
            assertThat(countProfiles(tgtAId)).isZero();
        }

        @Test
        @DisplayName("AC-15 ADM(組織B の SUPPORTER) × 組織B サブタブ SUPPORTER × PUB: 404(MEMBER_001)・コピー先0件（HEAD では通る＝red）")
        void AC15_越境_SUPPORTER_404() throws Exception {
            MembershipTestHelper.insertMembership(em, admId, ScopeType.ORGANIZATION, orgBId, RoleKind.SUPPORTER);
            setProfilesSubtab(orgBId, MinRole.SUPPORTER);
            em.flush();
            setAuth(admId);
            expect404(copy(tgtAId, pubBId), "MEMBER_001");
            assertThat(countProfiles(tgtAId)).isZero();
        }

        @Test
        @DisplayName("AC-16(裁可1) ADM(組織B の MEMBER) × 組織B の PUB: 別組織なので 404(MEMBER_001)・コピー先0件（HEAD では通る＝red）")
        void AC16_越境_MEMBER_でも404() throws Exception {
            MembershipTestHelper.insertMembership(em, admId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
            em.flush();
            setAuth(admId);
            expect404(copy(tgtAId, pubBId), "MEMBER_001");
            assertThat(countProfiles(tgtAId)).isZero();
        }

        @Test
        @DisplayName("AC-16 同一組織内: 200・コピー元の表示中のみ複製（1件）")
        void AC16_同一組織_200_表示中のみ() throws Exception {
            setAuth(admId);
            copy(tgtAId, pubAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.copiedCount").value(1));
            assertThat(countProfiles(tgtAId)).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-23 O2 同一組織・コピー元の表示中0件: 200・copiedCount=0")
        void AC23_表示中0件_copied0() throws Exception {
            setAuth(admId);
            copy(tgtAId, emptyAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.copiedCount").value(0));
            assertThat(countProfiles(tgtAId)).isZero();
        }

        @Test
        @DisplayName("AC-29 source=target: 400(MEMBER_011)・コピー先不変")
        void AC29_同一ページ_400() throws Exception {
            setAuth(admId);
            copy(tgtAId, tgtAId).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("MEMBER_011"));
            assertThat(countProfiles(tgtAId)).isZero();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // E. 空・境界
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-23/28: 0件")
    class Empty {

        @BeforeEach
        void auth() {
            setAuth(memId);
        }

        @Test
        @DisplayName("AC-23 V3 表示中0件: 200・空")
        void AC23_V3_空() throws Exception {
            listProfiles(emptyAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("AC-28 V1 セクション0件・表示中0件: 200・双方空配列")
        void AC28_V1_空() throws Exception {
            getPage(emptyAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sections.length()").value(0))
                    .andExpect(jsonPath("$.data.members.length()").value(0));
        }

        @Test
        @DisplayName("AC-28 V2 セクション0件: 200・空配列")
        void AC28_V2_空() throws Exception {
            listSections(emptyAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // D. 回帰（listPages・サブタブ設定の入力検証）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-17: listPages（組織）は現 HEAD どおり")
    class ListPages {

        @Test
        @DisplayName("AC-17 OUT × サブタブ PUBLIC: PUBLISHED + PUBLIC のみ（pubA・emptyA の2件）")
        void AC17_OUT_公開のみ() throws Exception {
            setProfilesSubtab(orgAId, MinRole.PUBLIC);
            setAuth(outId);
            listPages(orgAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2));
        }

        @Test
        @DisplayName("AC-17 MEM: PUBLISHED のみ（pubA・moA・emptyA の3件）")
        void AC17_MEM_公開済みのみ() throws Exception {
            setAuth(memId);
            listPages(orgAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(3));
        }

        @Test
        @DisplayName("AC-17 ADM: 全件（5件）")
        void AC17_ADM_全件() throws Exception {
            setAuth(admId);
            listPages(orgAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(5));
        }

        @Test
        @DisplayName("AC-17 SYS: 全件（5件）")
        void AC17_SYS_全件() throws Exception {
            setAuth(sysId);
            listPages(orgAId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(5));
        }

        @Test
        @DisplayName("AC-17 OUT × 既定: 403(COMMON_002)")
        void AC17_OUT_既定_403() throws Exception {
            setAuth(outId);
            listPages(orgAId).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }
    }

    @Nested
    @DisplayName("AC-21: サブタブに minRole \"ADMIN\" は入れられない")
    class SubtabAdminRejected {

        @Test
        @DisplayName("AC-21 ADM が minRole=ADMIN を PUT: 400・DB 不変")
        void AC21_ADMINは400_DB不変() throws Exception {
            setProfilesSubtab(orgAId, MinRole.SUPPORTER);
            em.clear();
            setAuth(admId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("subtabKey", MemberSubtabKey.MEMBER_PROFILES.getDbValue());
            item.put("minRole", "ADMIN");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("subtabs", List.of(item));

            mockMvc.perform(put("/api/v1/organizations/{slug}/member-subtab-visibility", orgASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());

            em.clear();
            List<MemberSubtabRoleVisibilityEntity> rows = subtabRepository.findByScopeTypeAndScopeId(
                    com.mannschaft.app.dashboard.ScopeType.ORGANIZATION, orgAId);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getMinRole()).isEqualTo(MinRole.SUPPORTER);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private ResultActions getPage(Long pageId) throws Exception {
        return mockMvc.perform(get("/api/v1/team/pages/{id}", pageId));
    }

    private ResultActions listSections(Long pageId) throws Exception {
        return mockMvc.perform(get("/api/v1/team/pages/{pageId}/sections", pageId));
    }

    private ResultActions listProfiles(Long pageId) throws Exception {
        return mockMvc.perform(get("/api/v1/team/members").param("teamPageId", pageId.toString()));
    }

    private ResultActions getProfile(Long profileId) throws Exception {
        return mockMvc.perform(get("/api/v1/team/members/{id}", profileId));
    }

    private ResultActions lookup(Long pageId, String q, int limit) throws Exception {
        return mockMvc.perform(get("/api/v1/team/members/lookup")
                .param("q", q)
                .param("teamPageId", pageId.toString())
                .param("limit", String.valueOf(limit)));
    }

    private ResultActions copy(Long targetPageId, Long sourcePageId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourcePageId", sourcePageId);
        return mockMvc.perform(post("/api/v1/team/pages/{id}/copy-members", targetPageId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions listPages(Long organizationId) throws Exception {
        return mockMvc.perform(get("/api/v1/team/pages").param("organizationId", organizationId.toString()));
    }

    private static void expect404(ResultActions actions, String code) throws Exception {
        actions.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(code));
    }

    private long countProfiles(Long pageId) {
        em.flush();
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM member_profiles WHERE team_page_id = :pid")
                .setParameter("pid", pageId).getSingleResult()).longValue();
    }

    private void setProfilesSubtab(Long orgId, MinRole minRole) {
        subtabRepository.save(MemberSubtabRoleVisibilityEntity.builder()
                .scopeType(com.mannschaft.app.dashboard.ScopeType.ORGANIZATION)
                .scopeId(orgId)
                .subtabKey(MemberSubtabKey.MEMBER_PROFILES.getDbValue())
                .minRole(minRole)
                .updatedBy(admId)
                .build());
        em.flush();
    }

    private Long savePage(Long orgId, String title, PageStatus status, PageVisibility visibility) {
        TeamPageEntity page = pageRepository.save(TeamPageEntity.builder()
                .organizationId(orgId).title(title).slug("orgv-" + UUID.randomUUID().toString().substring(0, 12))
                .pageType(PageType.YEARLY).status(status).visibility(visibility).build());
        return page.getId();
    }

    private Long saveProfile(Long pageId, String displayName, boolean visible, int sortOrder) {
        MemberProfileEntity profile = profileRepository.save(MemberProfileEntity.builder()
                .teamPageId(pageId).displayName(displayName).isVisible(visible).sortOrder(sortOrder).build());
        return profile.getId();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'ORGV', 'テスト', 'ORGV テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
