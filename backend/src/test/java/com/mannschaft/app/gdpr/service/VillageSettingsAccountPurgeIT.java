package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.auth.dto.RequestWithdrawalRequest;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.gdpr.dto.RetryResultResponse;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import com.mannschaft.app.village.entity.UserVillagePinEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * CMP1243の追加2設定だけを、実MySQL/既存公開バッチ/実retryで確認する。
 * 即時弱イベント、所属・憲章・投稿の処理は変えない。FlywayのFK証明は既DDLと区別する。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Timeout(120)
class VillageSettingsAccountPurgeIT extends AbstractMySqlIntegrationTest {

    private static final String DOMAIN = "village.settings";
    private static final List<String> TABLES = List.of("user_village_pins", "user_village_nicknames");

    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private UserService userService;
    @Autowired private AccountPurgeService accountPurgeService;
    @Autowired private GdprPurgeRetryFacade retryService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired @Qualifier("purge-pool") private Executor purgeExecutor;
    @PersistenceContext private EntityManager entityManager;

    @Test
    @DisplayName("村の2設定は期限前と退会取消で保持され、再申請後30日の公開バッチで本人全行だけ消える")
    void preservesGraceAndCancellationThenPurgesAllOwnerSettings() {
        OwnedFixture fixture = new OwnedFixture();
        try {
            prepare(fixture, true);
            Map<String, List<?>> targetBefore = snapshot(fixture.target);
            Map<String, List<?>> otherBefore = snapshot(fixture.other);
            requestWithdrawal(fixture.target);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            accountPurgeService.purgeExpiredAccounts();
            awaitPurgePoolIdle();
            assertThat(snapshot(fixture.target)).as("30日未到達の本人設定").isEqualTo(targetBefore);
            assertThat(countDomain(fixture.target, null)).isZero();
            assertThat(count("SELECT COUNT(*) FROM users WHERE id=" + fixture.target
                    + " AND purge_started_at IS NULL AND purged_at IS NULL")).isEqualTo(1);

            userService.cancelWithdrawal(fixture.target);
            accountPurgeService.purgeExpiredAccounts();
            awaitPurgePoolIdle();
            assertThat(count("SELECT COUNT(*) FROM users WHERE id=" + fixture.target
                    + " AND deleted_at IS NULL AND purge_started_at IS NULL AND purged_at IS NULL")).isEqualTo(1);
            assertThat(snapshot(fixture.target)).as("取消後の本人設定").isEqualTo(targetBefore);
            assertThat(countDomain(fixture.target, null)).isZero();

            requestWithdrawal(fixture.target);
            expireWithdrawal(fixture.target);
            accountPurgeService.purgeExpiredAccounts();
            awaitState(fixture.target, "SUCCESS", 0, 0);
            assertThat(snapshot(fixture.other)).as("別ownerの値も不変").isEqualTo(otherBefore);
            assertThat(count("SELECT COUNT(*) FROM villages WHERE id IN (" + villageIds(fixture) + ")"))
                    .as("本人設定削除で共同の村本体を消さない").isEqualTo(2);
        } finally {
            cleanupOwnedFixture(fixture);
        }
    }

