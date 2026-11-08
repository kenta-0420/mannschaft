package com.mannschaft.app.role;

import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.repository.PermissionGroupRepository;
import com.mannschaft.app.role.repository.UserPermissionGroupRepository;
import com.mannschaft.app.role.batch.InviteTokenClosureCleanupBatch;
import com.mannschaft.app.role.service.InviteService;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実archive入口と、イベント未配送を実SQLで作る回復経路の試練。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@ExtendWith(OutputCaptureExtension.class)
class InviteGenerationLifecycleIT extends InviteGenerationItSupport {
    @Autowired private TeamService teamService;
    @Autowired private OrganizationService organizationService;
    @Autowired private TeamOrgMembershipRepository affiliations;
    @Autowired private InviteTokenClosureCleanupBatch recovery;
    @Autowired private InviteService invites;
    @Autowired private MembershipRepository memberships;
    @Autowired private PermissionGroupRepository permissionGroups;
    @Autowired private UserPermissionGroupRepository userPermissionGroups;
    private final List<Long> ownedAffiliations = new ArrayList<>();
    private final List<Long> ownedPermissionGroups = new ArrayList<>();

    @ParameterizedTest
    @CsvSource({"TEAM,false", "TEAM,true", "ORGANIZATION,false", "ORGANIZATION,true"})
    @DisplayName("実delete入口はcommit時だけ旧世代を失効し、rollback/既失効日時を維持する")
    void deletionPreservesCommitRollbackAndExistingTimestamp(String type, boolean rollback) {
        Scope scope = scope(type);
        Long actor = admin(scope);
        var old = token(scope, 0);
        var firstRevocation = LocalDateTime.of(2025, 2, 3, 4, 5, 6);
        var alreadyRevoked = token(scope, 0, null, null, 0, null, firstRevocation);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            if ("TEAM".equals(type)) {
                teamService.deleteTeam(scope.id(), actor);
            } else {
                organizationService.deleteOrganization(scope.id(), actor);
            }
            if (rollback) {
                status.setRollbackOnly();
            }
        });

        assertThat(jdbc.queryForObject("SELECT closure_generation FROM " + scope.table() + " WHERE id = ?", Long.class, scope.id()))
                .isEqualTo(rollback ? 0L : 1L);
        var deletedAt = jdbc.queryForObject("SELECT deleted_at FROM " + scope.table() + " WHERE id = ?", LocalDateTime.class, scope.id());
        var revokedAt = tokens.findById(old.getId()).orElseThrow().getRevokedAt();
        if (rollback) {
            assertThat(deletedAt).isNull();
            assertThat(revokedAt).isNull();
        } else {
            assertThat(deletedAt).isNotNull();
            assertThat(revokedAt).isNotNull();
        }
        assertThat(tokens.findById(alreadyRevoked.getId()).orElseThrow().getRevokedAt()).isEqualTo(firstRevocation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G12: commit後listener失敗は親休止を巻戻さず、解除後のbatchで旧tokenだけ回復する")
    void committedListenerFailureIsRecoveredAfterReopening(String type, CapturedOutput output) {
        assertThat(MYSQL.isRunning()).isTrue();
        assertThat(jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL())).isEqualTo(MYSQL.getJdbcUrl());
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(MYSQL.getDatabaseName());
        Scope scope = scope(type);
        var old = token(scope, 0);
        String constraintName = "it_invite_listener_failure_" + old.getId();
        addOwnedRevokeFailure(constraintName, old);
        try {
            assertOwnedRevokeFailure(constraintName, old);
            assertThatCode(() -> archive(scope, false)).doesNotThrowAnyException();
            assertThat(jdbc.queryForObject("SELECT closure_generation FROM " + scope.table() + " WHERE id = ?", Long.class, scope.id())).isEqualTo(1);
            assertThat(tokens.findById(old.getId()).orElseThrow().getRevokedAt()).isNull();
            assertThat(output.getAll()).contains("scopeId=" + scope.id());
        } finally {
            jdbc.execute("ALTER TABLE invite_tokens DROP CHECK " + constraintName);
        }
        if ("TEAM".equals(type)) {
            teamService.unarchiveTeam(scope.id());
        } else {
            organizationService.unarchiveOrganization(scope.id());
        }
        var current = token(scope, 1);

        recovery.cleanUp();

        assertThat(tokens.findById(old.getId()).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(tokens.findById(current.getId()).orElseThrow().getRevokedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G12: 1scopeの実DB失効失敗は他scopeを止めず、次batchで取り直す")
    void recoveryContinuesAfterDatabaseFailureAndRepairsOnNextRun(String type, CapturedOutput output) {
        assertThat(MYSQL.isRunning()).as("一時CHECKは起動中のTestcontainersだけに作成する").isTrue();
        assertThat(jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL()))
                .isEqualTo(MYSQL.getJdbcUrl());
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(MYSQL.getDatabaseName());
        Scope failing = scope(type);
        Scope other = scope(type);
        var siblingOld = token(failing, 0);
        var blockedToken = token(failing, 0);
        var otherToken = token(other, 0);
        for (Scope scope : List.of(failing, other)) {
            jdbc.update("UPDATE " + scope.table() + " SET closure_generation = 1 WHERE id = ?", scope.id());
        }
        var sameGeneration = token(failing, 1);
        String constraintName = "it_invite_revoke_failure_" + blockedToken.getId();
        addOwnedRevokeFailure(constraintName, blockedToken);
        try {
            assertOwnedRevokeFailure(constraintName, blockedToken);
            recovery.cleanUp();
            assertThat(tokens.findById(blockedToken.getId()).orElseThrow().getRevokedAt()).isNull();
            assertThat(tokens.findById(siblingOld.getId()).orElseThrow().getRevokedAt()).as("失敗scopeの失効TXを全体rollbackする").isNull();
            assertThat(tokens.findById(otherToken.getId()).orElseThrow().getRevokedAt()).isNotNull();
            assertThat(output.getAll()).contains("scopeId=" + failing.id());
        } finally {
            jdbc.execute("ALTER TABLE invite_tokens DROP CHECK " + constraintName);
        }

        recovery.cleanUp();
        assertThat(tokens.findById(blockedToken.getId()).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(tokens.findById(siblingOld.getId()).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(tokens.findById(sameGeneration.getId()).orElseThrow().getRevokedAt()).isNull();
    }

    /** 既存ConfirmableNotificationReminderBatchTransactionIT同型。TRIGGER/SUPER権限不要の最小DDL例外。 */
    private void addOwnedRevokeFailure(String constraintName, InviteTokenEntity token) {
        // 呼出元の専用container/URL/schema確認後、所有する不変UUIDだけを対象にする。
        assertThat(UUID.fromString(token.getToken()).toString()).isEqualTo(token.getToken());
        jdbc.execute("ALTER TABLE invite_tokens ADD CONSTRAINT " + constraintName
                + " CHECK (revoked_at IS NULL OR token <> '" + token.getToken() + "')");
    }

    private void assertOwnedRevokeFailure(String constraintName, InviteTokenEntity token) {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                jdbc.update("UPDATE invite_tokens SET revoked_at = UTC_TIMESTAMP() WHERE id = ?", token.getId())))
                .isInstanceOf(DataAccessException.class)
                .satisfies(failure -> {
                    Throwable cause = failure;
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    assertThat(cause).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) cause).getErrorCode()).isEqualTo(3819);
                    assertThat(cause.getMessage()).contains(constraintName);
                });
        assertThat(tokens.findById(token.getId()).orElseThrow().getRevokedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G18: 休止・失効・解除は既存在籍/権限ロール/権限groupの全列を変更しない")
    void closureAndRecoveryKeepExistingMembershipRoleAndPermissionGroup(String type) {
        Scope scope = scope(type);
        Long member = actorWithRole(scope, "DEPUTY_ADMIN", 3);
        inTx(() -> {
            memberships.saveAndFlush(MembershipEntity.builder().userId(member).scopeType(ScopeType.valueOf(type))
                    .scopeId(scope.id()).roleKind(RoleKind.MEMBER).joinedAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build());
            var builder = PermissionGroupEntity.builder().name("世代試練権限")
                    .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN).createdBy(member);
            if (type.equals("TEAM")) {
                builder.teamId(scope.id());
            } else {
                builder.organizationId(scope.id());
            }
            Long group = permissionGroups.saveAndFlush(builder.build()).getId();
            ownedPermissionGroups.add(group);
            userPermissionGroups.saveAndFlush(UserPermissionGroupEntity.builder().userId(member).groupId(group).assignedBy(member).build());
            return null;
        });
        var tables = List.of("memberships", "user_roles", "user_permission_groups");
        var before = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " WHERE user_id = ? ORDER BY id", member)).toList();
        assertThat(before).allSatisfy(rows -> assertThat(rows).isNotEmpty());
        token(scope, 0);

        archive(scope, false);
        recovery.cleanUp();
        if (type.equals("TEAM")) {
            teamService.unarchiveTeam(scope.id());
        } else {
            organizationService.unarchiveOrganization(scope.id());
        }
        recovery.cleanUp();

        var after = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " WHERE user_id = ? ORDER BY id", member)).toList();
        assertThat(after).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"TEAM,false", "TEAM,true", "ORGANIZATION,false", "ORGANIZATION,true"})
    @DisplayName("G12: イベント未配送の閉鎖/解除後でも回復batchが旧世代だけを失効する")
    void recoveryRepairsMissingEventWithoutRevokingNewGeneration(String type, boolean reopened) {
        Scope scope = scope(type);
        var old = token(scope, 0);
        // listener未配送を作るためだけの直接UPDATE。実archive経路は別の試練で通す。
        jdbc.update("UPDATE " + scope.table() + " SET closure_generation = 1, archived_at = "
                + (reopened ? "NULL" : "UTC_TIMESTAMP()") + " WHERE id = ?", scope.id());
        var current = reopened ? token(scope, 1) : null;

        recovery.cleanUp();

        var revokedAt = tokens.findById(old.getId()).orElseThrow().getRevokedAt();
        assertThat(revokedAt).isNotNull();
        recovery.cleanUp();
        assertThat(tokens.findById(old.getId()).orElseThrow().getRevokedAt()).isEqualTo(revokedAt);
        if (current != null) {
            assertThat(tokens.findById(current.getId()).orElseThrow().getRevokedAt()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G13: 実archiveがrollbackした場合は世代もtokenも変更しない")
    void rollbackDoesNotDeliverClosureCleanup(String type) {
        Scope scope = scope(type);
        var old = token(scope, 0);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            archive(scope, false);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT closure_generation FROM " + scope.table() + " WHERE id = ?", Long.class, scope.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT archived_at FROM " + scope.table() + " WHERE id = ?", LocalDateTime.class, scope.id())).isNull();
        assertThat(tokens.findById(old.getId()).orElseThrow().getRevokedAt()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"TEAM,false", "TEAM,true", "ORGANIZATION,false"})
    @DisplayName("実archiveの全サービス入口はcommit後にそのscopeの旧tokenを失効する")
    void committedArchiveDeliversTokenCleanup(String type, boolean deferred) {
        Scope scope = scope(type);
        var old = token(scope, 0);

        archive(scope, deferred);

        assertThat(jdbc.queryForObject("SELECT closure_generation FROM " + scope.table() + " WHERE id = ?", Long.class, scope.id())).isEqualTo(1);
        assertThat(tokens.findById(old.getId()).orElseThrow().getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("G16/G17: 組織休止は所属teamの状態/世代/tokenとACTIVE加盟を変更しない")
    void organizationClosureDoesNotPropagateToAffiliatedTeam() {
        Scope org = scope("ORGANIZATION");
        Scope team = scope("TEAM");
        var teamToken = token(team, 0);
        Long affiliation = inTx(() -> affiliations.saveAndFlush(TeamOrgMembershipEntity.builder()
                .teamId(team.id()).organizationId(org.id()).status(TeamOrgMembershipEntity.Status.ACTIVE)
                .invitedAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build()).getId());
        ownedAffiliations.add(affiliation);
        var before = jdbc.queryForMap("SELECT * FROM team_org_memberships WHERE id = ?", affiliation);
        assertThat(invites.previewInvite(teamToken.getToken()).getData().isValid()).isTrue();

        organizationService.archiveOrganization(org.id());

        assertThat(jdbc.queryForObject("SELECT archived_at FROM teams WHERE id = ?", LocalDateTime.class, team.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT closure_generation FROM teams WHERE id = ?", Long.class, team.id())).isZero();
        assertThat(tokens.findById(teamToken.getId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(tokens.findById(teamToken.getId()).orElseThrow().getScopeClosureGeneration()).isZero();
        assertThat(invites.previewInvite(teamToken.getToken()).getData().isValid()).isTrue();
        assertThat(jdbc.queryForMap("SELECT * FROM team_org_memberships WHERE id = ?", affiliation)).isEqualTo(before);
    }

    private void archive(Scope scope, boolean deferred) {
        if ("ORGANIZATION".equals(scope.type())) {
            organizationService.archiveOrganization(scope.id());
        } else if (deferred) {
            teamService.archiveTeamDeferringAffiliationCleanup(scope.id());
        } else {
            teamService.archiveTeam(scope.id());
        }
    }

    @AfterEach
    void removeOwnedAffiliations() {
        for (Long id : ownedAffiliations) {
            jdbc.update("DELETE FROM team_org_memberships WHERE id = ?", id);
        }
        for (Long group : ownedPermissionGroups) {
            jdbc.update("DELETE FROM user_permission_groups WHERE group_id = ?", group);
            jdbc.update("DELETE FROM permission_groups WHERE id = ?", group);
        }
    }
}
