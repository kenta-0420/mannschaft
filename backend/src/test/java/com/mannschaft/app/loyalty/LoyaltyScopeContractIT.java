package com.mannschaft.app.loyalty;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.membership.domain.ArchiveReason;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.LeftTrigger;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.repository.PermissionGroupRepository;
import com.mannschaft.app.role.repository.PermissionGroupPermissionRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserPermissionGroupRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.loyalty.dto.RequestActionRequest;
import com.mannschaft.app.loyalty.entity.LoyaltyAccountEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 実認証主体を差し替え、実MVCとMySQLで他人の口座・申請・取消receiptへ到達できないことを固定する。
 * 対象は LoyaltyController#listAccounts / #myRequests / #myRequest / #cancel。
 * TEAM権限やQRの実業務検証はLoyaltyHttpContractITも担い、routing試練のFacade mockは利用しない。
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class LoyaltyScopeContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private LoyaltyStore store;
    @Autowired private Clock clock;
    @Autowired private LoyaltyAccessPolicy policy;
    @Autowired private MembershipRepository memberships;
    @Autowired private OrganizationRepository organizations;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private PermissionRepository permissions;
    @Autowired private PermissionGroupRepository permissionGroups;
    @Autowired private PermissionGroupPermissionRepository groupPermissions;
    @Autowired private UserPermissionGroupRepository userGroups;
    private Long me;
    private Long other;
    private Long teamA;
    private Long teamB;
    private UUID mineAccount;
    private UUID otherAccount;
    private UUID mineRequest;
    private UUID otherRequest;
    private UUID cancelKey;
    private LocalDateTime now;

    @BeforeEach void fixture() {
        me = users.saveAndFlush(LoyaltyTestFixture.user()).getId();
        other = users.saveAndFlush(LoyaltyTestFixture.user()).getId();
        teamA = team("本人側");
        teamB = team("別チーム");
        now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Tokyo")).withNano(0);
        mineAccount = account(teamA, me, 7);
        otherAccount = account(teamB, other, 19);
        mineRequest = request(teamA, me, "ABCDEFGH", false);
        otherRequest = request(teamB, other, "BCDEFGHI", false);
    }

    @Test void listAccounts_同じURLで主語を差し替えると本人分だけ返る() throws Exception {
        mvc.perform(get("/api/v1/loyalty/accounts/me").with(user(me.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[*].accountId", containsInAnyOrder(mineAccount.toString())))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
        mvc.perform(get("/api/v1/loyalty/accounts/me").with(user(other.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[*].accountId", containsInAnyOrder(otherAccount.toString())));
    }

    @Test void myRequests_所属や口座によらず本人申請だけ返る() throws Exception {
        mvc.perform(get("/api/v1/loyalty/award-requests/me").with(user(me.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[*].id", containsInAnyOrder(mineRequest.toString())));
        mvc.perform(get("/api/v1/loyalty/award-requests/me").with(user(other.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[*].id", containsInAnyOrder(otherRequest.toString())));
    }

    @Test void myRequest_同じ申請IDは所有者のみ取得できる() throws Exception {
        String path = "/api/v1/loyalty/award-requests/" + mineRequest + "/me";
        mvc.perform(get(path).with(user(me.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(mineRequest.toString()));
        mvc.perform(get(path).with(user(other.toString())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LOYALTY_001"));
    }

    @Test void cancel_他人は申請版やcodeや残高を更新できない() throws Exception {
        mvc.perform(post("/api/v1/loyalty/award-requests/" + mineRequest + "/cancel/me")
                        .with(user(other.toString())).header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content("{\"expectedRequestVersion\":0}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LOYALTY_001"));
        var persisted = store.getRequests().findById(mineRequest).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(persisted.getVersion()).isZero();
        assertThat(persisted.getVerificationCode()).isEqualTo("ABCDEFGH");
        assertThat(store.getAccounts().findById(mineAccount).orElseThrow().getBalance()).isEqualTo(7);
    }

    @Test void cancel_本人の保存済receiptは外部状態を再検証せず同じ申請を返す() throws Exception {
        UUID closed = request(teamA, me, "CDEFGHIJ", true);
        String path = "/api/v1/loyalty/award-requests/" + closed + "/cancel/me";
        mvc.perform(post(path).with(user(me.toString())).header("Idempotency-Key", cancelKey)
                        .contentType("application/json").content("{\"expectedRequestVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(closed.toString()))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mvc.perform(post(path).with(user(other.toString())).header("Idempotency-Key", cancelKey)
                        .contentType("application/json").content("{\"expectedRequestVersion\":0}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LOYALTY_001"));
        assertThat(store.getRequests().findById(closed).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test void AC18_未認証の巨大本文は認証境界を通過しない() throws Exception {
        mvc.perform(post("/api/v1/loyalty/self-award-bootstrap/me")
                        .contentType("application/json").content("x".repeat(4097)))
                .andExpect(status().isUnauthorized());
    }

    @Test void Policy_現役本人とTEAMは通る() {
        assertThatCode(() -> policy.requireActiveActor(me)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireActiveTeam(teamA)).doesNotThrowAnyException();
    }

    @Test void Policy_認証主体なしと不正IDは共通未認証で拒否する() {
        denied(() -> policy.requireActiveActor(null), CommonErrorCode.COMMON_000);
        denied(() -> policy.requireActiveActor(0L), CommonErrorCode.COMMON_000);
        denied(() -> policy.requireActiveActor(-1L), CommonErrorCode.COMMON_000);
    }

    @Test void Policy_存在しない本人は共通権限不足で拒否する() {
        denied(() -> policy.requireActiveActor(Long.MAX_VALUE), CommonErrorCode.COMMON_002);
    }

    @Test void Policy_凍結本人は共通権限不足で拒否する() {
        UserEntity frozen = users.saveAndFlush(LoyaltyTestFixture.user().toBuilder()
                .status(UserEntity.UserStatus.FROZEN).build());
        denied(() -> policy.requireActiveActor(frozen.getId()), CommonErrorCode.COMMON_002);
    }

    @Test void Policy_アーカイブ本人は共通権限不足で拒否する() {
        UserEntity archived = users.saveAndFlush(LoyaltyTestFixture.user().toBuilder()
                .archivedAt(now).build());
        denied(() -> policy.requireActiveActor(archived.getId()), CommonErrorCode.COMMON_002);
    }

    @Test void Policy_論理削除本人は共通権限不足で拒否する() {
        UserEntity deleted = users.saveAndFlush(LoyaltyTestFixture.user().toBuilder()
                .deletedAt(now).build());
        denied(() -> policy.requireActiveActor(deleted.getId()), CommonErrorCode.COMMON_002);
    }

    @Test void Policy_不在と未承諾とアーカイブと削除TEAMは不存在にする() {
        denied(() -> policy.requireActiveTeam(null), LoyaltyErrorCode.LOYALTY_001);
        denied(() -> policy.requireActiveTeam(Long.MAX_VALUE), LoyaltyErrorCode.LOYALTY_001);
        TeamEntity provisioned = teams.saveAndFlush(teamFixture("未承諾")
                .lifecycleStatus(TeamEntity.LifecycleStatus.PROVISIONED).build());
        TeamEntity archived = teams.saveAndFlush(teamFixture("アーカイブ").archivedAt(now).build());
        TeamEntity deleted = teams.saveAndFlush(teamFixture("削除").deletedAt(now).build());
        denied(() -> policy.requireActiveTeam(provisioned.getId()), LoyaltyErrorCode.LOYALTY_001);
        denied(() -> policy.requireActiveTeam(archived.getId()), LoyaltyErrorCode.LOYALTY_001);
        denied(() -> policy.requireActiveTeam(deleted.getId()), LoyaltyErrorCode.LOYALTY_001);
    }

    @Test void Policy_TEAM管理者は操作と設定を許可する() {
        member(me, teamA, RoleKind.MEMBER);
        role(me, teamA, null, "ADMIN");
        assertThatCode(() -> policy.requireOperator(me, teamA)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireSettingsAdmin(me, teamA)).doesNotThrowAnyException();
    }

    @Test void Policy_明示委任副管理者は操作だけを許可する() {
        member(me, teamA, RoleKind.MEMBER);
        role(me, teamA, null, "DEPUTY_ADMIN");
        delegate(me, teamA);
        assertThatCode(() -> policy.requireOperator(me, teamA)).doesNotThrowAnyException();
        denied(() -> policy.requireSettingsAdmin(me, teamA), LoyaltyErrorCode.LOYALTY_002);
    }

    @Test void Policy_無委任副管理者は操作も設定も拒否する() {
        member(me, teamA, RoleKind.MEMBER);
        role(me, teamA, null, "DEPUTY_ADMIN");
        denied(() -> policy.requireOperator(me, teamA), LoyaltyErrorCode.LOYALTY_002);
        denied(() -> policy.requireSettingsAdmin(me, teamA), LoyaltyErrorCode.LOYALTY_002);
    }

    @Test void Policy_別TEAMの管理者資格は流用しない() {
        member(me, teamB, RoleKind.MEMBER);
        role(me, teamB, null, "ADMIN");
        denied(() -> policy.requireOperator(me, teamA), LoyaltyErrorCode.LOYALTY_002);
        denied(() -> policy.requireSettingsAdmin(me, teamA), LoyaltyErrorCode.LOYALTY_002);
    }

    @Test void Policy_別TEAMの委任は流用しない() {
        member(me, teamA, RoleKind.MEMBER);
        member(me, teamB, RoleKind.MEMBER);
        role(me, teamA, null, "DEPUTY_ADMIN");
        role(me, teamB, null, "DEPUTY_ADMIN");
        delegate(me, teamB);
        denied(() -> policy.requireOperator(me, teamA), LoyaltyErrorCode.LOYALTY_002);
    }

    @Test void Policy_組織だけの管理者資格はTEAMへ流用しない() {
        OrganizationEntity organization = organizations.saveAndFlush(OrganizationEntity.builder()
                .name("別組織").slug("policy-org-" + UUID.randomUUID().toString().substring(0, 12))
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build());
        role(me, null, organization.getId(), "ADMIN");
        denied(() -> policy.requireOperator(me, teamA), LoyaltyErrorCode.LOYALTY_002);
        denied(() -> policy.requireSettingsAdmin(me, teamA), LoyaltyErrorCode.LOYALTY_002);
    }

    @Test void Policy_非所属SYSTEMADMINは操作と設定と所属資格へ自動昇格しない() {
        role(me, null, null, "SYSTEM_ADMIN");
        denied(() -> policy.requireOperator(me, teamA), LoyaltyErrorCode.LOYALTY_002);
        denied(() -> policy.requireSettingsAdmin(me, teamA), LoyaltyErrorCode.LOYALTY_002);
        denied(() -> policy.requireEligible(me, teamA, Audience.MEMBERS_AND_ABOVE), LoyaltyErrorCode.LOYALTY_003);
    }

    @Test void Policy_SYSTEMADMIN兼任でも当該TEAMの明示管理者資格は有効() {
        role(me, null, null, "SYSTEM_ADMIN");
        member(me, teamA, RoleKind.MEMBER);
        role(me, teamA, null, "ADMIN");
        assertThatCode(() -> policy.requireOperator(me, teamA)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireSettingsAdmin(me, teamA)).doesNotThrowAnyException();
    }

    @Test void Policy_SYSTEMADMIN兼任副管理者も明示委任で操作だけ許可する() {
        role(me, null, null, "SYSTEM_ADMIN");
        member(me, teamA, RoleKind.MEMBER);
        role(me, teamA, null, "DEPUTY_ADMIN");
        delegate(me, teamA);
        assertThatCode(() -> policy.requireOperator(me, teamA)).doesNotThrowAnyException();
        denied(() -> policy.requireSettingsAdmin(me, teamA), LoyaltyErrorCode.LOYALTY_002);
    }

    @Test void Policy_現役非所属は全認証対象だけ許可する() {
        assertThatCode(() -> policy.requireEligible(me, teamA, Audience.ALL_AUTHENTICATED)).doesNotThrowAnyException();
        denied(() -> policy.requireEligible(me, teamA, Audience.SUPPORTERS_AND_ABOVE), LoyaltyErrorCode.LOYALTY_003);
    }

    @Test void Policy_サポーターはサポーター以上だけ許可しスタッフへ昇格しない() {
        member(me, teamA, RoleKind.SUPPORTER);
        assertThatCode(() -> policy.requireEligible(me, teamA, Audience.SUPPORTERS_AND_ABOVE)).doesNotThrowAnyException();
        denied(() -> policy.requireEligible(me, teamA, Audience.MEMBERS_AND_ABOVE), LoyaltyErrorCode.LOYALTY_003);
        denied(() -> policy.requireOperator(me, teamA), LoyaltyErrorCode.LOYALTY_002);
    }

    @Test void Policy_メンバーは両所属対象で許可しスタッフへ昇格しない() {
        member(me, teamA, RoleKind.MEMBER);
        assertThatCode(() -> policy.requireEligible(me, teamA, Audience.SUPPORTERS_AND_ABOVE)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireEligible(me, teamA, Audience.MEMBERS_AND_ABOVE)).doesNotThrowAnyException();
        denied(() -> policy.requireOperator(me, teamA), LoyaltyErrorCode.LOYALTY_002);
    }

    @Test void Policy_凍結した対象本人は全認証対象でも付与しない() {
        UserEntity frozen = users.saveAndFlush(LoyaltyTestFixture.user().toBuilder()
                .status(UserEntity.UserStatus.FROZEN).build());
        denied(() -> policy.requireEligible(frozen.getId(), teamA, Audience.ALL_AUTHENTICATED), LoyaltyErrorCode.LOYALTY_003);
    }

    @Test void Policy_退会済み所属は所属対象から除く() {
        MembershipEntity left = member(me, teamA, RoleKind.MEMBER);
        left.setLeftAt(now);
        left.setLeaveReason(LeaveReason.SELF);
        left.setLeftTrigger(LeftTrigger.SELF);
        left.setLeftBy(me);
        memberships.saveAndFlush(left);
        denied(() -> policy.requireEligible(me, teamA, Audience.MEMBERS_AND_ABOVE), LoyaltyErrorCode.LOYALTY_003);
    }

    @Test void Policy_アーカイブ在籍は所属対象から除く() {
        MembershipEntity archived = member(me, teamA, RoleKind.MEMBER);
        archived.setArchivedAt(clock.instant());
        archived.setArchiveReason(ArchiveReason.DECEASED);
        memberships.saveAndFlush(archived);
        denied(() -> policy.requireEligible(me, teamA, Audience.SUPPORTERS_AND_ABOVE), LoyaltyErrorCode.LOYALTY_003);
    }

    @Test void Policy_null対象と不在本人と対象条件なしを付与しない() {
        denied(() -> policy.requireEligible(null, teamA, Audience.ALL_AUTHENTICATED), LoyaltyErrorCode.LOYALTY_003);
        denied(() -> policy.requireEligible(Long.MAX_VALUE, teamA, Audience.ALL_AUTHENTICATED), LoyaltyErrorCode.LOYALTY_003);
        denied(() -> policy.requireEligible(me, teamA, null), LoyaltyErrorCode.LOYALTY_003);
    }

    private void denied(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(expected));
    }

    private MembershipEntity member(Long user, Long team, RoleKind kind) {
        return memberships.saveAndFlush(MembershipEntity.builder().userId(user).scopeType(ScopeType.TEAM)
                .scopeId(team).roleKind(kind).joinedAt(now).invitedBy(user).build());
    }

    private void role(Long user, Long team, Long organization, String name) {
        RoleEntity definition = roles.findByName(name).orElseGet(() -> roles.saveAndFlush(RoleEntity.builder()
                .name(name).displayName(name).priority(100).isSystem(true).build()));
        userRoles.saveAndFlush(UserRoleEntity.builder().userId(user).roleId(definition.getId())
                .teamId(team).organizationId(organization).grantedBy(user).build());
    }

    private void delegate(Long user, Long team) {
        PermissionEntity permission = permissions.findByNameIn(List.of("TEAM_LOYALTY_OPERATE")).stream().findFirst()
                .orElseGet(() -> permissions.saveAndFlush(PermissionEntity.builder().name("TEAM_LOYALTY_OPERATE")
                        .displayName("チームポイント操作").scope(PermissionEntity.Scope.TEAM).build()));
        PermissionGroupEntity group = permissionGroups.saveAndFlush(PermissionGroupEntity.builder().teamId(team)
                .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN).name("ポイント委任").createdBy(user).build());
        groupPermissions.saveAndFlush(PermissionGroupPermissionEntity.builder().groupId(group.getId())
                .permissionId(permission.getId()).build());
        userGroups.saveAndFlush(UserPermissionGroupEntity.builder().userId(user).groupId(group.getId()).assignedBy(user).build());
    }

    private TeamEntity.TeamEntityBuilder<?, ?> teamFixture(String label) {
        return TeamEntity.builder().name(label).slug("policy-team-" + UUID.randomUUID().toString().substring(0, 12))
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(false);
    }

    private Long team(String label) {
        return teams.saveAndFlush(TeamEntity.builder().name(label)
                .slug("loyalty-" + UUID.randomUUID().toString().substring(0, 12))
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(false).build()).getId();
    }
    private UUID account(Long team, Long owner, int balance) {
        return store.getAccounts().saveAndFlush(LoyaltyAccountEntity.builder().teamId(team).userId(owner)
                .balance(balance).lifetimeEarned(balance).createdAt(now).updatedAt(now).build()).getId();
    }
    private UUID request(Long team, Long owner, String code, boolean closed) {
        var rule = store.getRules().findByTeamId(team).orElseGet(() -> store.getRules().saveAndFlush(LoyaltyTestFixture.rule(team)));
        UUID id = UuidV7.generate();
        var builder = LoyaltyAwardRequestEntity.builder().id(id).teamId(team).userId(owner).ruleId(rule.getId())
                .sourceLocationId(987654321L).programVersion(0).ruleVersion(0).points(1).ruleName("来店")
                .locationName("受付").awardOn(now.toLocalDate()).expiresAt(now.plusMinutes(10))
                .status(closed ? RequestStatus.CANCELLED : RequestStatus.PENDING).version(closed ? 1 : 0)
                .verificationCode(code).submitKey(UUID.randomUUID()).submitPayloadHash(new byte[32])
                .createdAt(now).updatedAt(now);
        if (closed) {
            cancelKey = UUID.randomUUID();
            builder.reason(RequestReason.USER_CANCELLED).terminalActorUserId(owner).terminalAction(RequestOperation.CANCEL)
                    .actionKey(cancelKey).actionPayloadHash(new LoyaltyPayloadHasher().action(RequestOperation.CANCEL,
                            team, id, new RequestActionRequest(0L))).closedAt(now);
        }
        return store.getRequests().saveAndFlush(builder.build()).getId();
    }
}
