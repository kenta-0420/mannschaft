package com.mannschaft.app.role.event;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RoleService の assign/change/remove/leave が共有する行ロックと、権限キャッシュの
 * トランザクション境界を実 DB で検証する。
 *
 * <p>4 つの mutation はいずれも同じ UserRowLockService を入口にするため、個別の
 * role/membership fixture を複製せず、ここでは共有ロックそのものの 2 トランザクション
 * 直列化を確認する。個別 mutation の認可・保存対象は各サービスの unit test で検証する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RoleMutationConcurrencyAndCacheIT extends AbstractMySqlIntegrationTest {

    private static final long AWAIT_SECONDS = 5;
    private static final String CACHE_NAME = "role-permissions";
    private static final String CACHE_KEY = "900001:TEAM:900002";

    @Autowired
    private UserRowLockService userRowLockService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private CacheManager cacheManager;

    @PersistenceContext
    private EntityManager entityManager;

    private Long userId;

    @AfterEach
    void cleanUp() {
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache != null) {
            cache.evict(CACHE_KEY);
        }
        if (userId != null) {
            transactionTemplate.executeWithoutResult(tx -> entityManager
                    .createNativeQuery("DELETE FROM users WHERE id = :id")
                    .setParameter("id", userId)
                    .executeUpdate());
        }
    }

    @Test
    void roleMutationLock_serializesTwoTransactionsForTheSameUserRow() throws Exception {
        transactionTemplate.executeWithoutResult(tx -> userId = insertActiveUser());

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondAcquired = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
                assertThat(userRowLockService.lock(userId))
                        .isEqualTo(UserRowLockService.UserState.ACTIVE);
                firstLocked.countDown();
                await(releaseFirst);
            }));
            assertThat(firstLocked.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
                secondStarted.countDown();
                assertThat(userRowLockService.lock(userId))
                        .isEqualTo(UserRowLockService.UserState.ACTIVE);
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
    @Transactional
    void rollback_doesNotEvictRolePermissionCache() {
        Cache cache = rolePermissionCache();
        cache.put(CACHE_KEY, "cached-before-mutation");

        eventPublisher.publishEvent(new MembershipChangedEvent(
                900001L, "TEAM", 900002L, MembershipChangedEvent.ChangeType.CHANGED));
        assertThat(cache.get(CACHE_KEY)).isNotNull();

        TestTransaction.flagForRollback();
        TestTransaction.end();

        assertThat(cache.get(CACHE_KEY)).isNotNull();
    }

    @Test
    @Transactional
    void commit_evictsRolePermissionCacheAfterCommit() {
        Cache cache = rolePermissionCache();
        cache.put(CACHE_KEY, "cached-before-mutation");

        eventPublisher.publishEvent(new MembershipChangedEvent(
                900001L, "TEAM", 900002L, MembershipChangedEvent.ChangeType.CHANGED));
        assertThat(cache.get(CACHE_KEY)).isNotNull();

        TestTransaction.flagForCommit();
        TestTransaction.end();

        assertThat(cache.get(CACHE_KEY)).isNull();
    }

    private Cache rolePermissionCache() {
        Cache cache = cacheManager.getCache(CACHE_NAME);
        assertThat(cache).as("role-permissions cache").isNotNull();
        return cache;
    }

    private Long insertActiveUser() {
        UserEntity user = UserEntity.builder()
                .email("role-mutation-lock-" + System.nanoTime() + "@example.com")
                .lastName("LOCK")
                .firstName("TEST")
                .displayName("LOCK TEST")
                .status(UserEntity.UserStatus.ACTIVE)
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
