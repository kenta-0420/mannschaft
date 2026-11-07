package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 実auth Bean/MySQLで同意判断と期限処理のusers-first/current-readを検証する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ParentalConsentBirthLifecycleRaceIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private ParentalConsentLinkRepository links;
    @Autowired private AuthTokenService tokens;
    @Autowired private ParentalConsentService consent;
    @Autowired private ParentalConsentCleanupBatchService cleanup;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    private record Fixture(Long child, Long parent, UUID expired, UUID decision, String token) {}

    @BeforeEach @SuppressWarnings("unchecked")
    void externalRedisFixture() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(redisTemplate.expire(anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
    }

    private Long user(UserEntity.UserStatus status, String birthDate) {
        return users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@child.mannschaft.internal")
                .lastName("山田").firstName("健太").lastNameKana("ヤマダ").firstNameKana("ケンタ")
                .birthDate(birthDate).birthProfileVersion(3L).displayName("本人")
                .locale("ja").timezone("Asia/Tokyo").isSearchable(false).status(status).build()).getId();
    }

    private ParentalConsentLinkEntity link(Long child, String token, LocalDateTime expiry) {
        return links.saveAndFlush(ParentalConsentLinkEntity.builder().childUserId(child)
                .parentEmail(UUID.randomUUID()+"@guardian.invalid").tokenHash(tokens.hashToken(token))
                .status(ParentalConsentLinkStatus.PENDING).expiresAt(expiry).build());
    }

    private Fixture fixture() {
        Long parent=user(UserEntity.UserStatus.ACTIVE,"1990-01-22");
        Long child=user(UserEntity.UserStatus.PENDING_PARENTAL_CONSENT,"2016-01-22");
        String token=UUID.randomUUID().toString();
        UUID expired=link(child,UUID.randomUUID().toString(),LocalDateTime.now().minusDays(1)).getId();
        UUID decision=link(child,token,LocalDateTime.now().plusDays(1)).getId();
        return new Fixture(child,parent,expired,decision,token);
    }

    private void probeDecisionLink(UUID id) {
        JdbcTemplate sql=new JdbcTemplate(dataSource);sql.setQueryTimeout(2);
        // 診断SQL。旧cleanupがこのLinkをロックするという主張には使わない。
        assertThatCode(()->sql.queryForList("select id from parental_consent_links where id=unhex(replace(?,'-','')) for update",id.toString()))
                .doesNotThrowAnyException();
    }

    @Test @DisplayName("approveはusers待機前にLinkを先取りせずcleanup後にPENDING子を有効化する")
    void 承認は期限処理とユーザー先で整合する() throws Exception {
        Fixture f=fixture();var worker=Executors.newSingleThreadExecutor();
        var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(s->{
                users.findByIdForUpdateIncludingDeleted(f.child()).orElseThrow();
                var decision=worker.submit(()->consent.approveParentalConsent(f.token(),f.parent(),"127.0.0.1"));
                pending.set(decision);BirthProfileLockWaitObserver.awaitUserWait(MYSQL,f.child(),decision);
                cleanup.execute();probeDecisionLink(f.decision());
            });
            pending.get().get(10,TimeUnit.SECONDS);
            new TransactionTemplate(transactionManager).executeWithoutResult(s->{
                UserEntity child=users.findByIdForUpdateIncludingDeleted(f.child()).orElseThrow();
                assertThat(child.getStatus()).isEqualTo(UserEntity.UserStatus.ACTIVE);
                assertThat(child.getDeletedAt()).isNull();assertThat(child.getBirthProfileVersion()).isEqualTo(3L);
                assertThat(child.getBirthDate()).isEqualTo("2016-01-22");
                assertThat(links.findById(f.expired()).orElseThrow().getStatus()).isEqualTo(ParentalConsentLinkStatus.REVOKED);
                assertThat(links.findById(f.decision()).orElseThrow().getStatus()).isEqualTo(ParentalConsentLinkStatus.APPROVED);
            });
        } finally {worker.shutdown();assertThat(worker.awaitTermination(15,TimeUnit.SECONDS)).isTrue();}
    }

    @Test @DisplayName("rejectはcleanupの現在のsibling状態を読んで全同意なしの論理削除を行う")
    void 否認は古い同意snapshotを数えない() throws Exception {
        Fixture f=fixture();var worker=Executors.newSingleThreadExecutor();
        var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(s->{
                users.findByIdForUpdateIncludingDeleted(f.child()).orElseThrow();
                var decision=worker.submit(()->consent.rejectParentalConsent(f.token(),"127.0.0.1"));
                pending.set(decision);BirthProfileLockWaitObserver.awaitUserWait(MYSQL,f.child(),decision);
                cleanup.execute();probeDecisionLink(f.decision());
            });
            pending.get().get(10,TimeUnit.SECONDS);
            new TransactionTemplate(transactionManager).executeWithoutResult(s->{
                UserEntity child=users.findByIdForUpdateIncludingDeleted(f.child()).orElseThrow();
                assertThat(child.getDeletedAt()).isNotNull();
                // rejectの既存意味はrequestDeletionであり、出生情報の匿名化ではない。
                assertThat(child.getBirthDate()).isEqualTo("2016-01-22");
                assertThat(child.getBirthProfileVersion()).isEqualTo(3L);
                assertThat(links.findById(f.expired()).orElseThrow().getStatus()).isEqualTo(ParentalConsentLinkStatus.REVOKED);
                assertThat(links.findById(f.decision()).orElseThrow().getStatus()).isEqualTo(ParentalConsentLinkStatus.REJECTED);
            });
        } finally {worker.shutdown();assertThat(worker.awaitTermination(15,TimeUnit.SECONDS)).isTrue();}
    }

    @Test @DisplayName("users待機中の子の論理削除をfresh資格で拒否しLink承認で復活させない")
    void 削除済みの子は待機後に拒否する() throws Exception {
        Fixture f=fixture();var worker=Executors.newSingleThreadExecutor();
        var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(s->{
                UserEntity child=users.findByIdForUpdateIncludingDeleted(f.child()).orElseThrow();
                var decision=worker.submit(()->consent.approveParentalConsent(f.token(),f.parent(),"127.0.0.1"));
                pending.set(decision);BirthProfileLockWaitObserver.awaitUserWait(MYSQL,f.child(),decision);
                child.requestDeletion();users.save(child);
            });
            try {pending.get().get(10,TimeUnit.SECONDS);fail("論理削除後の同意承認が拒否されませんでした");}
            catch(ExecutionException failure) {
                assertThat(failure.getCause()).isInstanceOf(BusinessException.class);
                assertThat(((BusinessException)failure.getCause()).getErrorCode()).isEqualTo(AuthErrorCode.AUTH_005);
            }
            new TransactionTemplate(transactionManager).executeWithoutResult(s->{
                assertThat(users.findByIdForUpdateIncludingDeleted(f.child()).orElseThrow().getDeletedAt()).isNotNull();
                assertThat(links.findById(f.decision()).orElseThrow().getStatus()).isEqualTo(ParentalConsentLinkStatus.PENDING);
            });
        } finally {worker.shutdown();assertThat(worker.awaitTermination(15,TimeUnit.SECONDS)).isTrue();}
    }
}
