package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.dto.DeliveryUserState;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実auth入口・MySQLで有限複数ロックを検証する。HTTP/native CMS保存の証拠は別試験。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UserRewardDeliveryGuardBoundedIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserRewardDeliveryGuard guard;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    private final List<Long> ownIds = new ArrayList<>();
    private TransactionTemplate tx() {
        var result = new TransactionTemplate(transactionManager);
        result.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return result;
    }
    private Long user(UserEntity.UserStatus status) {
        Long id = tx().execute(s -> users.saveAndFlush(UserEntity.builder()
                .email(UUID.randomUUID()+"@bounded.invalid").lastName("検証").firstName("本人")
                .displayName("検証").isSearchable(false).locale("ja").timezone("UTC")
                .status(status).build()).getId());
        ownIds.add(id);
        return id;
    }
    @AfterEach void cleanupOwnRows() {
        tx().executeWithoutResult(s -> ownIds.forEach(id -> jdbc.update("DELETE FROM users WHERE id=?",id)));
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10,TimeUnit.SECONDS)) throw new AssertionError("有限ロック同期期限切れ"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError("有限ロック同期中断"); }
    }
    @Test void immutableCurrentStatesAndIndependentCallbackConnection() {
        Long active=user(UserEntity.UserStatus.ACTIVE), frozen=user(UserEntity.UserStatus.FROZEN);
        guard.withLockedDeliveryUsers(List.of(frozen,active,active,Long.MAX_VALUE),states -> {
            assertThat(states).hasSize(3);
            assertThat(states.get(active).lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.ACTIVE);
            assertThat(states.get(frozen).lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.FROZEN);
            assertThat(states.get(Long.MAX_VALUE).lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.ABSENT);
            assertThatThrownBy(() -> states.clear()).isInstanceOf(UnsupportedOperationException.class);
            Long outerConnection=jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class);
            Long innerConnection=tx().execute(s -> jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
            assertThat(innerConnection).isNotEqualTo(outerConnection);
            return null;
        });
    }
    @Test void reversedInputBlocksFirstOnLowestUserId() throws Exception {
        Long first=user(UserEntity.UserStatus.ACTIVE), second=user(UserEntity.UserStatus.ACTIVE);
        Long lowest=Math.min(first,second), highest=Math.max(first,second);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(2);
        try {
            var holder=workers.submit(() -> guard.withLockedDeliveryUsers(List.of(highest,lowest),states ->
                    tx().execute(s -> { entered.countDown(); await(release); return "完了"; })));
            await(entered);
            var contender=workers.submit(() -> guard.withLockedDeliveryUsers(List.of(highest,lowest),states -> "後続"));
            BirthProfileLockWaitObserver.awaitUserWait(MYSQL,lowest,contender);
            release.countDown();
            assertThat(holder.get(10,TimeUnit.SECONDS)).isEqualTo("完了");
            assertThat(contender.get(10,TimeUnit.SECONDS)).isEqualTo("後続");
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test void bothRowsStayLockedUntilIndependentCallbackCommit() throws Exception {
        Long first=user(UserEntity.UserStatus.ACTIVE), second=user(UserEntity.UserStatus.ACTIVE);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(3);
        try {
            var holder=workers.submit(() -> guard.withLockedDeliveryUsers(List.of(second,first),states ->
                    tx().execute(s -> { entered.countDown(); await(release); return "完了"; })));
            await(entered);
            var firstWithdrawal=workers.submit(() -> tx().executeWithoutResult(s -> users.findByIdForUpdateIncludingDeleted(first).orElseThrow().requestDeletion()));
            var secondWithdrawal=workers.submit(() -> tx().executeWithoutResult(s -> users.findByIdForUpdateIncludingDeleted(second).orElseThrow().requestDeletion()));
            BirthProfileLockWaitObserver.awaitUserWait(MYSQL,first,firstWithdrawal);
            BirthProfileLockWaitObserver.awaitUserWait(MYSQL,second,secondWithdrawal);
            release.countDown(); holder.get(10,TimeUnit.SECONDS);
            firstWithdrawal.get(10,TimeUnit.SECONDS); secondWithdrawal.get(10,TimeUnit.SECONDS);
            DeliveryUserState.Lifecycle firstLifecycle=guard.withLockedDeliveryUser(first,state -> state.lifecycle());
            DeliveryUserState.Lifecycle secondLifecycle=guard.withLockedDeliveryUser(second,state -> state.lifecycle());
            assertThat(firstLifecycle).isEqualTo(DeliveryUserState.Lifecycle.WITHDRAWAL);
            assertThat(secondLifecycle).isEqualTo(DeliveryUserState.Lifecycle.WITHDRAWAL);
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test void invalidBoundedInputsDoNotBeginTransactionOrCallback() {
        ConfigurableTransactionManager manager=(ConfigurableTransactionManager)transactionManager;
        var original=new ArrayList<>(manager.getTransactionExecutionListeners());
        long thread=Thread.currentThread().threadId(); var begins=new AtomicInteger(); var callbacks=new AtomicInteger();
        TransactionExecutionListener observer=new TransactionExecutionListener() {
            @Override public void beforeBegin(TransactionExecution execution) {
                if (Thread.currentThread().threadId()==thread) begins.incrementAndGet();
            }
        };
        var observers=new ArrayList<>(original); observers.add(observer); manager.setTransactionExecutionListeners(observers);
        try {
            var overLimit=new ArrayList<Long>(); for(long id=1;id<=52;id++) overLimit.add(id);
            for(List<Long> ids : List.of(List.<Long>of(),List.of(0L),overLimit))
                assertThatThrownBy(() -> guard.withLockedDeliveryUsers(ids,states -> callbacks.incrementAndGet())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> guard.withLockedDeliveryUsers(null,states -> callbacks.incrementAndGet())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> guard.withLockedDeliveryUsers(List.of(1L),null)).isInstanceOf(IllegalArgumentException.class);
            assertThat(begins).hasValue(0); assertThat(callbacks).hasValue(0);
        } finally {
            manager.setTransactionExecutionListeners(original);
            assertThat(manager.getTransactionExecutionListeners()).containsExactlyElementsOf(original);
        }
    }
    @Test void fiftyOneUsersAreAcceptedInOneImmutableWindow() {
        var ids=new ArrayList<Long>(); for(int i=0;i<51;i++) ids.add(user(UserEntity.UserStatus.ACTIVE));
        java.util.Collections.reverse(ids);
        Integer observed=guard.withLockedDeliveryUsers(ids,states -> {
            assertThat(states).hasSize(51);
            assertThat(states.values()).allSatisfy(state -> assertThat(state.lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.ACTIVE));
            return states.size();
        });
        assertThat(observed).isEqualTo(51);
    }
    @Test void callbackFailureRetainsIdentityAndReleasesSharedAdmission() {
        Long id=user(UserEntity.UserStatus.ACTIVE);
        var failure=new IllegalStateException("固定検証分類");
        assertThatThrownBy(() -> guard.withLockedDeliveryUsers(List.of(id),states -> { throw failure; })).isSameAs(failure);
        DeliveryUserState state=guard.withLockedDeliveryUser(id,value -> value);
        assertThat(state.lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.ACTIVE);
    }
}
