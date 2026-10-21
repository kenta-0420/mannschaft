package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.dto.UpdateProfileRequest;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.BirthProfileErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実MySQL・auth Beanでプロフィール版と非出生更新の並行上書きを検証する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BirthProfileConcurrencyIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private BirthProfileFacade profiles;
    @Autowired private UserService userService;
    @Autowired private PlatformTransactionManager transactionManager;

    private Long user() {
        return users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@birth-race.invalid")
                .lastName("山田").firstName("健太").lastNameKana("ヤマダ").firstNameKana("ケンタ")
                .birthDate("1990-01-22").displayName("本人").locale("ja").timezone("Asia/Tokyo")
                .isSearchable(false).status(UserEntity.UserStatus.ACTIVE).build()).getId();
    }
    private void updateName(UserEntity user, String name, String kana) {
        user.applyProfileUpdate(user.getLastName(),name,user.getLastNameKana(),kana,user.getDisplayName(),
                user.getNickname2(),user.getIsSearchable(),user.getAvatarUrl(),user.getPhoneNumber(),
                user.getPostalCode(),user.getLastNameHash(),user.getFirstNameHash(),user.getPhoneNumberHash(),
                user.getLocale(),user.getCountryCode(),user.getTimezone(),user.getDmReceiveFrom());
    }
    private void await(CountDownLatch latch) {
        try {if(!latch.await(10,TimeUnit.SECONDS)) throw new AssertionError("並行試練の待機満了");}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    @Test @DisplayName("旧managed entityのメール更新が新しい出生欄と版を巻き戻さない")
    void 非出生更新は新しいプロフィールを保持() throws Exception {
        Long id=user();
        CountDownLatch oldLoaded=new CountDownLatch(1), birthCommitted=new CountDownLatch(1);
        var workers=Executors.newSingleThreadExecutor();
        try {
            var old=workers.submit(()->new TransactionTemplate(transactionManager).execute(s->{
                UserEntity stale=users.findById(id).orElseThrow();
                oldLoaded.countDown();await(birthCommitted);
                stale.updateEmail(UUID.randomUUID()+"@mail-race.invalid");
                users.flush();return null;
            }));
            await(oldLoaded);
            new TransactionTemplate(transactionManager).executeWithoutResult(s->{
                updateName(users.findByIdForUpdate(id).orElseThrow(),"花子","ハナコ");
            });
            birthCommitted.countDown();old.get(10,TimeUnit.SECONDS);
            UserEntity actual=users.findById(id).orElseThrow();
            assertThat(actual.getFirstName()).isEqualTo("花子");
            assertThat(actual.getFirstNameKana()).isEqualTo("ハナコ");
            assertThat(actual.getBirthProfileVersion()).isEqualTo(1L);
        } finally {birthCommitted.countDown();workers.shutdownNow();}
    }
    @Test @DisplayName("一般profile更新は先行出生更新のusersロック後に新しい版を読んで加算する")
    void 一般プロフィール更新は版を重複させない() throws Exception {
        Long id=user();
        var confirmation = profiles.confirm(id, UUID.randomUUID(), 0, true);
        CountDownLatch birthLocked=new CountDownLatch(1), releaseBirth=new CountDownLatch(1), profileStarted=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(2);
        try {
            var birth=workers.submit(()->new TransactionTemplate(transactionManager).execute(s->{
                UserEntity locked=users.findByIdForUpdate(id).orElseThrow();
                birthLocked.countDown();await(releaseBirth);updateName(locked,"花子","ハナコ");return null;
            }));
            await(birthLocked);
            var profile=workers.submit(()->{
                profileStarted.countDown();
                return userService.updateProfile(id,new UpdateProfileRequest(null,"太郎",null,"タロウ",null,null,
                        null,null,null,null,null,null,null,null));
            });
            await(profileStarted);
            BirthProfileLockWaitObserver.awaitUserWait(MYSQL,id,profile);
            releaseBirth.countDown();birth.get(10,TimeUnit.SECONDS);profile.get(10,TimeUnit.SECONDS);
            UserEntity actual=users.findById(id).orElseThrow();
            assertThat(actual.getFirstName()).isEqualTo("太郎");
            assertThat(actual.getFirstNameKana()).isEqualTo("タロウ");
            assertThat(actual.getBirthProfileVersion()).isEqualTo(2L);
            var initialWrites = new AtomicInteger();
            assertThatThrownBy(() -> profiles.withConfirmedBirthProfile(id, confirmation.confirmationRef(),
                    Optional::empty, numbers -> initialWrites.incrementAndGet()))
                    .isInstanceOfSatisfying(BusinessException.class, failure ->
                            assertThat(failure.getErrorCode()).isEqualTo(BirthProfileErrorCode.CONFIRMATION_STALE));
            assertThat(initialWrites).hasValue(0);
        } finally {releaseBirth.countDown();workers.shutdownNow();}
    }
    @Test @DisplayName("先行出生更新の後の匿名化は世代を進め原入力を消去する")
    void 匿名化は既存版を無効化() {
        Long id=user();
        new TransactionTemplate(transactionManager).executeWithoutResult(s->updateName(users.findByIdForUpdate(id).orElseThrow(),"花子","ハナコ"));
        new TransactionTemplate(transactionManager).executeWithoutResult(s->{
            UserEntity locked=users.findByIdForUpdate(id).orElseThrow();locked.anonymize();
        });
        UserEntity actual=users.findById(id).orElseThrow();
        assertThat(actual.getBirthProfileVersion()).isEqualTo(2L);
        assertThat(actual.getBirthDate()).isNull();
        assertThat(actual.getLastNameKana()).isNull();
        assertThat(actual.getFirstNameKana()).isNull();
    }
}
