package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import org.springframework.http.HttpStatus;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実 auth Bean と MySQL の行ロックで認可と callback 中の lock 保持を検証する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UserOperationGuardIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserOperationGuard guard;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;

    private Long user(UserEntity.UserStatus status) {
        return users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@guard.invalid")
                .lastName("山田").firstName("本人").displayName("本人").isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").status(status).build()).getId();
    }
    @Test @DisplayName("ACTIVE 本人 callback の戻り値を保持する")
    void 有効本人のみ処理() {
        Long id=user(UserEntity.UserStatus.ACTIVE);
        assertThat(guard.withActiveUser(id,()->"保存済み")).isEqualTo("保存済み");
    }
    @Test @DisplayName("不存在・凍結・論理削除は callback 前に拒否")
    void 無効本人の処理を拒否() {
        Long frozen=user(UserEntity.UserStatus.FROZEN);
        Long deleted=user(UserEntity.UserStatus.ACTIVE);
        TransactionTemplate tx=new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> users.findById(deleted).orElseThrow().requestDeletion());
        for(Long id : new Long[]{Long.MAX_VALUE,frozen,deleted}) {
            AtomicBoolean invoked=new AtomicBoolean();
            assertThatThrownBy(()->guard.withActiveUser(id,()->{invoked.set(true);return null;}))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> {
                        BusinessException business = (BusinessException) error;
                        assertThat(business.getErrorCode().getCode()).isEqualTo("AUTHOPERATION_002");
                        assertThat(GlobalExceptionHandler.resolveStatus(business.getErrorCode())).isEqualTo(HttpStatus.FORBIDDEN);
                    });
            assertThat(invoked).isFalse();
        }
    }
    @Test @DisplayName("callback 完了まで users 行ロックを保持し、独立更新を待機させる")
    void 処理完了までロックを保持() throws Exception {
        Long id=user(UserEntity.UserStatus.ACTIVE);
        CountDownLatch callbackEntered=new CountDownLatch(1);
        CountDownLatch releaseCallback=new CountDownLatch(1);
        CountDownLatch updaterAttempted=new CountDownLatch(1);
        CountDownLatch updateLocked=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(2);
        try {
            var guarded=workers.submit(()->guard.withActiveUser(id,()->{
                callbackEntered.countDown();
                try {
                    if(!releaseCallback.await(10,TimeUnit.SECONDS)) throw new AssertionError("callback 待機が満了");
                } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new AssertionError(e);}
                return "完了";
            }));
            assertThat(callbackEntered.await(10,TimeUnit.SECONDS)).isTrue();
            var updater=workers.submit(()->{
                TransactionTemplate tx=new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                updaterAttempted.countDown();
                tx.executeWithoutResult(s->{users.findByIdForUpdate(id).orElseThrow();updateLocked.countDown();});
            });
            assertThat(updaterAttempted.await(10,TimeUnit.SECONDS)).isTrue();
            BirthProfileLockWaitObserver.awaitUserWait(MYSQL,id,updater);
            releaseCallback.countDown();
            assertThat(guarded.get(10,TimeUnit.SECONDS)).isEqualTo("完了");
            updater.get(10,TimeUnit.SECONDS);
            assertThat(updateLocked.getCount()).isZero();
        } finally {releaseCallback.countDown();workers.shutdownNow();}
    }
}
