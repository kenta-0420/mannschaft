package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.auth.dto.RequestWithdrawalRequest;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.UserInterestTagEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.PurgeMarkerService;
import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.contact.entity.ContactRequestBlockEntity;
import com.mannschaft.app.dashboard.entity.DashboardScopeTabOrderEntity;
import com.mannschaft.app.filesharing.entity.SharedFileStarEntity;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.notification.entity.NotificationSettingsEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.user.entity.UserBlockEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * CMP-260822-1243: 通常の退会受付から30日後の強削除まで、本人専用設定を実DBで確認する。
 * ddl-auto=create の試練であり、Flyway由来のCASCADEの証明はmigration試練へ分離する。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PersonalSettingsAccountPurgeIT extends AbstractMySqlIntegrationTest {

    private static final List<String> TABLES = List.of(
            "dashboard_widget_settings", "dashboard_scope_tab_order", "chat_contact_folders",
            "my_scope_folders", "user_calendar_sync_settings", "user_quick_memo_settings",
            "notification_settings", "user_interest_tags", "shared_file_stars", "contact_request_blocks");

    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountPurgeService accountPurgeService;
    @Autowired @Qualifier("purge-pool") private Executor purgeExecutor;
    @PersistenceContext private EntityManager entityManager;

    @Test
    @DisplayName("通常退会の猶予中は設定を保持し、30日後の実ユーザー削除で本人と子行だけを消す")
    void retainsSettingsUntilStrongPurgeAndPreservesOtherOwner() {
        // 共通基底の外部Redis mockだけを補完し、退会受付の実レートリミット処理を通す。
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        Long target = createUser("本人");
        Long other = createUser("別所有者");
        Long contact = createUser("対象外の連絡先");
        List<Long> chatFolderIds = new ArrayList<>();
        try {
            Long targetChatFolder = seedSettings(target, contact);
            chatFolderIds.add(targetChatFolder);
            Long otherChatFolder = seedSettings(other, contact);
            chatFolderIds.add(otherChatFolder);
            transactionTemplate.executeWithoutResult(tx -> entityManager.persist(
                    ContactRequestBlockEntity.builder().userId(other).blockedId(target).build()));
            transactionTemplate.executeWithoutResult(tx -> entityManager.persist(
                    UserBlockEntity.builder().blockerId(other).blockedId(target).build()));
            transactionTemplate.executeWithoutResult(tx -> insert("chat_contact_folder_items",
                    "folder_id,item_type,item_id,is_pinned,created_at",
                    otherChatFolder + ",'CONTACT'," + target + ",false,NOW()"));
            userService.requestWithdrawal(target, new RequestWithdrawalRequest(null));
            for (String table : TABLES) {
                // scope tabの即時削除は既承認の別経路であり、猶予中の保持は要件にしない。
                if (table.equals("dashboard_scope_tab_order")) {
                    continue;
                }
                assertThat(countOwner(table, target)).as("猶予中 %s", table).isEqualTo(1);
            }
            assertThat(count("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = " + target))
                    .as("猶予中の本人ブロック設定").isEqualTo(1);
            List<UserEntity> recentTargets = transactionTemplate.execute(tx -> userRepository.findPurgeTargets(
                    LocalDateTime.now().minusDays(30), PageRequest.of(0, 100)));
            assertThat(recentTargets).noneMatch(user -> user.getId().equals(target));

            transactionTemplate.executeWithoutResult(tx -> entityManager.createNativeQuery(
                    "UPDATE users SET deleted_at = DATE_SUB(NOW(), INTERVAL 31 DAY) WHERE id = :owner")
                    .setParameter("owner", target).executeUpdate());
            List<UserEntity> expiredTargets = transactionTemplate.execute(tx -> userRepository.findPurgeTargets(
                    LocalDateTime.now().minusDays(30), PageRequest.of(0, 100)));
            assertThat(expiredTargets).anyMatch(user -> user.getId().equals(target));
            // 外側の自前TXを作らず、本番schedulerが呼ぶ公開バッチから強削除を開始する。
            accountPurgeService.purgeExpiredAccounts();
            Map<String, Long> expected = new LinkedHashMap<>();
            expected.put("本人/users", 0L);
            for (String table : TABLES) {
                expected.put("本人/" + table, 0L);
                expected.put("別所有者/" + table, 1L);
            }
            expected.put("本人/chat_contact_folder_items", 0L);
            expected.put("別所有者/chat_contact_folder_items", 1L);
            expected.put("退会者へのcontact_request_blocks", 0L);
            expected.put("退会者へのchat_contact_folder_items", 0L);
            expected.put("本人/user_blocks", 0L);
            expected.put("別所有者/user_blocks", 1L);
            expected.put("退会者へのuser_blocks", 0L);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Map<String, Long> actual = new LinkedHashMap<>();
                actual.put("本人/users", count("SELECT COUNT(*) FROM users WHERE id = " + target));
                for (String table : TABLES) {
                    actual.put("本人/" + table, countOwner(table, target));
                    actual.put("別所有者/" + table, countOwner(table, other));
                }
                actual.put("本人/chat_contact_folder_items", count(
                        "SELECT COUNT(*) FROM chat_contact_folder_items WHERE folder_id = " + targetChatFolder));
                actual.put("別所有者/chat_contact_folder_items", count(
                        "SELECT COUNT(*) FROM chat_contact_folder_items WHERE folder_id = " + otherChatFolder));
                actual.put("退会者へのcontact_request_blocks", count(
                        "SELECT COUNT(*) FROM contact_request_blocks WHERE blocked_id = " + target));
                actual.put("退会者へのchat_contact_folder_items", count(
                        "SELECT COUNT(*) FROM chat_contact_folder_items WHERE item_type = 'CONTACT' AND item_id = "
                                + target));
                actual.put("本人/user_blocks", count("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = " + target));
                actual.put("別所有者/user_blocks", count("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = " + other));
                actual.put("退会者へのuser_blocks", count("SELECT COUNT(*) FROM user_blocks WHERE blocked_id = " + target));
                assertThat(actual).as("強削除後の全設定・子行のnative件数")
                        .containsExactlyInAnyOrderEntriesOf(expected);
            });
        } finally {
            ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) purgeExecutor;
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    pool.getActiveCount() == 0 && pool.getThreadPoolExecutor().getQueue().isEmpty());
            transactionTemplate.executeWithoutResult(tx -> {
                entityManager.createNativeQuery("DELETE FROM user_blocks WHERE blocker_id IN ("
                        + target + "," + other + ") OR blocked_id IN (" + target + "," + other + ")")
                        .executeUpdate();
                for (Long folderId : chatFolderIds) {
                    entityManager.createNativeQuery("DELETE FROM chat_contact_folder_items WHERE folder_id = "
                            + folderId).executeUpdate();
                }
                for (String table : TABLES) {
                    entityManager.createNativeQuery("DELETE FROM " + table + " WHERE user_id IN ("
                            + target + "," + other + ")").executeUpdate();
                }
                entityManager.createQuery("DELETE FROM AccountPurgeCompletionStatusEntity status "
                                + "WHERE status.userId IN :owners")
                        .setParameter("owners", List.of(target, other)).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM users WHERE id IN ("
                        + target + "," + other + "," + contact + ")").executeUpdate();
            });
        }
    }

    @Test
    @DisplayName("旧候補取得後に取消が先にcommitすると、本人と設定を保持しpurge開始マークを付けない")
    void cancellationCommittedBeforeMarkerPreservesCandidate() throws Exception {
        verifyCanceledCandidate(false);
    }

    @Test
    @DisplayName("旧候補取得後に取消と再申請が先にcommitすると、新しい30日猶予を侵害しない")
    void renewedWithdrawalCommittedBeforeMarkerPreservesCandidate() throws Exception {
        verifyCanceledCandidate(true);
    }

    private void verifyCanceledCandidate(boolean requestAgain) throws Exception {
        stubRedisValueOperations();
        Long target = createUser("取消競合の本人");
        Long contact = createUser("対象外の連絡先");
        AtomicReference<Thread> worker = new AtomicReference<>();
        ExecutorService executor = newWorker(worker);
        Long folder = null;
        try {
            folder = seedSettings(target, contact);
            prepareExpiredWithdrawal(target);
            AtomicReference<Future<?>> batch = new AtomicReference<>();
            transactionTemplate.executeWithoutResult(tx -> {
                assertThat(userRepository.findByIdForUpdateIncludingDeleted(target)).isPresent();
                // バッチには外側TXを渡さず、旧候補の取得後に実markerをrowlockで待機させる。
                batch.set(executor.submit(accountPurgeService::purgeExpiredAccounts));
                awaitWorkerFrame(worker, PurgeMarkerService.class.getName(), null);
                userService.cancelWithdrawal(target);
                if (requestAgain) {
                    userService.requestWithdrawal(target, new RequestWithdrawalRequest(null));
                }
            });
            batch.get().get(15, TimeUnit.SECONDS);
            Map<String, Long> expected = retainedCandidateCounts();
            Map<String, Long> actual = readRetainedCandidateCounts(target, folder);
            expected.put("最新の退会状態", 1L);
            actual.put("最新の退会状態", count("SELECT COUNT(*) FROM users WHERE id = " + target
                    + (requestAgain ? " AND deleted_at > DATE_SUB(NOW(), INTERVAL 1 DAY)"
                    : " AND deleted_at IS NULL")));
            assertThat(actual).as("取消または再申請commit後の旧purge候補")
                    .containsExactlyInAnyOrderEntriesOf(expected);
        } finally {
            finishWorker(executor);
            cleanupCandidateFixture(target, contact, folder);
        }
    }

    @Test
    @DisplayName("取消の上位RR snapshotが古くても、rowlock取得前に開始マークがcommitすると409で拒否する")
    void committedMarkerRejectsCancellationAfterOldSnapshot() throws Exception {
        stubRedisValueOperations();
        Long target = createUser("開始マーク競合の本人");
        Long contact = createUser("対象外の連絡先");
        AtomicReference<Thread> worker = new AtomicReference<>();
        ExecutorService executor = newWorker(worker);
        Long folder = null;
        try {
            folder = seedSettings(target, contact);
            prepareExpiredWithdrawal(target);
            AtomicReference<Future<?>> cancellation = new AtomicReference<>();
            TransactionTemplate repeatableRead = new TransactionTemplate(transactionTemplate.getTransactionManager());
            repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transactionTemplate.executeWithoutResult(tx -> {
                assertThat(userRepository.findByIdForUpdateIncludingDeleted(target)).isPresent();
                cancellation.set(executor.submit(() -> repeatableRead.executeWithoutResult(cancelTx -> {
                    // 上位TXのconsistent readを先に作る。lock後の通常SELECT再読では修復できない境界。
                    assertThat(((Number) entityManager.createNativeQuery(
                            "SELECT COUNT(*) FROM users WHERE id = :owner AND purge_started_at IS NULL")
                            .setParameter("owner", target).getSingleResult()).longValue()).isEqualTo(1);
                    userService.cancelWithdrawal(target);
                })));
                awaitWorkerFrame(worker, null, "findByIdForUpdateIncludingDeleted");
                // 同じnative marker SQLをlock所有TXでcommitし、待機中の取消より先に確定させる。
                userRepository.markPurgeStarted(target);
            });
            Throwable failure = catchThrowable(() -> cancellation.get().get(15, TimeUnit.SECONDS));
            assertThat(failure).isInstanceOf(ExecutionException.class);
            assertThat(failure.getCause()).isInstanceOf(BusinessException.class)
                    .satisfies(cause -> assertThat(((BusinessException) cause).getErrorCode())
                            .isEqualTo(GdprErrorCode.GDPR_012));
            Map<String, Long> expected = retainedCandidateCounts();
            Map<String, Long> actual = readRetainedCandidateCounts(target, folder);
            expected.put("開始マークなし", 0L);
            expected.put("元の退会申請", 1L);
            actual.put("元の退会申請", count("SELECT COUNT(*) FROM users WHERE id = " + target
                    + " AND deleted_at < DATE_SUB(NOW(), INTERVAL 30 DAY)"));
            assertThat(actual).as("開始マークcommit後の取消拒否と設定保持")
                    .containsExactlyInAnyOrderEntriesOf(expected);
        } finally {
            finishWorker(executor);
            cleanupCandidateFixture(target, contact, folder);
        }
    }

    private void prepareExpiredWithdrawal(Long target) {
        userService.requestWithdrawal(target, new RequestWithdrawalRequest(null));
        transactionTemplate.executeWithoutResult(tx -> entityManager.createNativeQuery(
                "UPDATE users SET deleted_at = DATE_SUB(NOW(), INTERVAL 31 DAY) WHERE id = :owner")
                .setParameter("owner", target).executeUpdate());
    }

    private Map<String, Long> retainedCandidateCounts() {
        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("本人/users", 1L);
        expected.put("開始マークなし", 1L);
        expected.put("強削除時刻なし", 1L);
        for (String table : TABLES) {
            if (!table.equals("dashboard_scope_tab_order")) {
                expected.put("本人/" + table, 1L);
            }
        }
        expected.put("本人/user_blocks", 1L);
        expected.put("本人/chat_contact_folder_items", 1L);
        return expected;
    }

    private Map<String, Long> readRetainedCandidateCounts(Long target, Long folder) {
        Map<String, Long> actual = new LinkedHashMap<>();
        actual.put("本人/users", count("SELECT COUNT(*) FROM users WHERE id = " + target));
        actual.put("開始マークなし", count("SELECT COUNT(*) FROM users WHERE id = " + target
                + " AND purge_started_at IS NULL"));
        actual.put("強削除時刻なし", count("SELECT COUNT(*) FROM users WHERE id = " + target
                + " AND purged_at IS NULL"));
        for (String table : TABLES) {
            if (!table.equals("dashboard_scope_tab_order")) {
                actual.put("本人/" + table, countOwner(table, target));
            }
        }
        actual.put("本人/user_blocks", count("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = " + target));
        actual.put("本人/chat_contact_folder_items", count(
                "SELECT COUNT(*) FROM chat_contact_folder_items WHERE folder_id = " + folder));
        return actual;
    }

    private ExecutorService newWorker(AtomicReference<Thread> worker) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "cmp1243-marker-cancel");
            worker.set(thread);
            return thread;
        });
    }

    private void awaitWorkerFrame(AtomicReference<Thread> worker, String className, String methodName) {
        await().atMost(Duration.ofSeconds(10)).until(() -> worker.get() != null
                && Arrays.stream(worker.get().getStackTrace()).anyMatch(frame ->
                (className == null || frame.getClassName().equals(className))
                        && (methodName == null || frame.getMethodName().equals(methodName))));
    }

    private void finishWorker(ExecutorService executor) throws InterruptedException {
        // ここへ来る前に制御用TXはcommit/rollback済みで、所有rowlockは必ず解放されている。
        executor.shutdown();
        if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("自隊threadの終了").isTrue();
        }
    }

    private void cleanupCandidateFixture(Long target, Long contact, Long folder) {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) purgeExecutor;
        await().atMost(Duration.ofSeconds(10)).until(() ->
                pool.getActiveCount() == 0 && pool.getThreadPoolExecutor().getQueue().isEmpty());
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery("DELETE FROM user_blocks WHERE blocker_id = " + target
                    + " OR blocked_id = " + target).executeUpdate();
            if (folder != null) {
                entityManager.createNativeQuery("DELETE FROM chat_contact_folder_items WHERE folder_id = "
                        + folder).executeUpdate();
            }
            for (String table : TABLES) {
                entityManager.createNativeQuery("DELETE FROM " + table + " WHERE user_id = " + target)
                        .executeUpdate();
            }
            entityManager.createQuery("DELETE FROM AccountPurgeCompletionStatusEntity status "
                            + "WHERE status.userId = :owner")
                    .setParameter("owner", target).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM users WHERE id IN (" + target + "," + contact + ")")
                    .executeUpdate();
        });
    }

    private void stubRedisValueOperations() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
    }

    private Long createUser(String name) {
        return transactionTemplate.execute(tx -> {
            UserEntity user = UserEntity.builder()
                    .email("cmp1243-" + System.nanoTime() + "@example.com")
                    .lastName("試練").firstName(name).displayName(name)
                    .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                    .isSearchable(true).build();
            entityManager.persist(user);
            entityManager.flush();
            return user.getId();
        });
    }

    private Long seedSettings(Long owner, Long blockedUser) {
        return transactionTemplate.execute(tx -> {
            insert("dashboard_widget_settings", "user_id,scope_type,scope_id,widget_key,is_visible,sort_order,created_at,updated_at",
                    owner + ",'PERSONAL',0,'calendar',true,0,NOW(),NOW()");
            entityManager.persist(DashboardScopeTabOrderEntity.builder()
                    .userId(owner).scopeType("TEAM").scopeId(123L).sortOrder(0).build());
            insert("chat_contact_folders", "user_id,name,sort_order,created_at,updated_at",
                    owner + ",'試練フォルダ',0,NOW(),NOW()");
            Long folder = ((Number) entityManager.createNativeQuery(
                    "SELECT id FROM chat_contact_folders WHERE user_id = " + owner).getSingleResult()).longValue();
            insert("chat_contact_folder_items", "folder_id,item_type,item_id,is_pinned,created_at",
                    folder + ",'CONTACT'," + blockedUser + ",false,NOW()");
            insert("my_scope_folders", "user_id,scope_type,name,sort_order,is_default,created_at,updated_at",
                    owner + ",'TEAM','試練分類',0,false,NOW(),NOW()");
            insert("user_calendar_sync_settings", "user_id,scope_type,scope_id,is_enabled,created_at,updated_at",
                    owner + ",'TEAM',123,true,NOW(),NOW()");
            insert("user_quick_memo_settings", "user_id,reminder_enabled,created_at,updated_at",
                    owner + ",false,NOW(),NOW()");
            entityManager.persist(NotificationSettingsEntity.builder().userId(owner).build());
            entityManager.persist(UserInterestTagEntity.create(owner, "cmp1243", "a".repeat(64)));
            entityManager.persist(SharedFileStarEntity.builder().userId(owner).fileId(123L).build());
            entityManager.persist(ContactRequestBlockEntity.builder().userId(owner).blockedId(blockedUser).build());
            entityManager.persist(UserBlockEntity.builder().blockerId(owner).blockedId(blockedUser).build());
            entityManager.flush();
            return folder;
        });
    }

    private void insert(String table, String columns, String values) {
        entityManager.createNativeQuery("INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")")
                .executeUpdate();
    }

    private long countOwner(String table, Long owner) {
        return count("SELECT COUNT(*) FROM " + table + " WHERE user_id = " + owner);
    }

    private long count(String sql) {
        return transactionTemplate.execute(tx -> ((Number) entityManager.createNativeQuery(sql)
                .getSingleResult()).longValue());
    }
}