    @Test
    @DisplayName("本人の空設定と重複強イベントは成功1件で冪等、別owner設定は不変")
    void emptySettingsAndDuplicateEventsAreIdempotent() {
        OwnedFixture fixture = new OwnedFixture();
        try {
            prepare(fixture, false);
            Map<String, List<?>> otherBefore = snapshot(fixture.other);
            prepareExpiredWithdrawal(fixture.target);
            accountPurgeService.purgeExpiredAccounts();
            awaitState(fixture.target, "SUCCESS", 0, 0);
            awaitPurgePoolIdle();
            accountPurgeService.purgeExpiredAccounts();
            transactionTemplate.executeWithoutResult(tx -> {
                eventPublisher.publishEvent(new AccountPurgedEvent(fixture.target, "a".repeat(64)));
                eventPublisher.publishEvent(new AccountPurgedEvent(fixture.target, "a".repeat(64)));
            });
            awaitPurgePoolIdle();
            awaitState(fixture.target, "SUCCESS", 0, 0);
            assertThat(countDomain(fixture.target, null)).as("重複登録なし").isEqualTo(1);
            assertThat(retryService.retryDomainPurge(fixture.target, DOMAIN).retryCount()).isZero();
            assertThat(snapshot(fixture.other)).isEqualTo(otherBefore);
        } finally {
            cleanupOwnedFixture(fixture);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_village_pins", "user_village_nicknames"})
    @DisplayName("2表どちらのDELETE障害でもowner処理全体rollback、PENDINGを実retryで回復する")
    void eitherDeleteFailureRollsBackBothSettingsUntilRetry(String blockedTable) {
        OwnedFixture fixture = new OwnedFixture();
        try {
            prepare(fixture, true);
            Map<String, List<?>> targetBefore = snapshot(fixture.target);
            Map<String, List<?>> otherBefore = snapshot(fixture.other);
            String trigger = "cmp1243_village_delete_" + fixture.target;
            createOwnedTrigger(fixture, trigger, "CREATE TRIGGER " + trigger + " BEFORE DELETE ON "
                    + blockedTable + " FOR EACH ROW BEGIN IF OLD.user_id=" + fixture.target
                    + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='cmp1243 owned village delete failure';"
                    + " END IF; END");
            prepareExpiredWithdrawal(fixture.target);
            accountPurgeService.purgeExpiredAccounts();
            awaitState(fixture.target, "PENDING", 2, 3);
            awaitPurgePoolIdle();
            assertThat(snapshot(fixture.target)).as("片方成功を残さないowner全体rollback").isEqualTo(targetBefore);

            RetryResultResponse failed = retryService.retryDomainPurge(fixture.target, DOMAIN);
            assertThat(failed.succeeded()).isFalse();
            assertThat(failed.newStatus()).isEqualTo("PENDING");
            assertThat(failed.retryCount()).isEqualTo(1);
            assertThat(snapshot(fixture.target)).isEqualTo(targetBefore);
            assertThat(count("SELECT COUNT(*) FROM account_purge_completion_status WHERE user_id="
                    + fixture.target + " AND domain_name='" + DOMAIN + "' AND status='PENDING'"
                    + " AND retry_count=1 AND last_retried_at IS NOT NULL AND completed_at IS NULL")).isEqualTo(1);

            dropOwnedTrigger(trigger);
            RetryResultResponse recovered = retryService.retryDomainPurge(fixture.target, DOMAIN);
            assertThat(recovered.succeeded()).isTrue();
            assertThat(recovered.newStatus()).isEqualTo("SUCCESS");
            assertThat(recovered.retryCount()).isEqualTo(2);
            // retryのowner proxyが返るまでにDELETE commit、GDPR保存commit後に新TXで見る。
            awaitState(fixture.target, "SUCCESS", 0, 0);
            assertThat(retryService.retryDomainPurge(fixture.target, DOMAIN).retryCount()).isEqualTo(2);
            assertThat(snapshot(fixture.other)).isEqualTo(otherBefore);
        } finally {
            cleanupOwnedFixture(fixture);
        }
    }

    @Test
    @DisplayName("村設定DELETE commit後の完了記録障害はdata0/PENDING、実retryでSUCCESSへ回復する")
    void completionFailureKeepsPendingAfterOwnerCommit() {
        OwnedFixture fixture = new OwnedFixture();
        try {
            prepare(fixture, true);
            Map<String, List<?>> otherBefore = snapshot(fixture.other);
            String trigger = "cmp1243_village_success_" + fixture.target;
            createOwnedTrigger(fixture, trigger, "CREATE TRIGGER " + trigger
                    + " BEFORE UPDATE ON account_purge_completion_status FOR EACH ROW BEGIN IF OLD.user_id="
                    + fixture.target + " AND OLD.domain_name='" + DOMAIN + "' AND NEW.status='SUCCESS'"
                    + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='cmp1243 owned village completion failure';"
                    + " END IF; END");
            prepareExpiredWithdrawal(fixture.target);
            accountPurgeService.purgeExpiredAccounts();
            awaitState(fixture.target, "PENDING", 0, 0);
            awaitPurgePoolIdle();
            assertThat(count("SELECT COUNT(*) FROM account_purge_completion_status WHERE user_id="
                    + fixture.target + " AND domain_name='" + DOMAIN + "' AND completed_at IS NULL")).isEqualTo(1);
            dropOwnedTrigger(trigger);
            RetryResultResponse recovered = retryService.retryDomainPurge(fixture.target, DOMAIN);
            assertThat(recovered.succeeded()).isTrue();
            assertThat(recovered.retryCount()).isEqualTo(1);
            awaitState(fixture.target, "SUCCESS", 0, 0);
            assertThat(snapshot(fixture.other)).isEqualTo(otherBefore);
        } finally {
            cleanupOwnedFixture(fixture);
        }
    }

    @Test
    @DisplayName("既purge-poolの実拒否は村設定PENDINGを保持し、解放後の設定retryで本人だけを消す")
    void queueRejectionRecoversThroughRealSettingsRetry() throws InterruptedException {
        OwnedFixture fixture = new OwnedFixture();
        CountDownLatch release = new CountDownLatch(1);
        try {
            prepare(fixture, true);
            Map<String, List<?>> targetBefore = snapshot(fixture.target);
            Map<String, List<?>> otherBefore = snapshot(fixture.other);
            prepareExpiredWithdrawal(fixture.target);
            awaitPurgePoolIdle();
            ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) purgeExecutor;
            CountDownLatch active = new CountDownLatch(pool.getMaxPoolSize());
            Runnable blocker = () -> {
                active.countDown();
                try {
                    release.await(90, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            };
            boolean rejected = false;
            int capacity = pool.getThreadPoolExecutor().getQueue().remainingCapacity() + pool.getMaxPoolSize();
            for (int submitted = 0; submitted <= capacity; submitted++) {
                try {
                    pool.execute(blocker);
                } catch (TaskRejectedException expectedRejection) {
                    rejected = true;
                    break;
                }
            }
            assertThat(rejected).as("既poolの実拒否を観測する").isTrue();
            assertThat(active.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(pool.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
            accountPurgeService.purgeExpiredAccounts();
            // 実拒否後はAsync待ちを挟まず、commit済み登録と未処理行を観測する。
            assertThat(state(fixture.target)).containsExactlyInAnyOrderEntriesOf(expectedState("PENDING", 2, 3));
            assertThat(snapshot(fixture.target)).isEqualTo(targetBefore);
            release.countDown();
            awaitPurgePoolIdle();
            RetryResultResponse recovered = retryService.retryDomainPurge(fixture.target, DOMAIN);
            assertThat(recovered.succeeded()).isTrue();
            assertThat(recovered.retryCount()).isEqualTo(1);
            awaitState(fixture.target, "SUCCESS", 0, 0);
            assertThat(snapshot(fixture.other)).isEqualTo(otherBefore);
        } finally {
            release.countDown();
            cleanupOwnedFixture(fixture);
        }
    }

    private void prepare(OwnedFixture fixture, boolean targetSettings) {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(operations);
        fixture.target = createUser(fixture, "本人");
        fixture.other = createUser(fixture, "別owner");
        for (int index = 0; index < 2; index++) {
            int ordinal = index;
            UUID village = transactionTemplate.execute(tx -> {
                VillageEntity entity = VillageEntity.builder().slug("cmp1243-village-" + fixture.target + "-" + ordinal)
                        .name("所有試練村").type(VillageType.COMMUNITY).joinPolicy(VillageJoinPolicy.FREE)
                        .visibility(VillageVisibility.UNLISTED).createdByUserId(fixture.other).build();
                entityManager.persist(entity);
                entityManager.flush();
                return entity.getId();
            });
            fixture.villages.add(village);
        }
        if (targetSettings) {
            seedSettings(fixture.target, fixture.villages);
        }
        seedSettings(fixture.other, fixture.villages);
    }

    private Long createUser(OwnedFixture fixture, String name) {
        Long id = transactionTemplate.execute(tx -> {
            UserEntity user = UserEntity.builder().email("cmp1243-village-" + UUID.randomUUID() + "@example.com")
                    .lastName("試練").firstName(name).displayName(name).status(UserEntity.UserStatus.ACTIVE)
                    .locale("ja").timezone("Asia/Tokyo").isSearchable(true).build();
            entityManager.persist(user);
            entityManager.flush();
            return user.getId();
        });
        fixture.owners.add(id);
        return id;
    }

    private void seedSettings(Long owner, List<UUID> villages) {
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.persist(UserVillageNicknameEntity.builder().userId(owner)
                    .nickname("cmp1243-global-" + owner).bio("所有fixture").build());
            for (int index = 0; index < villages.size(); index++) {
                entityManager.persist(UserVillageNicknameEntity.builder().userId(owner).villageId(villages.get(index))
                        .nickname("cmp1243-local-" + owner + "-" + index).bio("村別fixture").build());
                entityManager.persist(UserVillagePinEntity.builder().userId(owner).villageId(villages.get(index))
                        .sortOrder((long) index).build());
            }
            entityManager.flush();
        });
        assertThat(countOwner(TABLES.get(0), owner)).isEqualTo(2);
        assertThat(countOwner(TABLES.get(1), owner)).isEqualTo(3);
    }

    private void requestWithdrawal(Long owner) {
        userService.requestWithdrawal(owner, new RequestWithdrawalRequest(null));
    }

    private void expireWithdrawal(Long owner) {
        transactionTemplate.executeWithoutResult(tx -> entityManager.createNativeQuery(
                "UPDATE users SET deleted_at=DATE_SUB(NOW(),INTERVAL 31 DAY) WHERE id=:owner")
                .setParameter("owner", owner).executeUpdate());
    }

    private void prepareExpiredWithdrawal(Long owner) {
        requestWithdrawal(owner);
        expireWithdrawal(owner);
    }

    private Map<String, Long> expectedState(String status, long pins, long nicknames) {
        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("users", 0L);
        expected.put(TABLES.get(0), pins);
        expected.put(TABLES.get(1), nicknames);
        expected.put("domain/total", 1L);
        expected.put("domain/" + status, 1L);
        expected.put("domain/" + ("SUCCESS".equals(status) ? "PENDING" : "SUCCESS"), 0L);
        return expected;
    }

    private Map<String, Long> state(Long owner) {
        Map<String, Long> actual = new LinkedHashMap<>();
        actual.put("users", count("SELECT COUNT(*) FROM users WHERE id=" + owner));
        for (String table : TABLES) {
            actual.put(table, countOwner(table, owner));
        }
        actual.put("domain/total", countDomain(owner, null));
        actual.put("domain/SUCCESS", countDomain(owner, "SUCCESS"));
        actual.put("domain/PENDING", countDomain(owner, "PENDING"));
        return actual;
    }

    private void awaitState(Long owner, String status, long pins, long nicknames) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(state(owner))
                .as("本人2表と強完了記録のnative件数").containsExactlyInAnyOrderEntriesOf(expectedState(status, pins, nicknames)));
    }

    private long countOwner(String table, Long owner) {
        return count("SELECT COUNT(*) FROM " + table + " WHERE user_id=" + owner);
    }

    private long countDomain(Long owner, String status) {
        return count("SELECT COUNT(*) FROM account_purge_completion_status WHERE user_id=" + owner
                + " AND domain_name='" + DOMAIN + "'" + (status == null ? "" : " AND status='" + status + "'"));
    }

    private long count(String sql) {
        return transactionTemplate.execute(tx -> ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue());
    }

    private Map<String, List<?>> snapshot(Long owner) {
        return transactionTemplate.execute(tx -> {
            Map<String, List<?>> result = new LinkedHashMap<>();
            result.put(TABLES.get(0), entityManager.createNativeQuery("SELECT CAST(JSON_ARRAY(HEX(id),user_id,HEX(village_id),sort_order,pinned_at) AS CHAR)"
                    + " FROM user_village_pins WHERE user_id=" + owner + " ORDER BY id").getResultList());
            result.put(TABLES.get(1), entityManager.createNativeQuery("SELECT CAST(JSON_ARRAY(HEX(id),user_id,HEX(village_id),nickname,avatar_r2_key,bio,"
                    + "last_changed_at,change_count_this_month,created_at,updated_at) AS CHAR)"
                    + " FROM user_village_nicknames WHERE user_id=" + owner + " ORDER BY id").getResultList());
            return result;
        });
    }

    private void createOwnedTrigger(OwnedFixture fixture, String name, String ddl) {
        assertThat(count("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()"
                + " AND trigger_name='" + name + "'")).isZero();
        // CREATE失敗時でも、未知名へ進まず自隊の一意名だけをfinally対象にする。
        fixture.triggers.add(name);
        executeOwnedTriggerDdl(ddl);
    }

    private void dropOwnedTrigger(String name) {
        executeOwnedTriggerDdl("DROP TRIGGER IF EXISTS " + name);
    }

    private void executeOwnedTriggerDdl(String sql) {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException("所有MySQLの試練trigger DDL失敗", failure);
        }
    }

