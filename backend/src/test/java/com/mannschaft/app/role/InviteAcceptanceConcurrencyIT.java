package com.mannschaft.app.role;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.dto.BlockRequest;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.service.BlockService;
import com.mannschaft.app.role.service.MembershipGrantService;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.team.TeamErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260826-1457 W1 前段: 承諾の並行性（AC C1〜C7・J1〜J3・K2・K3・R5）。
 *
 * <p>Bean を差し替えず、実DB の行ロックと別接続 commit・{@link CountDownLatch} で競合を作る。
 * T6 と T7 の間に割り込む決定的な競合（C2・C3・C6 の release 版）と C8 は、止め所（spy）が要るため
 * {@code InviteAcceptanceFailureIT} に置く。</p>
 *
 * <p>「特性」は main でも緑が正しい（承諾は Facade があれば Facade、無ければ現行 InviteService）。「red」は main で落ちる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-1457 招待承諾の並行性")
class InviteAcceptanceConcurrencyIT extends InviteAcceptanceItSupport {

    @Autowired private MembershipGrantService grants;
    @Autowired private RoleService roleService;
    @Autowired private BlockService blockService;

    // ------------------------------------------------------------------
    // C1〜C3
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("C1 red: 上限1に6人同時→成功1、used_count=1、claim は GRANTED 1件のみ")
    void manyUsersRaceForLastSeat(String type) throws Exception {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 1, 0, null, null);
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            users.add(user());
        }
        List<Throwable> results = race(users.stream().<Runnable>map(u -> () -> acceptCompat(plaintext(token), u, null)).toList());
        assertThat(results.stream().filter(r -> r == null)).hasSize(1);
        assertThat(results.stream().filter(r -> r != null))
                .allSatisfy(r -> assertThat(((BusinessException) r).getErrorCode()).isEqualTo(RoleErrorCode.ROLE_003));
        assertThat(usedCount(token.getId())).isEqualTo(1);
        assertThat(claimStatuses(token.getId())).containsExactly("GRANTED");
    }

    @ParameterizedTest
    @CsvSource({"TEAM,1", "TEAM,3", "ORGANIZATION,1", "ORGANIZATION,3"})
    @DisplayName("C2 red: 同一人の二重送信→上限1なら後続 ROLE_003、余裕ありなら後続 409。回数と非RELEASED claim はちょうど1")
    void sameUserDoubleSubmit(String type, int maxUses) throws Exception {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, maxUses, 0, null, null);
        Long userId = user();
        List<Throwable> results = race(List.of(() -> acceptCompat(plaintext(token), userId, null),
                () -> acceptCompat(plaintext(token), userId, null)));
        assertThat(results.stream().filter(r -> r == null)).hasSize(1);
        BusinessException loser = (BusinessException) results.stream().filter(r -> r != null).findFirst().orElseThrow();
        if (maxUses == 1) {
            assertThat(loser.getErrorCode()).isEqualTo(RoleErrorCode.ROLE_003);
        } else {
            assertThat(loser.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_003);
            assertThat(loser.getHttpStatusOverride()).isEqualTo(HttpStatus.CONFLICT);
        }
        assertThat(usedCount(token.getId())).isEqualTo(1);
        assertThat(claimStatuses(token.getId()).stream().filter(s -> !"RELEASED".equals(s))).containsExactly("GRANTED");
        assertThat(userRoleCount(userId, scope)).isEqualTo(1);
        assertThat(membershipCount(userId, scope)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("C3 red: 同一人が別招待で同じ所へ同時→1件成功、他は 409 で回数戻る")
    void sameUserTwoTokensSameScope(String type) throws Exception {
        Scope scope = scope(type);
        InviteTokenEntity first = token(scope, 0, null, 5, 0, null, null);
        InviteTokenEntity second = token(scope, 0, null, 5, 0, null, null);
        Long userId = user();
        List<Throwable> results = race(List.of(() -> acceptCompat(plaintext(first), userId, null),
                () -> acceptCompat(plaintext(second), userId, null)));
        assertThat(results.stream().filter(r -> r == null)).hasSize(1);
        assertThat(results.stream().filter(r -> r != null))
                .singleElement().satisfies(r -> assertThat(((BusinessException) r).getErrorCode()).isEqualTo(TeamErrorCode.TEAM_003));
        assertThat(usedCount(first.getId()) + usedCount(second.getId())).isEqualTo(1);
        long live = claimStatuses(first.getId()).stream().filter(s -> !"RELEASED".equals(s)).count()
                + claimStatuses(second.getId()).stream().filter(s -> !"RELEASED".equals(s)).count();
        assertThat(live).isEqualTo(1);
        assertThat(userRoleCount(userId, scope)).isEqualTo(1);
        assertThat(membershipCount(userId, scope)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // C4: ロック順と退会競合
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("C4 red: T1 後 T7 前に退会（非ACTIVE化）が確定→T7 で ROLE_002、全巻き戻しで回数も戻る（claim RELEASED）")
    void withdrawalBetweenPreCheckAndGrant(String type) throws Exception {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Long userId = user();
        CountDownLatch tokenLocked = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicLong blocker = new AtomicLong();
        AtomicReference<RowLockProof> proof = new AtomicReference<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            // 招待行を握り、承諾を T6（招待行ロック）で待たせる。その間に退会を確定させる。
            Future<?> withdrawal = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                try {
                    blocker.set(connectionId());
                    jdbc.queryForObject("SELECT id FROM invite_tokens WHERE id = ? FOR UPDATE", Long.class, token.getId());
                    proof.set(captureTokenLock(blocker.get(), token.getId()));
                } finally {
                    tokenLocked.countDown();
                }
                await(proceed);
                jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", userId);
            }));
            Future<?> joining = null;
            Throwable withdrawalError;
            Throwable joinError;
            try {
                await(tokenLocked);
                RowLockProof captured = capturedProof(proof, withdrawal);
                // UUID tokenと新scopeはこの試験が所有し、当worker以外のconsumerへ渡さない。
                joining = executor.submit(() -> acceptCompat(plaintext(token), userId, null));
                awaitRowWait(captured, joining);
            } finally {
                proceed.countDown();
                withdrawalError = outcome(withdrawal);
                joinError = joining == null ? null : outcome(joining);
            }
            assertThat(withdrawalError).as("退会側はデッドロックせず commit する").isNull();
            assertThat(joinError).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(RoleErrorCode.ROLE_002));
        }
        assertThat(usedCount(token.getId())).isZero();
        assertThat(claimStatuses(token.getId())).containsExactly("RELEASED");
        assertThat(userRoleCount(userId, scope)).isZero();
        assertThat(membershipCount(userId, scope)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"TEAM,REVOKE", "TEAM,REMOVE", "TEAM,BLOCK", "TEAM,UNBLOCK", "TEAM,WITHDRAW",
            "ORGANIZATION,REVOKE", "ORGANIZATION,REMOVE", "ORGANIZATION,BLOCK", "ORGANIZATION,UNBLOCK", "ORGANIZATION,WITHDRAW"})
    @DisplayName("C4 特性: 承諾と取消し・除名・block/unblock・退会の交差でデッドロックしない（各5巡）")
    void noDeadlockAgainstCrossingOperations(String type, String operation) throws Exception {
        Scope scope = scope(type);
        Long admin = admin(scope);
        for (int round = 0; round < 5; round++) {
            InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
            Long userId = user();
            if ("UNBLOCK".equals(operation)) {
                block(scope, userId, admin);
            }
            Runnable crossing = switch (operation) {
                case "REVOKE" -> () -> inviteService.revokeInviteToken(token.getId(), admin);
                case "REMOVE" -> () -> roleService.removeMemberWithoutAdminCheck(scope.id(), type, userId);
                case "BLOCK" -> () -> blockService.blockUser(scope.id(), type, new BlockRequest(userId, "試練"), admin);
                case "UNBLOCK" -> () -> blockService.unblockUser(scope.id(), type, userId, admin);
                default -> () -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                    jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
                    jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", userId);
                });
            };
            List<Throwable> results = race(List.of(() -> acceptCompat(plaintext(token), userId, null), crossing));
            for (Throwable r : results) {
                assertThat(r == null || r instanceof BusinessException)
                        .as("round " + round + " " + operation + ": 業務エラー以外（デッドロック・ロック待ち超過等）が出ない: " + r)
                        .isTrue();
            }
            assertThat(userRoleCount(userId, scope)).isLessThanOrEqualTo(1);
            assertThat(membershipCount(userId, scope)).isLessThanOrEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // C5: 外側 RR TX
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("C5a red: 外側RRで preview 済み→別TXが上限まで使用→join は ROLE_003、回数不変")
    void outerSnapshotDoesNotHideExhaustion(String type) throws Exception {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 1, 0, null, null);
        Long outerUser = user();
        Long otherUser = user();
        AtomicReference<Throwable> joinError = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            inviteService.previewInvite(plaintext(token));
            runElsewhere(() -> acceptCompat(plaintext(token), otherUser, null));
            try {
                acceptCompat(plaintext(token), outerUser, null);
            } catch (RuntimeException e) {
                joinError.set(e);
            }
            status.setRollbackOnly();
        });
        assertThat(joinError.get()).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(RoleErrorCode.ROLE_003));
        assertThat(usedCount(token.getId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("C5b 特性: 同一外側TXで承諾2回→2回目 409・rollback-only、外側コミット失敗後はどれも残らない")
    void sameOuterTransactionTwice(String type) {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Long userId = user();
        AtomicReference<Throwable> second = new AtomicReference<>();
        AtomicReference<Throwable> third = new AtomicReference<>();
        Throwable commit = null;
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                acceptCompat(plaintext(token), userId, null);
                try {
                    acceptCompat(plaintext(token), userId, null);
                } catch (RuntimeException e) {
                    second.set(e);
                }
                assertThat(status.isRollbackOnly()).as("業務エラー後は rollback-only").isTrue();
                try {
                    acceptCompat(plaintext(token), userId, null);
                } catch (RuntimeException e) {
                    third.set(e);
                }
            });
        } catch (RuntimeException e) {
            commit = e;
        }
        assertThat(second.get()).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_003));
        assertThat(third.get()).isInstanceOf(BusinessException.class);
        assertThat(commit).as("外側コミットは失敗する").isNotNull();
        assertNothingRemains(scope, token, userId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("C5b 特性: 外側TXのコミット時例外で claims・回数・参加・配置のどれも残らない")
    void outerCommitFailureLeavesNothing(String type) {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Long userId = user();
        Throwable commit = null;
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                acceptCompat(plaintext(token), userId, null);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("試練: 外側コミット失敗");
                    }
                });
            });
        } catch (RuntimeException e) {
            commit = e;
        }
        assertThat(commit).hasMessageContaining("外側コミット失敗");
        assertNothingRemains(scope, token, userId);
    }

    private void assertNothingRemains(Scope scope, InviteTokenEntity token, Long userId) {
        assertThat(usedCount(token.getId())).isZero();
        assertThat(claimCountOrZero(token.getId())).isZero();
        assertThat(userRoleCount(userId, scope)).isZero();
        assertThat(membershipCount(userId, scope)).isZero();
        assertThat(folderPlacements(userId, scope)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("C5c red: 外側RRで preview 済み→別TXが1件消費→join 成功で同TX内 Entity が DB 値と一致、コミット後 b+2")
    void outerSnapshotEntityIsRefreshed(String type) throws Exception {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Long outerUser = user();
        Long otherUser = user();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            inviteService.previewInvite(plaintext(token));
            InviteTokenEntity managed = tokens.findById(token.getId()).orElseThrow();
            assertThat(managed.getUsedCount()).isZero();
            runElsewhere(() -> acceptCompat(plaintext(token), otherUser, null));
            acceptCompat(plaintext(token), outerUser, null);
            Integer current = jdbc.queryForObject("SELECT used_count FROM invite_tokens WHERE id = ? FOR UPDATE",
                    Integer.class, token.getId());
            assertThat(current).isEqualTo(2);
            assertThat(tokens.findById(token.getId()).orElseThrow().getUsedCount())
                    .as("管理下 Entity は refresh 済み").isEqualTo(current);
        });
        assertThat(usedCount(token.getId())).isEqualTo(2);
        assertThat(claimStatuses(token.getId())).containsExactly("GRANTED", "GRANTED");
    }

    // ------------------------------------------------------------------
    // C6・C7
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("C6 red: 招待と JoinRequest 承認（grantMemberRole）が同時→user_roles・memberships 各1件、招待が負ければ 409 で回数は残らない"
            + "（main では招待側に一意制約違反が出うる）")
    void inviteAndJoinRequestApprovalRace(String type) throws Exception {
        Scope scope = scope(type);
        Long admin = admin(scope);
        for (int round = 0; round < 5; round++) {
            InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
            Long userId = user();
            List<Throwable> results = race(List.of(() -> acceptCompat(plaintext(token), userId, null),
                    () -> grants.grantMemberRole(type, scope.id(), userId, admin, "JOIN_REQUEST")));
            assertThat(userRoleCount(userId, scope)).isEqualTo(1);
            assertThat(membershipCount(userId, scope)).isEqualTo(1);
            assertThat(results.get(1)).as("承認側も業務上の失敗以外を出さない").isNull();
            if (results.get(0) != null) {
                assertThat(results.get(0)).isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_003));
                assertThat(usedCount(token.getId())).isZero();
            } else {
                assertThat(usedCount(token.getId())).isEqualTo(1);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("C7 red: T3（ブロック判定）後 T7 前にブロック確定→参加成立（既存同種リスクとして固定）")
    void blockCommittedAfterBlockCheck(String type) throws Exception {
        Scope scope = scope(type);
        Long admin = admin(scope);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Long userId = user();
        CountDownLatch tokenLocked = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicLong blocker = new AtomicLong();
        AtomicLong blockerLockedAt = new AtomicLong();
        AtomicReference<RowLockProof> proof = new AtomicReference<>();
        AtomicReference<Thread> joiningThread = new AtomicReference<>();
        try (var executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2)) {
            Future<?> blocking = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                try {
                    blocker.set(connectionId());
                    jdbc.queryForObject("SELECT id FROM invite_tokens WHERE id = ? FOR UPDATE", Long.class, token.getId());
                    blockerLockedAt.set(System.nanoTime());
                    proof.set(captureTokenLock(blocker.get(), token.getId()));
                } finally {
                    tokenLocked.countDown();
                }
                await(proceed);
                block(scope, userId, admin);
            }));
            Future<?> joining = null;
            Throwable blockingError;
            Throwable joiningError;
            try {
                await(tokenLocked);
                RowLockProof captured = capturedProof(proof, blocking);
                // requester接続は辺由来の観測値。当workerだけが所有UUID tokenを消費する前提で同期する。
                joining = executor.submit(() -> {
                    joiningThread.set(Thread.currentThread());
                    acceptCompat(plaintext(token), userId, null);
                });
                awaitRowWait(captured, joining, joiningThread, executor,
                        blockerLockedAt.get());
            } finally {
                proceed.countDown();
                blockingError = outcome(blocking);
                joiningError = joining == null ? null : outcome(joining);
            }
            assertThat(blockingError).isNull();
            assertThat(joiningError).as("T3 の結果で判定し、参加は成立する").isNull();
        }
        assertThat(userRoleCount(userId, scope)).isEqualTo(1);
        assertThat(membershipCount(userId, scope)).isEqualTo(1);
        assertThat(usedCount(token.getId())).isEqualTo(1);
        assertThat(claimStatuses(token.getId())).containsExactly("GRANTED");
    }

    // ------------------------------------------------------------------
    // J1〜J3: JoinRequest 側の現状固定（特性）
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("J1 特性: 非ACTIVE ユーザーの JoinRequest 承認（grantMemberRole）は現行どおり付与する")
    void joinRequestGrantsInactiveUser(String type) {
        Scope scope = scope(type);
        Long admin = admin(scope);
        Long userId = inactiveUser("FROZEN");
        grants.grantMemberRole(type, scope.id(), userId, admin, "JOIN_REQUEST");
        assertThat(userRoleCount(userId, scope)).isEqualTo(1);
        assertThat(membershipCount(userId, scope)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("J2 特性: user_roles のみ残存の JoinRequest 承認は一意制約違反（現行結果を固定・改善は (c)）")
    void joinRequestWithLeftoverUserRole(String type) {
        Scope scope = scope(type);
        Long admin = admin(scope);
        Long userId = user();
        insertUserRole(scope, userId);
        String unique = addUserRoleScopeUnique(userId);
        Throwable error;
        try {
            error = catching(() -> grants.grantMemberRole(type, scope.id(), userId, admin, "JOIN_REQUEST"));
        } finally {
            dropUserRoleScopeUnique(unique);
        }
        assertThat(error).isNotNull();
        assertThat(chainContains(error, DataIntegrityViolationException.class)
                || chainContains(error, java.sql.SQLIntegrityConstraintViolationException.class))
                .as("一意制約違反のまま: " + error).isTrue();
        assertThat(userRoleCount(userId, scope)).isEqualTo(1);
        assertThat(membershipCount(userId, scope)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("J3 特性: 承認側の事前確認後に招待が先にコミット→招待成功、承認側は一意制約違反（現行結果を固定）")
    void joinRequestLosesToCommittedInvite(String type) throws Exception {
        Scope scope = scope(type);
        Long admin = admin(scope);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Long userId = user();
        String unique = addUserRoleScopeUnique(userId);
        Throwable approval;
        try {
            approval = catching(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                // 承認側の事前確認（スナップショット確立）
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memberships WHERE user_id = ? AND left_at IS NULL",
                        Long.class, userId)).isZero();
                runElsewhere(() -> acceptCompat(plaintext(token), userId, null));
                grants.grantMemberRole(type, scope.id(), userId, admin, "JOIN_REQUEST");
            }));
        } finally {
            dropUserRoleScopeUnique(unique);
        }
        assertThat(approval).isNotNull();
        assertThat(chainContains(approval, DataIntegrityViolationException.class)
                || chainContains(approval, java.sql.SQLIntegrityConstraintViolationException.class))
                .as("承認側は一意制約違反: " + approval).isTrue();
        assertThat(usedCount(token.getId())).isEqualTo(1);
        assertThat(userRoleCount(userId, scope)).isEqualTo(1);
        assertThat(membershipCount(userId, scope)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // K2・K3・R5
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("K2 red: 上限 M=2 で claim 2件が停止中→他者は ROLE_003、付与 ≤ M、自動 release なし")
    void stalledClaimsBlockNewcomers(String type) {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 2, 0, null, null);
        UUID a = insertClaim(token.getId(), user(), "CLAIMED", utcNow().minusHours(2), true);
        UUID b = insertClaim(token.getId(), user(), "CLAIMED", utcNow().minusHours(2), true);
        Long newcomer = user();
        assertError(business(() -> accept(plaintext(token), newcomer)), RoleErrorCode.ROLE_003, null);
        assertThat(usedCount(token.getId())).isEqualTo(2);
        assertThat(claimStatus(a)).isEqualTo("CLAIMED");
        assertThat(claimStatus(b)).isEqualTo("CLAIMED");
        String column = "TEAM".equals(type) ? "team_id" : "organization_id";
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE " + column + " = ?", Long.class, scope.id()))
                .as("付与 ≤ M").isLessThanOrEqualTo(2L);
        assertThat(userRoleCount(newcomer, scope)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"TEAM,REMOVE", "TEAM,BLOCK", "ORGANIZATION,REMOVE", "ORGANIZATION,BLOCK"})
    @DisplayName("K3 red: 付与成功後の退会・ブロックで claim は GRANTED のまま、回数不変")
    void grantedClaimSurvivesLaterRemoval(String type, String after) {
        Scope scope = scope(type);
        Long admin = admin(scope);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Long userId = user();
        accept(plaintext(token), userId);
        if ("REMOVE".equals(after)) {
            roleService.removeMemberWithoutAdminCheck(scope.id(), type, userId);
        } else {
            blockService.blockUser(scope.id(), type, new BlockRequest(userId, "試練"), admin);
        }
        assertThat(claimStatuses(token.getId())).containsExactly("GRANTED");
        assertThat(usedCount(token.getId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("R5 red: recordJoined の重複・並行→招待監査はちょうど1回、claim は GRANTED")
    void recordJoinedIsIdempotentUnderRace(String type) throws Exception {
        Scope scope = scope(type);
        Long admin = admin(scope);
        InviteTokenEntity token = inTx(() -> tokens.saveAndFlush(token(scope, 0, null, 3, 0, null, null).toBuilder()
                .createdBy(admin).build()));
        Long userId = user();
        UUID claim = insertClaim(token.getId(), userId, "CLAIMED", utcNow(), true);
        grants.grantRole(type, scope.id(), userId, scope.memberRoleId(), admin, "INVITE_TOKEN");
        List<String> events;
        try (AuditCapture capture = capture(userId)) {
            List<Throwable> results = race(List.of(() -> recordJoined(claim), () -> recordJoined(claim)));
            assertThat(results).containsOnlyNulls();
            recordJoined(claim);
            events = capture.events();
        }
        // T7（grantRole）由来の membership JOINED は捕捉開始前。ここで見えるのは T9 の招待監査だけ。
        assertThat(events).containsExactly("TEAM".equals(type) ? "TEAM:INVITED" : "ORGANIZATION:JOINED");
        assertThat(claimStatus(claim)).isEqualTo("GRANTED");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** 全員を同時に走らせ、各々の例外（成功は null）を投入順で返す。 */
    private static List<Throwable> race(List<Runnable> actions) throws Exception {
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(actions.size())) {
            for (Runnable action : actions) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    action.run();
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Throwable> results = new ArrayList<>();
            for (Future<?> f : futures) {
                Throwable r = outcome(f);
                if (r != null && !(r instanceof BusinessException)
                        && (chainContains(r, CannotAcquireLockException.class)
                            || chainContains(r, PessimisticLockingFailureException.class))) {
                    throw new AssertionError("デッドロック／ロック待ち超過: " + r, r);
                }
                results.add(r);
            }
            return results;
        }
    }

    /** 別スレッド（別接続・別TX）で実行し commit まで待つ。 */
    private static void runElsewhere(Runnable action) {
        try (var executor = Executors.newSingleThreadExecutor()) {
            Throwable error = outcome(executor.submit(action));
            if (error != null) {
                throw new AssertionError("別TXの処理が失敗した", error);
            }
        }
    }

    private static Throwable catching(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
