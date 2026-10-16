package com.mannschaft.app.role;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.dto.InviteScopeState;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.service.InviteScopeStatePort;
import com.mannschaft.app.role.service.InviteService;
import com.mannschaft.app.role.service.InviteTokenClosureCleanupRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #3892 物理TX分離・新しいprimary読取・strict世代cleanupの試練。
 * Bean/DBをモックせず、未commitの親変更と別connectionのcommitを実SQL/latchで作る。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("個人招待世代の独立TXと並行整合性")
class InviteGenerationConsistencyIT extends InviteGenerationItSupport {
    @Autowired private List<InviteScopeStatePort> ports;
    @Autowired private InviteService invites;
    @Autowired private InviteTokenClosureCleanupRunner cleanup;
    @Autowired private ConfigurableApplicationContext context;

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G15: 親判定後に重なる閉鎖は受諾先行として成立し、閉鎖後も在籍を残す")
    void overlappingClosureAfterJoinDecisionKeepsAcceptedMembership(String type) {
        Scope scope = scope(type);
        var token = token(scope, 0);
        Long target = user();
        ApplicationListener<PayloadApplicationEvent<?>> listener = event -> {
            Object payload = event.getPayload();
            boolean ownJoin = payload instanceof com.mannschaft.app.team.event.TeamMemberAuditEvent joined
                    && type.equals("TEAM") && target.equals(joined.getTargetUserId()) && scope.id().equals(joined.getTeamId());
            ownJoin |= payload instanceof com.mannschaft.app.organization.event.OrganizationMemberAuditEvent joined
                    && type.equals("ORGANIZATION") && target.equals(joined.getTargetUserId()) && scope.id().equals(joined.getOrganizationId());
            if (ownJoin) {
                // 実joinの監査イベントは親判定と加入の後、role TXのcommitより前。動作は置換しない。
                TransactionTemplate closing = new TransactionTemplate(transactionManager);
                closing.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                closing.executeWithoutResult(status -> jdbc.update("UPDATE " + scope.table()
                        + " SET archived_at = UTC_TIMESTAMP(), closure_generation = 1 WHERE id = ?", scope.id()));
            }
        };
        ApplicationEventMulticaster multicaster = context.getBean("applicationEventMulticaster", ApplicationEventMulticaster.class);
        multicaster.addApplicationListener(listener);
        try {
            invites.joinByInvite(token.getToken(), target);
        } finally {
            multicaster.removeApplicationListener(listener);
        }
        assertThat(tokens.findById(token.getId()).orElseThrow().getUsedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memberships WHERE user_id = ? AND left_at IS NULL", Long.class, target)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT closure_generation FROM " + scope.table() + " WHERE id = ?", Long.class, scope.id())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G21: 親readerは外側TXの未commit変更を読まず、外側connectionを復元する")
    void parentReaderUsesIndependentPrimaryTransaction(String type) {
        Scope scope = scope(type);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Long outerConnection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            jdbc.update("UPDATE " + scope.table() + " SET closure_generation = 99 WHERE id = ?", scope.id());

            // 同じ物理TXなら99が見える。独立したnonlocking primary読取ならcommit済み0が見える。
            assertThat(port(type).readCommittedStateForInviteDecision(scope.id()))
                    .contains(new InviteScopeState(false, false, false, 0));
            assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isEqualTo(outerConnection);
            assertThat(jdbc.queryForObject("SELECT closure_generation FROM " + scope.table() + " WHERE id = ?",
                    Long.class, scope.id())).isEqualTo(99L);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT closure_generation FROM " + scope.table() + " WHERE id = ?",
                Long.class, scope.id())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G14: 発行後の親再確認は進行中の閉鎖commitを共有lockで待って新世代を読む")
    void postIssueRecheckWaitsForCommittedClosure(String type) throws Exception {
        Scope scope = scope(type);
        CountDownLatch parentLocked = new CountDownLatch(1);
        CountDownLatch commitClosure = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var closure = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.update("UPDATE " + scope.table() + " SET archived_at = UTC_TIMESTAMP(), closure_generation = 1 WHERE id = ?",
                        scope.id());
                parentLocked.countDown();
                await(commitClosure);
            }));
            assertThat(parentLocked.await(15, TimeUnit.SECONDS)).isTrue();
            var reading = executor.submit(() -> port(type).recheckCommittedStateAfterInviteIssue(scope.id()));
            try {
                assertThatThrownBy(() -> reading.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                commitClosure.countDown();
            }
            closure.get(15, TimeUnit.SECONDS);
            assertThat(reading.get(15, TimeUnit.SECONDS)).contains(new InviteScopeState(true, false, false, 1));
        } finally {
            commitClosure.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G15: 外側RR snapshotが古くても閉鎖完了後のjoinは新しいprimary世代で拒否する")
    void joinDoesNotReuseOuterRepeatableReadSnapshot(String type) throws Exception {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0);
        Long userId = user();
        CountDownLatch snapshotEstablished = new CountDownLatch(1);
        CountDownLatch closureCommitted = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var joining = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("SELECT closure_generation FROM " + scope.table() + " WHERE id = ?",
                        Long.class, scope.id())).isZero();
                snapshotEstablished.countDown();
                await(closureCommitted);
                invites.joinByInvite(token.getToken(), userId);
            }));
            assertThat(snapshotEstablished.await(15, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE " + scope.table()
                    + " SET archived_at = UTC_TIMESTAMP(), closure_generation = 1 WHERE id = ?", scope.id());
            closureCommitted.countDown();
            assertThatThrownBy(() -> joining.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .satisfies(error -> {
                        assertThat(error.getCause()).isInstanceOf(BusinessException.class);
                        assertThat(((BusinessException) error.getCause()).getErrorCode()).isEqualTo(RoleErrorCode.ROLE_002);
                    });
        } finally {
            closureCommitted.countDown();
        }
        for (String table : List.of("memberships", "user_roles", "user_permission_groups")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Long.class, userId)).isZero();
        }
        assertThat(tokens.findById(token.getId()).orElseThrow().getUsedCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G10/G11: 全種類の旧世代だけ失効し、新世代・他scope・既失効日時を維持する")
    void cleanupIsStrictCompleteAndIdempotent(String type) {
        Scope scope = scope(type);
        Scope other = scope(type);
        Long target = user();
        List<InviteTokenEntity> old = List.of(token(scope, 0),
                token(scope, 0, LocalDateTime.of(2020, 1, 1, 0, 0), null, 0, null, null),
                token(scope, 0, null, 1, 1, null, null),
                token(scope, 0, null, 1, 0, target, null));
        InviteTokenEntity fresh = token(scope, 1);
        InviteTokenEntity unrelated = token(other, 0);
        LocalDateTime existingTimestamp = LocalDateTime.of(2025, 2, 3, 4, 5, 6);
        InviteTokenEntity revoked = token(scope, 0, null, null, 0, null, existingTimestamp);

        assertThat(cleanup.revokeBeforeGeneration(type, scope.id(), 1)).isEqualTo(4);
        List<LocalDateTime> first = old.stream().map(t -> tokens.findById(t.getId()).orElseThrow().getRevokedAt()).toList();
        assertThat(first).doesNotContainNull();
        assertThat(tokens.findById(fresh.getId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(tokens.findById(unrelated.getId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(tokens.findById(revoked.getId()).orElseThrow().getRevokedAt()).isEqualTo(existingTimestamp);
        assertThat(cleanup.revokeBeforeGeneration(type, scope.id(), 1)).isZero();
        assertThat(old.stream().map(t -> tokens.findById(t.getId()).orElseThrow().getRevokedAt()).toList()).isEqualTo(first);
    }

    private InviteScopeStatePort port(String type) {
        return ports.stream().filter(p -> p.scopeType().equals(type)).findFirst().orElseThrow();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("試練の同期点が期限内に到着しない");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("試練の同期が中断された", e);
        }
    }
}
