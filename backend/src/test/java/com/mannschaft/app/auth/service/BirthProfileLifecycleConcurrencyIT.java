package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.guardianship.GuardianshipHandoverService;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実auth Bean・MySQLで非ACTIVE cleanupとusers→同意linkの順序を検証する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BirthProfileLifecycleConcurrencyIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private ParentalConsentLinkRepository links;
    @Autowired private ParentalConsentCleanupBatchService cleanup;
    @Autowired private GuardianshipHandoverService handover;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    private Long user(UserEntity.UserStatus status) {
        return users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@child.mannschaft.internal")
                .lastName("山田").firstName("健太").lastNameKana("ヤマダ").firstNameKana("ケンタ")
                .birthDate("1990-01-22").birthProfileVersion(3L).displayName("本人")
                .locale("ja").timezone("Asia/Tokyo").isSearchable(false).status(status).build()).getId();
    }
    private UUID link(Long child, Long parent, ParentalConsentLinkStatus status) {
        String token=UUID.randomUUID().toString().replace("-","");
        return links.saveAndFlush(ParentalConsentLinkEntity.builder().childUserId(child).parentUserId(parent)
                .parentEmail(UUID.randomUUID()+"@guardian.invalid").tokenHash(token+token).status(status)
                .expiresAt(status==ParentalConsentLinkStatus.PENDING?LocalDateTime.now().minusDays(1):LocalDateTime.now().plusDays(1))
                .build()).getId();
    }
    private void await(CountDownLatch latch) {
        try {if(!latch.await(10,TimeUnit.SECONDS)) throw new AssertionError("並行試練の待機満了");}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    private void updateName(UserEntity user) {
        user.applyProfileUpdate(user.getLastName(),"花子",user.getLastNameKana(),"ハナコ",user.getDisplayName(),
                user.getNickname2(),user.getIsSearchable(),user.getAvatarUrl(),user.getPhoneNumber(),
                user.getPostalCode(),user.getLastNameHash(),user.getFirstNameHash(),user.getPhoneNumberHash(),
                user.getLocale(),user.getCountryCode(),user.getTimezone(),user.getDmReceiveFrom());
    }
    @Test @DisplayName("PENDING_PARENTAL_CONSENT対象も匿名化し出生確認版を無効化する")
    void 非有効ユーザーの同意期限切れを処理() {
        Long child=user(UserEntity.UserStatus.PENDING_PARENTAL_CONSENT);
        UUID id=link(child,null,ParentalConsentLinkStatus.PENDING);
        cleanup.execute();
        new TransactionTemplate(transactionManager).executeWithoutResult(s->{
            UserEntity actual=users.findByIdForUpdateIncludingDeleted(child).orElseThrow();
            assertThat(actual.getDeletedAt()).isNotNull();
            assertThat(actual.getBirthProfileVersion()).isEqualTo(4L);
            assertThat(actual.getBirthDate()).isNull();
            assertThat(actual.getLastNameKana()).isNull();
            assertThat(links.findById(id).orElseThrow().getStatus()).isEqualTo(ParentalConsentLinkStatus.REVOKED);
        });
    }
    @Test @DisplayName("cleanupはusersを待つ間に同意linkを先取りせず逆順deadlockを防ぐ")
    void 同意匿名化はユーザー先のロック順を保つ() throws Exception {
        Long child=user(UserEntity.UserStatus.PENDING_PARENTAL_CONSENT);
        UUID id=link(child,null,ParentalConsentLinkStatus.PENDING);
        CountDownLatch started=new CountDownLatch(1);
        var worker=Executors.newSingleThreadExecutor();
        var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(s->{
                users.findByIdForUpdateIncludingDeleted(child).orElseThrow();
                var cleanupFuture=worker.submit(()->{started.countDown();cleanup.execute();});
                pending.set(cleanupFuture);await(started);
                assertThatThrownBy(()->cleanupFuture.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                JdbcTemplate sql=new JdbcTemplate(dataSource);sql.setQueryTimeout(2);
                assertThatCode(()->sql.queryForList("select id from parental_consent_links where id=unhex(replace(?,'-','')) for update",id.toString()))
                        .doesNotThrowAnyException();
            });
            pending.get().get(10,TimeUnit.SECONDS);
            assertThat(links.findById(id).orElseThrow().getStatus()).isEqualTo(ParentalConsentLinkStatus.REVOKED);
        } finally {
            worker.shutdown();assertThat(worker.awaitTermination(15,TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test @DisplayName("保護者引き継ぎのメール更新が先行出生更新をdetached mergeで巻き戻さない")
    void 引き継ぎは新しいプロフィールを保持() throws Exception {
        Long guardian=user(UserEntity.UserStatus.ACTIVE),child=user(UserEntity.UserStatus.ACTIVE);
        link(child,guardian,ParentalConsentLinkStatus.APPROVED);
        CountDownLatch birthLocked=new CountDownLatch(1), releaseBirth=new CountDownLatch(1), handoverStarted=new CountDownLatch(1);
        var worker=Executors.newFixedThreadPool(2);
        try {
            var birth=worker.submit(()->new TransactionTemplate(transactionManager).execute(s->{
                UserEntity locked=users.findByIdForUpdate(child).orElseThrow();birthLocked.countDown();await(releaseBirth);
                updateName(locked);return null;
            }));
            await(birthLocked);
            var handoverFuture=worker.submit(()->{
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
                try {handoverStarted.countDown();handover.initiateHandover(guardian,child,UUID.randomUUID()+"@handover.invalid","127.0.0.1");}
                finally {RequestContextHolder.resetRequestAttributes();}
            });
            await(handoverStarted);
            assertThatThrownBy(()->handoverFuture.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseBirth.countDown();birth.get(10,TimeUnit.SECONDS);handoverFuture.get(10,TimeUnit.SECONDS);
            UserEntity actual=users.findById(child).orElseThrow();
            assertThat(actual.getFirstName()).isEqualTo("花子");
            assertThat(actual.getFirstNameKana()).isEqualTo("ハナコ");
            assertThat(actual.getBirthProfileVersion()).isEqualTo(4L);
            assertThat(actual.getEmail()).endsWith("@handover.invalid");
        } finally {releaseBirth.countDown();worker.shutdownNow();}
    }
}
