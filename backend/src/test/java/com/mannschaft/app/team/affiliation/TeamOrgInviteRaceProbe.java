package com.mannschaft.app.team.affiliation;

import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.Advised;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261004-1822専用の実delegate観測。製品の返値・例外を変更せず、固定stageと数値だけを保持する。
 *
 * <p>1試行のfixtureと、その試行が起動したスレッドだけを対象とする。Repositoryの既存AOP chainへ
 * 一時的に差し込み、closeで必ず外す。SQL・引数・例外message・Entityそのものは記録しない。</p>
 */
final class TeamOrgInviteRaceProbe implements AutoCloseable {
    enum Operation { ACCEPT, CANCEL }

    private static final int WAIT_SECONDS = 10;
    private static final int MAX_CAUSES = 8;
    private final long membershipId;
    private final long teamId;
    private final long organizationId;
    private final Operation winner;
    private final Advised repository;
    private final MethodInterceptor observer = this::observe;
    private final ThreadLocal<Operation> operation = new ThreadLocal<>();
    private final ThreadLocal<Boolean> registered = new ThreadLocal<>();
    private final List<String> stages = new CopyOnWriteArrayList<>();
    private final List<String> harnessFailures = new CopyOnWriteArrayList<>();
    private final CountDownLatch winnerLocked = new CountDownLatch(1);
    private final CountDownLatch loserEntered = new CountDownLatch(1);
    private final CountDownLatch releaseWinner = new CountDownLatch(1);

    TeamOrgInviteRaceProbe(Object repository, long membershipId, long teamId, long organizationId, Operation winner) {
        this.repository = (Advised) repository;
        this.membershipId = membershipId;
        this.teamId = teamId;
        this.organizationId = organizationId;
        this.winner = winner;
        this.repository.addAdvice(0, observer);
    }

    <T> T run(Operation selected, Callable<T> action) throws Exception {
        operation.set(selected);
        try {
            return action.call();
        } catch (Exception failure) {
            stages.add(selected + ":request-failure=" + causes(failure));
            throw failure;
        } finally {
            operation.remove();
            registered.remove();
        }
    }

    boolean isCurrentOperation() {
        return operation.get() != null;
    }

    void stage(String stage) {
        stages.add(operation.get() + ":" + stage);
    }

    void failure(String stage, Throwable failure) {
        stage(stage + "=" + causes(failure));
    }

    void awaitWinner() throws InterruptedException {
        await(winnerLocked, "winner-lock");
    }

    void awaitLoser() throws InterruptedException {
        await(loserEntered, "loser-entry");
    }

    void release() {
        releaseWinner.countDown();
    }

    void assertHarness() {
        assertThat(harnessFailures).as("観測callback障害（製品redには数えない）").isEmpty();
    }

    void assertTransactions(boolean accepted) {
        assertThat(stages).contains("CANCEL:tx-owner");
        if (accepted || winner != null) {
            assertThat(stages).contains("ACCEPT:tx-owner");
        }
        assertThat(stages).contains(accepted ? "ACCEPT:tx=COMMITTED" : "CANCEL:tx=COMMITTED");
        if (accepted || stages.contains("ACCEPT:tx-owner")) {
            assertThat(stages).contains(accepted ? "CANCEL:tx=ROLLED_BACK" : "ACCEPT:tx=ROLLED_BACK");
        }
        assertThat(stages).contains(accepted ? "ACCEPT:acceptPendingInviteUnassigned:rows=1"
                : "CANCEL:deletePendingInviteByOrganization:rows=1");
        String succeeded = accepted ? "ACCEPT" : "CANCEL";
        String rejected = accepted ? "CANCEL" : "ACCEPT";
        assertThat(stages).contains(succeeded + ":audit-entry", succeeded + ":audit-return");
        assertThat(stages).doesNotContain(rejected + ":audit-entry");
        assertThat(stages.indexOf(succeeded + ":audit-entry"))
                .isGreaterThan(stages.indexOf(succeeded + ":tx=COMMITTED"));
    }

    String diagnostic() {
        return "stages=" + stages + ", harness=" + harnessFailures;
    }

