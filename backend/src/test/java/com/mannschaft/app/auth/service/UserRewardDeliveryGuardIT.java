package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.dto.DeliveryUserState;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実auth Beanと専用MySQLで配送状態の現在値・取消・callback中の行保護を検証する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UserRewardDeliveryGuardIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserRewardDeliveryGuard guard;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    private TransactionTemplate tx() {
        var tx=new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }
    private Long user(UserEntity.UserStatus status, Instant purgeStartedAt) {
        return tx().execute(s->users.saveAndFlush(UserEntity.builder()
                .email(UUID.randomUUID()+"@delivery.invalid").lastName("検証").firstName("本人")
                .displayName("検証").isSearchable(false).locale("ja").timezone("UTC")
                .status(status).purgeStartedAt(purgeStartedAt).build()).getId());
    }
    private DeliveryUserState state(Long id) { return guard.withLockedDeliveryUser(id,s->s); }
    @Test void currentLifecycleDoesNotConflateDeferredAndDeleted() {
        assertThat(state(user(UserEntity.UserStatus.ACTIVE,null)).lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.ACTIVE);
        assertThat(state(user(UserEntity.UserStatus.FROZEN,null)).lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.FROZEN);
        assertThat(state(user(UserEntity.UserStatus.PENDING_PARENTAL_CONSENT,null)).lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.INELIGIBLE);
        assertThat(state(user(UserEntity.UserStatus.ACTIVE,Instant.now())).lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.PURGING);
        assertThat(state(Long.MAX_VALUE).lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.ABSENT);
        Long purged=user(UserEntity.UserStatus.ACTIVE,Instant.now());
        tx().executeWithoutResult(s->users.findByIdForUpdateIncludingDeleted(purged).orElseThrow().setPurgedAt(LocalDateTime.now()));
        assertThat(state(purged).lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.PURGED);
    }
    @Test void withdrawalCancelRetainsAttemptButCannotProveHistoricalEligibility() {
        Long id=user(UserEntity.UserStatus.ACTIVE,null);
        tx().executeWithoutResult(s->users.findByIdForUpdateIncludingDeleted(id).orElseThrow().requestDeletion());
        var withdrawn=state(id);
        assertThat(withdrawn.lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.WITHDRAWAL);
        assertThat(withdrawn.withdrawalAttemptId()).isNotNull();
        tx().executeWithoutResult(s->users.findByIdForUpdateIncludingDeleted(id).orElseThrow().cancelDeletion());
        var cancelled=state(id);
        assertThat(cancelled.lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.ACTIVE);
        assertThat(cancelled.withdrawalAttemptId()).isEqualTo(withdrawn.withdrawalAttemptId());
    }
    @Test void callbackFailuresPropagateWithoutBecomingDeletedState() {
        Long id=user(UserEntity.UserStatus.ACTIVE,null);
        var failure=new IllegalStateException("固定検証分類");
        assertThatThrownBy(()->guard.withLockedDeliveryUser(id,s->{throw failure;})).isSameAs(failure);
        assertThat(state(id).lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.ACTIVE);
    }
    @Test void currentUsersLockRemainsUntilIndependentCallbackCommit() throws Exception {
        Long id=user(UserEntity.UserStatus.ACTIVE,null);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(2);
        try {
            var guarded=workers.submit(()->guard.withLockedDeliveryUser(id,state->tx().execute(s->{
                assertThat(state.lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.ACTIVE);
                entered.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("配送callback待機期限切れ"); }
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError("配送callback中断");}
                return "commit済み";
            })));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var withdrawal=workers.submit(()->tx().executeWithoutResult(s->
                    users.findByIdForUpdateIncludingDeleted(id).orElseThrow().requestDeletion()));
            BirthProfileLockWaitObserver.awaitUserWait(MYSQL,id,withdrawal);
            release.countDown();assertThat(guarded.get(10,TimeUnit.SECONDS)).isEqualTo("commit済み");
            withdrawal.get(10,TimeUnit.SECONDS);
            assertThat(state(id).lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.WITHDRAWAL);
        } finally { release.countDown();workers.shutdownNow(); }
    }
}