    private void awaitPurgePoolIdle() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) purgeExecutor;
        await().atMost(Duration.ofSeconds(10)).until(() -> pool.getActiveCount() == 0
                && pool.getThreadPoolExecutor().getQueue().isEmpty());
    }

    private String villageIds(OwnedFixture fixture) {
        return String.join(",", fixture.villages.stream().map(id -> "UUID_TO_BIN('" + id + "')").toList());
    }

    private void cleanupOwnedFixture(OwnedFixture fixture) {
        // 所有latchはcallerのfinallyで解放済み。idle確認失敗時はDELETEへ進まない。
        awaitPurgePoolIdle();
        for (String trigger : fixture.triggers) {
            dropOwnedTrigger(trigger);
        }
        if (fixture.owners.isEmpty()) {
            return;
        }
        String owners = String.join(",", fixture.owners.stream().map(String::valueOf).toList());
        transactionTemplate.executeWithoutResult(tx -> {
            for (String table : TABLES) {
                entityManager.createNativeQuery("DELETE FROM " + table + " WHERE user_id IN (" + owners + ")").executeUpdate();
            }
            entityManager.createNativeQuery("DELETE FROM account_purge_completion_status WHERE user_id IN (" + owners + ")").executeUpdate();
            if (!fixture.villages.isEmpty()) {
                entityManager.createNativeQuery("DELETE FROM villages WHERE id IN (" + villageIds(fixture) + ")").executeUpdate();
            }
            entityManager.createNativeQuery("DELETE FROM users WHERE id IN (" + owners + ")").executeUpdate();
        });
    }

    private static final class OwnedFixture {
        private Long target;
        private Long other;
        private final List<Long> owners = new ArrayList<>();
        private final List<UUID> villages = new ArrayList<>();
        private final List<String> triggers = new ArrayList<>();
    }
}