    private Object observe(MethodInvocation invocation) throws Throwable {
        Operation selected = operation.get();
        String method = invocation.getMethod().getName();
        if (selected == null || !matches(method, invocation.getArguments())) {
            return invocation.proceed();
        }
        boolean lock = method.equals("findByIdAndTeamIdForUpdate")
                || method.equals("findByTeamIdAndOrganizationIdForUpdate");
        if (lock) {
            registerTransaction(selected);
            stage("lock-entry");
            if (winner != null && selected != winner) {
                loserEntered.countDown();
            }
        }
        Object result;
        try {
            result = invocation.proceed();
        } catch (Throwable failure) {
            failure("delegate:" + method, failure);
            throw failure;
        }
        if (lock) {
            Optional<?> row = (Optional<?>) result;
            String state = row.map(value -> {
                TeamOrgMembershipEntity entity = (TeamOrgMembershipEntity) value;
                return entity.getStatus().name() + "/" + entity.getDirection().name();
            }).orElse("ABSENT");
            stage("lock-return:" + state);
            if (selected == winner) {
                if (!state.equals("PENDING/ORG_INVITE")) {
                    harnessFailures.add("winner-not-pending");
                    throw new IllegalStateException("競合試験の勝者がPENDING行を取得できない");
                }
                winnerLocked.countDown();
                await(releaseWinner, "winner-release");
            }
        } else {
            stage(method + ":rows=" + result);
        }
        return result;
    }

    private boolean matches(String method, Object[] arguments) {
        if (method.equals("findByTeamIdAndOrganizationIdForUpdate")) {
            return Long.valueOf(teamId).equals(arguments[0]) && Long.valueOf(organizationId).equals(arguments[1]);
        }
        if (method.equals("findByIdAndTeamIdForUpdate") || method.equals("acceptPendingInviteUnassigned")) {
            return Long.valueOf(membershipId).equals(arguments[0]) && Long.valueOf(teamId).equals(arguments[1]);
        }
        return method.equals("deletePendingInviteByOrganization")
                && Long.valueOf(membershipId).equals(arguments[0])
                && Long.valueOf(organizationId).equals(arguments[1]);
    }

    private void registerTransaction(Operation selected) {
        if (Boolean.TRUE.equals(registered.get())) {
            return;
        }
        String name = TransactionSynchronizationManager.getCurrentTransactionName();
        String suffix = selected == Operation.ACCEPT ? ".accept" : ".cancel";
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || name == null || !name.endsWith("TeamOrgInviteCommandService" + suffix)) {
            harnessFailures.add("wrong-command-transaction");
            throw new IllegalStateException("競合試験がCommand書込TXを観測できない");
        }
        registered.set(true);
        stage("tx-owner");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                String completed = status == STATUS_COMMITTED ? "COMMITTED"
                        : status == STATUS_ROLLED_BACK ? "ROLLED_BACK" : "UNKNOWN";
                stages.add(selected + ":tx=" + completed);
            }
        });
    }

    private void await(CountDownLatch latch, String stage) throws InterruptedException {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                harnessFailures.add(stage + ":timeout");
                throw new IllegalStateException("競合試験の同期が期限内に完了しない");
            }
        } catch (InterruptedException failure) {
            harnessFailures.add(stage + ":interrupted");
            Thread.currentThread().interrupt();
            throw failure;
        }
    }

    /** cause/SQLExceptionの辺を辿るが、出力は最大8classとSQLState/vendor codeに限定する。 */
    static List<String> causes(Throwable root) {
        List<String> result = new ArrayList<>();
        List<Throwable> pending = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(root);
        for (int index = 0; index < pending.size() && result.size() < MAX_CAUSES; index++) {
            Throwable current = pending.get(index);
            if (current == null || !visited.add(current)) {
                continue;
            }
            String value = current.getClass().getName();
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                value += "[state=" + (state != null && state.matches("[A-Z0-9]{5}") ? state : "UNKNOWN")
                        + ",vendor=" + sql.getErrorCode() + "]";
                pending.add(sql.getNextException());
            }
            result.add(value);
            pending.add(current.getCause());
            if (current instanceof org.hibernate.JDBCException jdbc) {
                pending.add(jdbc.getSQLException());
            }
        }
        return List.copyOf(result);
    }

    @Override
    public void close() {
        release();
        assertThat(repository.removeAdvice(observer)).as("試験専用Repository観測を復元").isTrue();
    }
}
