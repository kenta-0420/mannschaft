package com.mannschaft.app.role.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.role.service.PermissionGroupService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UserRepositoryPessimisticLockIT extends AbstractMySqlIntegrationTest {

    private static final long AWAIT_SECONDS = 5;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private Long userId;

    @AfterEach
    void cleanUp() {
        if (userId != null) {
            transactionTemplate.executeWithoutResult(tx ->
                    entityManager.createNativeQuery("DELETE FROM users WHERE id = :id")
                            .setParameter("id", userId).executeUpdate());
        }
    }

    @Test
    void findByIdForUpdate_waitsUntilTheFirstTransactionCommits() throws Exception {
        transactionTemplate.executeWithoutResult(tx -> userId = insertUser());

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondAcquired = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
                assertThat(userRepository.findByIdForUpdate(userId)).isPresent();
                firstLocked.countDown();
                await(releaseFirst);
            }));
            assertThat(firstLocked.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() ->
                transactionTemplate.executeWithoutResult(tx -> {
                    secondStarted.countDown();
                    assertThat(userRepository.findByIdForUpdate(userId)).isPresent();
                    secondAcquired.countDown();
                }));
            assertThat(secondStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(secondAcquired.await(300, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirst.countDown();
            assertThat(secondAcquired.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            first.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            second.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void findByIdForUpdateIncludingDeleted_executesNativeForUpdateWithoutJpaLockMode() {
        transactionTemplate.executeWithoutResult(tx -> userId = insertUser(UserEntity.UserStatus.FROZEN));

        transactionTemplate.executeWithoutResult(tx ->
                assertThat(userRepository.findByIdForUpdateIncludingDeleted(userId)).isPresent());
    }

    @Test
    void assignUsesReadCommittedIsolation() throws NoSuchMethodException {
        Method method = PermissionGroupService.class.getMethod(
                "assignUserPermissionGroups", Long.class, Long.class, String.class,
                com.mannschaft.app.role.dto.UserPermissionGroupAssignRequest.class, Long.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    private Long insertUser() {
        return insertUser(UserEntity.UserStatus.ACTIVE);
    }

    private Long insertUser(UserEntity.UserStatus status) {
        UserEntity user = UserEntity.builder()
                .email("role-lock-" + System.nanoTime() + "@example.com")
                .lastName("LOCK")
                .firstName("TEST")
                .displayName("LOCK TEST")
                .status(status)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .isSearchable(true)
                .build();
        entityManager.persist(user);
        entityManager.flush();
        return user.getId();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("lock test latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("lock test interrupted", interrupted);
        }
    }
}
