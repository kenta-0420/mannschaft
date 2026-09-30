package com.mannschaft.app.role;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.repository.PermissionGroupPermissionRepository;
import com.mannschaft.app.role.repository.PermissionGroupRepository;
import com.mannschaft.app.role.repository.UserPermissionGroupRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 5-A — ADMIN 専用権限 {@code MANAGE_ORG_AFFILIATION} の付与経路の認可契約テスト（試練・実装前 red）。
 *
 * <p>正本は {@code docs/features/F01.2.1_org_team_groups.md} §3.2 と §16「P. チーム側の加盟操作権限」。
 * 本クラスは実装より前に書かれており、DEPUTY_ADMIN 系のテストは<b>意図的に red</b> である。</p>
 *
 * <h2>red の理由（実装前の既知の欠陥）</h2>
 * <p>{@code PermissionGroupService#requireMutationAuthority} は ADMIN 専用権限の一覧
 * {@code ADMIN_ONLY_GRANTABLE_PERMISSIONS}（SEND_PAID_TIMELINE / VIEW_TIMELINE_COST）に
 * {@code MANAGE_ORG_AFFILIATION} を含んでいないため、この権限を含む権限グループの
 * 作成・更新・複製・削除・ユーザー割当が {@code checkAdminOrAbove}（DEPUTY_ADMIN も通る）で判定され、
 * DEPUTY_ADMIN が自己昇格できてしまう（201/200/204 が返る）。</p>
 *
 * <h2>方針</h2>
 * <p>実 MySQL（Testcontainers）＋実 Security フィルタ＋ MockMvc で実 API を叩く。
 * {@code AccessControlService}・{@code PermissionGroupService}・Repository はモックしない。
 * 状態が変わらないことは flush/clear 後に DB を直接読み直して確かめる。</p>
 *
 * <p>本テストは共有コンテキスト（ddl-auto=create・Flyway 無効）で動くため、
 * {@code permissions} 行は Flyway と同じ内容を冪等に seed する。Flyway が実際にこの行と
 * ADMIN の既定付与を入れることは {@link OrgAffiliationPermissionFlywayIT} が検証する（AC-P01）。</p>
 *
 * <p>人物は設計書 §16 の記号に揃える: TA＝チーム ADMIN、TD＝チーム DEPUTY_ADMIN、
 * TM＝チーム MEMBER（付与なし）、TG＝チーム MEMBER（権限グループで付与済み）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 5-A MANAGE_ORG_AFFILIATION 付与経路の認可契約テスト（試練・実装前 red）")
class OrgAffiliationPermissionGroupAuthzContractIT extends AbstractMySqlIntegrationTest {

    private static final String MANAGE_ORG_AFFILIATION = "MANAGE_ORG_AFFILIATION";
    private static final String SEND_PAID_TIMELINE = "SEND_PAID_TIMELINE";
    private static final String VIEW_TIMELINE_COST = "VIEW_TIMELINE_COST";
    /** ADMIN 専用ではない通常の権限（回帰確認用）。 */
    private static final String MANAGE_SCHEDULES = "MANAGE_SCHEDULES";

    private static final Long TA = 930529001L;
    private static final Long TD = 930529002L;
    private static final Long TM = 930529003L;
    private static final Long TG = 930529004L;

    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private PermissionGroupRepository permissionGroupRepository;

    @Autowired
    private PermissionGroupPermissionRepository permissionGroupPermissionRepository;

    @Autowired
    private UserPermissionGroupRepository userPermissionGroupRepository;

    @Autowired
    private AccessControlService accessControlService;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private String teamSlug;

    private Long affiliationPermId;
    private Long schedulesPermId;
    private Long sendPaidPermId;
    private Long viewCostPermId;

    /** TA が作った、MANAGE_ORG_AFFILIATION を含む DEPUTY_ADMIN 向けグループ。 */
    private Long affDeputyGroupId;
    /** TA が作った、MANAGE_ORG_AFFILIATION を含む MEMBER 向けグループ。 */
    private Long affMemberGroupId;
    /** MANAGE_ORG_AFFILIATION を含まない DEPUTY_ADMIN 向けグループ。 */
    private Long plainDeputyGroupId;
    /** MANAGE_ORG_AFFILIATION を含まない MEMBER 向けグループ。 */
    private Long plainMemberGroupId;
    /** F09.14 の ADMIN 専用権限（VIEW_TIMELINE_COST）を含む DEPUTY_ADMIN 向けグループ。 */
    private Long f0914DeputyGroupId;

    @BeforeEach
    void setUp() {
        affiliationPermId = ensurePermission(MANAGE_ORG_AFFILIATION, "組織への加盟操作");
        schedulesPermId = ensurePermission(MANAGE_SCHEDULES, "スケジュール管理");
        sendPaidPermId = ensurePermission(SEND_PAID_TIMELINE, "有料タイムライン投稿");
        viewCostPermId = ensurePermission(VIEW_TIMELINE_COST, "タイムライン配信コスト閲覧");

        TeamEntity team = teamRepository.save(TeamEntity.builder()
                .slug("aff-perm-5a-" + SLUG_SEQ.incrementAndGet() + "-" + System.nanoTime())
                .name("加盟操作権限 5-A 試練チーム")
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(true)
                .build());
        teamId = team.getId();
        teamSlug = team.getSlug();

        for (Long userId : List.of(TA, TD, TM, TG)) {
            MembershipTestHelper.insertActiveUser(em, userId);
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        }
        // 権限ロールは user_roles 系統（ADMIN / DEPUTY_ADMIN は memberships と二重に seed する既知の地雷）。
        MembershipTestHelper.insertUserRole(em, TA, "ADMIN", teamId, null);
        MembershipTestHelper.insertUserRole(em, TD, "DEPUTY_ADMIN", teamId, null);

        affDeputyGroupId = saveGroup("加盟操作(副管理者)", PermissionGroupEntity.TargetRole.DEPUTY_ADMIN,
                List.of(affiliationPermId, schedulesPermId));
        affMemberGroupId = saveGroup("加盟操作(メンバー)", PermissionGroupEntity.TargetRole.MEMBER,
                List.of(affiliationPermId));
        plainDeputyGroupId = saveGroup("通常(副管理者)", PermissionGroupEntity.TargetRole.DEPUTY_ADMIN,
                List.of(schedulesPermId));
        plainMemberGroupId = saveGroup("通常(メンバー)", PermissionGroupEntity.TargetRole.MEMBER,
                List.of(schedulesPermId));
        f0914DeputyGroupId = saveGroup("配信コスト(副管理者)", PermissionGroupEntity.TargetRole.DEPUTY_ADMIN,
                List.of(viewCostPermId));

        // TG は MEMBER 向けの加盟操作グループを割り当て済み（AC-P06 の被験者）。
        assignDirectly(TG, affMemberGroupId);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-P10: 自己昇格の封止（作成・自分への割当）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-P10 自己昇格の封止")
    class SelfEscalation {

        @Test
        @DisplayName("AC-P10/P12(作成): TD が MANAGE_ORG_AFFILIATION を含むグループを作ると 403 で作られない")
        void deputyCreateWithAffiliation_forbidden() throws Exception {
            long before = countGroupsInTeam();

            forbidden(mockMvc.perform(post("/api/v1/teams/{slug}/permission-groups", teamSlug)
                    .with(user(TD.toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(groupBody("自己昇格", "DEPUTY_ADMIN", List.of(affiliationPermId)))));

            assertThat(countGroupsInTeam()).isEqualTo(before);
        }

        @Test
        @DisplayName("AC-P10(自分への割当): TD が TA の作った同権限を含むグループを自分に割り当てると 403 で、me/permissions に現れない")
        void deputyAssignAffiliationGroupToSelf_forbidden() throws Exception {
            forbidden(mockMvc.perform(put("/api/v1/teams/{slug}/members/{userId}/permission-groups", teamSlug, TD)
                    .with(user(TD.toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(assignBody(List.of(affDeputyGroupId)))));

            assertThat(assignedGroupIds(TD)).doesNotContain(affDeputyGroupId);
            myPermissions(TD).andExpect(jsonPath("$.data.permissions", not(hasItem(MANAGE_ORG_AFFILIATION))));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-P11: 他者への割当・割当の解除（変更前後の和集合で判定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-P11 他者への割当・解除")
    class OthersAssignment {

        @Test
        @DisplayName("AC-P11(他者への割当): TD が同権限を含むグループを TM に割り当てると 403 で、TM の me/permissions は変わらない")
        void deputyAssignAffiliationGroupToOther_forbidden() throws Exception {
            forbidden(mockMvc.perform(put("/api/v1/teams/{slug}/members/{userId}/permission-groups", teamSlug, TM)
                    .with(user(TD.toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(assignBody(List.of(affMemberGroupId)))));

            assertThat(assignedGroupIds(TM)).isEmpty();
            myPermissions(TM).andExpect(jsonPath("$.data.permissions", not(hasItem(MANAGE_ORG_AFFILIATION))));
        }

        @Test
        @DisplayName("AC-P11(解除): 同権限を含むグループが割当済みの TG から、TD が割当を外すと 403 で割当は残る（和集合判定）")
        void deputyUnassignAffiliationGroup_forbidden() throws Exception {
            forbidden(mockMvc.perform(put("/api/v1/teams/{slug}/members/{userId}/permission-groups", teamSlug, TG)
                    .with(user(TD.toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(assignBody(List.of()))));

            assertThat(assignedGroupIds(TG)).containsExactly(affMemberGroupId);
        }

        @Test
        @DisplayName("AC-P11(差し替え): 同権限を含むグループが割当済みの TG を、TD が通常グループへ差し替えると 403 で割当は変わらない")
        void deputyReplaceAffiliationGroupWithPlain_forbidden() throws Exception {
            forbidden(mockMvc.perform(put("/api/v1/teams/{slug}/members/{userId}/permission-groups", teamSlug, TG)
                    .with(user(TD.toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(assignBody(List.of(plainMemberGroupId)))));

            assertThat(assignedGroupIds(TG)).containsExactly(affMemberGroupId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-P12: 5経路（作成・更新・複製・削除・割当）すべてで 403
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-P12 既存グループの改変・複製・削除")
    class ExistingGroupMutation {

        @Test
        @DisplayName("AC-P12(更新・追加): TD が通常グループに同権限を足すと 403 でグループは変わらない")
        void deputyUpdateAddingAffiliation_forbidden() throws Exception {
            forbidden(mockMvc.perform(patch("/api/v1/teams/{slug}/permission-groups/{groupId}",
                            teamSlug, plainDeputyGroupId)
                    .with(user(TD.toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(groupBody("通常(副管理者)", "DEPUTY_ADMIN",
                            List.of(schedulesPermId, affiliationPermId)))));

            assertThat(permissionIdsOf(plainDeputyGroupId)).containsExactly(schedulesPermId);
        }

        @Test
        @DisplayName("AC-P12(更新・剥奪): TD が同権限を含むグループから同権限を外すと 403 でグループは変わらない（和集合判定）")
        void deputyUpdateRemovingAffiliation_forbidden() throws Exception {
            forbidden(mockMvc.perform(patch("/api/v1/teams/{slug}/permission-groups/{groupId}",
                            teamSlug, affDeputyGroupId)
                    .with(user(TD.toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(groupBody("加盟操作(副管理者)", "DEPUTY_ADMIN", List.of(schedulesPermId)))));

            assertThat(permissionIdsOf(affDeputyGroupId))
                    .containsExactlyInAnyOrder(affiliationPermId, schedulesPermId);
        }

        @Test
        @DisplayName("AC-P12(更新・改名のみ): TD が同権限を含むグループの名前だけ変えても 403 で名前は変わらない")
        void deputyRenameAffiliationGroup_forbidden() throws Exception {
            forbidden(mockMvc.perform(patch("/api/v1/teams/{slug}/permission-groups/{groupId}",
                            teamSlug, affDeputyGroupId)
                    .with(user(TD.toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(groupBody("改名後", "DEPUTY_ADMIN",
                            List.of(affiliationPermId, schedulesPermId)))));

            assertThat(groupName(affDeputyGroupId)).isEqualTo("加盟操作(副管理者)");
        }

        @Test
        @DisplayName("AC-P12(複製): TD が同権限を含むグループを複製すると 403 で複製は作られない")
        void deputyDuplicateAffiliationGroup_forbidden() throws Exception {
            long before = countGroupsInTeam();

            forbidden(mockMvc.perform(post("/api/v1/admin/permission-groups/{id}/duplicate", affDeputyGroupId)
                    .with(user(TD.toString()))));

            assertThat(countGroupsInTeam()).isEqualTo(before);
        }

        @Test
        @DisplayName("AC-P12(削除): TD が同権限を含むグループを削除すると 403 でグループと割当は残る")
        void deputyDeleteAffiliationGroup_forbidden() throws Exception {
            forbidden(mockMvc.perform(delete("/api/v1/teams/{slug}/permission-groups/{groupId}",
                            teamSlug, affMemberGroupId)
                    .with(user(TD.toString()))));

            assertThat(groupExists(affMemberGroupId)).isTrue();
            assertThat(permissionIdsOf(affMemberGroupId)).containsExactly(affiliationPermId);
            assertThat(assignedGroupIds(TG)).containsExactly(affMemberGroupId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-P12 後半: 同権限を含まないグループは従来どおり DEPUTY_ADMIN にも許す（回帰防止）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-P12 回帰: 同権限を含まないグループは TD も操作できる")
    class DeputyPlainGroupStillAllowed {

        @Test
        @DisplayName("TD は同権限を含まないグループを作成できる（201）")
        void deputyCreatePlain_created() throws Exception {
            mockMvc.perform(post("/api/v1/teams/{slug}/permission-groups", teamSlug)
                            .with(user(TD.toString()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(groupBody("TD 作成の通常グループ", "MEMBER", List.of(schedulesPermId))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("TD は同権限を含まないグループを更新できる（200）")
        void deputyUpdatePlain_ok() throws Exception {
            mockMvc.perform(patch("/api/v1/teams/{slug}/permission-groups/{groupId}", teamSlug, plainMemberGroupId)
                            .with(user(TD.toString()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(groupBody("通常(メンバー)改", "MEMBER", List.of(schedulesPermId))))
                    .andExpect(status().isOk());

            assertThat(groupName(plainMemberGroupId)).isEqualTo("通常(メンバー)改");
        }

        @Test
        @DisplayName("TD は同権限を含まないグループを TM に割り当てられる（200）")
        void deputyAssignPlain_ok() throws Exception {
            mockMvc.perform(put("/api/v1/teams/{slug}/members/{userId}/permission-groups", teamSlug, TM)
                            .with(user(TD.toString()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(assignBody(List.of(plainMemberGroupId))))
                    .andExpect(status().isOk());

            assertThat(assignedGroupIds(TM)).containsExactly(plainMemberGroupId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // チーム ADMIN は5経路すべてで成功する
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TA（チーム ADMIN）は同権限を含むグループを5経路すべてで操作できる")
    class AdminAllowed {

        @Test
        @DisplayName("作成（201）")
        void adminCreate_created() throws Exception {
            mockMvc.perform(post("/api/v1/teams/{slug}/permission-groups", teamSlug)
                            .with(user(TA.toString()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(groupBody("TA 作成の加盟操作グループ", "DEPUTY_ADMIN",
                                    List.of(affiliationPermId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.permissions", hasItem(MANAGE_ORG_AFFILIATION)));
        }

        @Test
        @DisplayName("更新・剥奪（200）")
        void adminUpdate_ok() throws Exception {
            mockMvc.perform(patch("/api/v1/teams/{slug}/permission-groups/{groupId}", teamSlug, affDeputyGroupId)
                            .with(user(TA.toString()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(groupBody("加盟操作(副管理者)", "DEPUTY_ADMIN", List.of(schedulesPermId))))
                    .andExpect(status().isOk());

            assertThat(permissionIdsOf(affDeputyGroupId)).containsExactly(schedulesPermId);
        }

        @Test
        @DisplayName("複製（201）")
        void adminDuplicate_created() throws Exception {
            long before = countGroupsInTeam();

            mockMvc.perform(post("/api/v1/admin/permission-groups/{id}/duplicate", affDeputyGroupId)
                            .with(user(TA.toString())))
                    .andExpect(status().isCreated());

            assertThat(countGroupsInTeam()).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("削除（204）")
        void adminDelete_noContent() throws Exception {
            mockMvc.perform(delete("/api/v1/teams/{slug}/permission-groups/{groupId}", teamSlug, affMemberGroupId)
                            .with(user(TA.toString())))
                    .andExpect(status().isNoContent());

            assertThat(groupExists(affMemberGroupId)).isFalse();
        }

        @Test
        @DisplayName("TG からの割当解除（200）")
        void adminUnassign_ok() throws Exception {
            mockMvc.perform(put("/api/v1/teams/{slug}/members/{userId}/permission-groups", teamSlug, TG)
                            .with(user(TA.toString()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(assignBody(List.of())))
                    .andExpect(status().isOk());

            assertThat(assignedGroupIds(TG)).isEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-P03 / AC-P04: 付与されたメンバーが権限を持つと判定される（付与の経路のみ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-P03/P04 付与の経路")
    class GrantPath {

        @Test
        @DisplayName("AC-P03: TA が DEPUTY_ADMIN 向けの同権限グループを TD に割り当てると、TD が権限を持つと判定される")
        void adminGrantsDeputy_deputyHasPermission() throws Exception {
            assertThat(accessControlService.hasPermission(TD, teamId, "TEAM", MANAGE_ORG_AFFILIATION)).isFalse();

            mockMvc.perform(put("/api/v1/teams/{slug}/members/{userId}/permission-groups", teamSlug, TD)
                            .with(user(TA.toString()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(assignBody(List.of(affDeputyGroupId))))
                    .andExpect(status().isOk());

            myPermissions(TD).andExpect(jsonPath("$.data.permissions", hasItem(MANAGE_ORG_AFFILIATION)));
            assertThat(accessControlService.hasPermission(TD, teamId, "TEAM", MANAGE_ORG_AFFILIATION)).isTrue();
        }

        @Test
        @DisplayName("AC-P04: TA が MEMBER 向けの同権限グループを TM に割り当てると TM が権限を持ち、付与されていない TD・MEMBER は持たない")
        void adminGrantsMember_memberHasPermission() throws Exception {
            assertThat(accessControlService.hasPermission(TM, teamId, "TEAM", MANAGE_ORG_AFFILIATION)).isFalse();

            mockMvc.perform(put("/api/v1/teams/{slug}/members/{userId}/permission-groups", teamSlug, TM)
                            .with(user(TA.toString()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(assignBody(List.of(affMemberGroupId))))
                    .andExpect(status().isOk());

            myPermissions(TM).andExpect(jsonPath("$.data.permissions", hasItem(MANAGE_ORG_AFFILIATION)));
            assertThat(accessControlService.hasPermission(TM, teamId, "TEAM", MANAGE_ORG_AFFILIATION)).isTrue();
            // 既に割当済みの TG も持つ。付与されていない TD は持たない。
            assertThat(accessControlService.hasPermission(TG, teamId, "TEAM", MANAGE_ORG_AFFILIATION)).isTrue();
            assertThat(accessControlService.hasPermission(TD, teamId, "TEAM", MANAGE_ORG_AFFILIATION)).isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-P06: TG（権限付与済みの MEMBER）は権限グループを操作できない
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-P06 付与済み MEMBER（TG）は権限グループを操作できない")
    class GrantedMemberCannotManageGroups {

        @Test
        @DisplayName("TG の権限グループ作成は 403")
        void grantedMemberCreate_forbidden() throws Exception {
            long before = countGroupsInTeam();

            forbidden(mockMvc.perform(post("/api/v1/teams/{slug}/permission-groups", teamSlug)
                    .with(user(TG.toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(groupBody("TG の試み", "MEMBER", List.of(affiliationPermId)))));

            assertThat(countGroupsInTeam()).isEqualTo(before);
        }

        @Test
        @DisplayName("TG が TM に同権限グループを割り当てると 403")
        void grantedMemberAssign_forbidden() throws Exception {
            forbidden(mockMvc.perform(put("/api/v1/teams/{slug}/members/{userId}/permission-groups", teamSlug, TM)
                    .with(user(TG.toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(assignBody(List.of(affMemberGroupId)))));

            assertThat(assignedGroupIds(TM)).isEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 回帰: 既存の F09.14 系の権限も引き続き ADMIN 限定
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("回帰: F09.14 の ADMIN 専用権限は引き続き TD に 403")
    class F0914Regression {

        @Test
        @DisplayName("TD が SEND_PAID_TIMELINE を含むグループを作ると 403")
        void deputyCreateSendPaid_forbidden() throws Exception {
            long before = countGroupsInTeam();

            forbidden(mockMvc.perform(post("/api/v1/teams/{slug}/permission-groups", teamSlug)
                    .with(user(TD.toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(groupBody("有料配信", "DEPUTY_ADMIN", List.of(sendPaidPermId)))));

            assertThat(countGroupsInTeam()).isEqualTo(before);
        }

        @Test
        @DisplayName("TD が VIEW_TIMELINE_COST を含むグループを自分に割り当てると 403")
        void deputyAssignViewCostToSelf_forbidden() throws Exception {
            forbidden(mockMvc.perform(put("/api/v1/teams/{slug}/members/{userId}/permission-groups", teamSlug, TD)
                    .with(user(TD.toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(assignBody(List.of(f0914DeputyGroupId)))));

            assertThat(assignedGroupIds(TD)).isEmpty();
        }

        @Test
        @DisplayName("TD が VIEW_TIMELINE_COST を含むグループを削除すると 403")
        void deputyDeleteViewCost_forbidden() throws Exception {
            forbidden(mockMvc.perform(delete("/api/v1/teams/{slug}/permission-groups/{groupId}",
                            teamSlug, f0914DeputyGroupId)
                    .with(user(TD.toString()))));

            assertThat(groupExists(f0914DeputyGroupId)).isTrue();
        }
    }

    // ─────────────────────────────── ヘルパー ───────────────────────────────

    private void forbidden(ResultActions actions) throws Exception {
        actions.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    private ResultActions myPermissions(Long userId) throws Exception {
        return mockMvc.perform(get("/api/v1/teams/{slug}/me/permissions", teamSlug)
                        .with(user(userId.toString())))
                .andExpect(status().isOk());
    }

    private String groupBody(String name, String targetRole, List<Long> permissionIds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("targetRole", targetRole);
        body.put("permissionIds", permissionIds);
        return objectMapper.writeValueAsString(body);
    }

    private String assignBody(List<Long> groupIds) throws Exception {
        return objectMapper.writeValueAsString(Map.of("groupIds", groupIds));
    }

    /** Flyway の INSERT ... WHERE NOT EXISTS と同じ作法で permissions 行を冪等に用意する。 */
    private Long ensurePermission(String name, String displayName) {
        em.createNativeQuery(
                        "INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                                + "SELECT :name, :displayName, 'TEAM', NOW(), NOW() FROM DUAL "
                                + "WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = :name)")
                .setParameter("name", name)
                .setParameter("displayName", displayName)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM permissions WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long saveGroup(String name, PermissionGroupEntity.TargetRole targetRole, List<Long> permissionIds) {
        PermissionGroupEntity group = permissionGroupRepository.save(PermissionGroupEntity.builder()
                .teamId(teamId)
                .name(name)
                .targetRole(targetRole)
                .createdBy(TA)
                .build());
        for (Long permissionId : permissionIds) {
            permissionGroupPermissionRepository.save(PermissionGroupPermissionEntity.builder()
                    .groupId(group.getId())
                    .permissionId(permissionId)
                    .build());
        }
        return group.getId();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-P11: ロール変更による間接的な剥奪（RolePermissionCleanupService 経路）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-P11 ロール変更による間接剥奪")
    class RoleChangeIndirectRemoval {

        private static final Long TX = 930529005L;

        private Long seedDeputyHolding(Long groupId) {
            MembershipTestHelper.insertActiveUser(em, TX);
            MembershipTestHelper.insertMembership(em, TX, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, TX, "DEPUTY_ADMIN", teamId, null);
            assignDirectly(TX, groupId);
            em.flush();
            em.clear();
            return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = 'MEMBER'")
                    .getSingleResult()).longValue();
        }

        private ResultActions changeRoleToMember(Long actor, Long memberRoleId) throws Exception {
            return mockMvc.perform(patch("/api/v1/teams/{slug}/members/{userId}/role", teamSlug, TX)
                    .with(user(actor.toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"roleId\":" + memberRoleId + "}"));
        }

        @Test
        @DisplayName("AC-P11(a): TD が加盟権限付き割当を持つ対象のロールを変えると 403 で、割当は残る")
        void deputyChangeRoleRemovingAffiliationAssignment_forbidden() throws Exception {
            Long memberRoleId = seedDeputyHolding(affDeputyGroupId);

            forbidden(changeRoleToMember(TD, memberRoleId));

            assertThat(assignedGroupIds(TX)).contains(affDeputyGroupId);
        }

        @Test
        @DisplayName("AC-P11(b): TA が同じ操作をすると 200 で、割当は自動で外れる")
        void adminChangeRoleRemovingAffiliationAssignment_ok() throws Exception {
            Long memberRoleId = seedDeputyHolding(affDeputyGroupId);

            changeRoleToMember(TA, memberRoleId).andExpect(status().isOk());

            assertThat(assignedGroupIds(TX)).doesNotContain(affDeputyGroupId);
        }

        @Test
        @DisplayName("AC-P11(c): 加盟権限を含まない割当なら TD でも従来どおり 200 で割当が外れる")
        void deputyChangeRoleRemovingPlainAssignment_ok() throws Exception {
            Long memberRoleId = seedDeputyHolding(plainDeputyGroupId);

            changeRoleToMember(TD, memberRoleId).andExpect(status().isOk());

            assertThat(assignedGroupIds(TX)).doesNotContain(plainDeputyGroupId);
        }
    }

    private void assignDirectly(Long userId, Long groupId) {
        userPermissionGroupRepository.save(UserPermissionGroupEntity.builder()
                .userId(userId)
                .groupId(groupId)
                .assignedBy(TA)
                .build());
    }

    private long countGroupsInTeam() {
        em.flush();
        em.clear();
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM permission_groups WHERE team_id = :teamId AND deleted_at IS NULL")
                .setParameter("teamId", teamId)
                .getSingleResult()).longValue();
    }

    private boolean groupExists(Long groupId) {
        em.flush();
        em.clear();
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM permission_groups WHERE id = :id AND deleted_at IS NULL")
                .setParameter("id", groupId)
                .getSingleResult()).longValue() > 0;
    }

    private String groupName(Long groupId) {
        em.flush();
        em.clear();
        return (String) em.createNativeQuery("SELECT name FROM permission_groups WHERE id = :id")
                .setParameter("id", groupId)
                .getSingleResult();
    }

    @SuppressWarnings("unchecked")
    private List<Long> permissionIdsOf(Long groupId) {
        em.flush();
        em.clear();
        return ((List<Number>) em.createNativeQuery(
                        "SELECT permission_id FROM permission_group_permissions WHERE group_id = :id")
                .setParameter("id", groupId)
                .getResultList()).stream().map(Number::longValue).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Long> assignedGroupIds(Long userId) {
        em.flush();
        em.clear();
        return ((List<Number>) em.createNativeQuery(
                        "SELECT upg.group_id FROM user_permission_groups upg "
                                + "JOIN permission_groups pg ON pg.id = upg.group_id "
                                + "WHERE upg.user_id = :userId AND pg.team_id = :teamId")
                .setParameter("userId", userId)
                .setParameter("teamId", teamId)
                .getResultList()).stream().map(Number::longValue).toList();
    }
}
