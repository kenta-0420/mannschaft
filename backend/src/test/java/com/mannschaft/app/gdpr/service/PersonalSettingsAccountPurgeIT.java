package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.auth.dto.RequestWithdrawalRequest;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.UserInterestTagEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.PurgeMarkerService;
import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.contact.entity.ContactRequestBlockEntity;
import com.mannschaft.app.dashboard.entity.DashboardScopeTabOrderEntity;
import com.mannschaft.app.filesharing.entity.SharedFileStarEntity;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.gdpr.dto.RetryResultResponse;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
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

    // user_blocks は両端の所有境界を別に検証するため、この37表の user_id 集合には入れない。
    private static final List<String> ALL_SETTINGS_TABLES = Stream.concat(TABLES.stream(), Stream.of(
            "user_action_memo_settings", "action_memo_tags", "point_card_user_settings", "point_card_groups",
            "timeline_bookmarks", "search_saved_queries", "appearance_settings", "user_nav_settings",
            "gamification_user_settings", "user_reflection_settings", "personal_timetable_settings",
            "user_blog_settings", "chat_message_bookmarks", "kb_page_favorites", "user_mutes", "user_favorites",
            "scope_member_calendar_settings", "notification_preferences", "notification_type_preferences",
            "push_subscriptions", "user_calendar_layer_settings", "user_weather_locations", "inbox_item_states",
            "notification_labels", "inbox_label_links", "timetable_slot_user_note_fields",
            "seal_scope_defaults")).toList();
    private static final List<String> SETTING_DOMAINS = List.of(
            "actionmemo", "pointcard", "timeline", "search", "dashboard", "scopefolder", "schedule", "quickmemo",
            "auth", "notification", "filesharing", "contact", "user", "appearance", "navsettings", "gamification",
            "reflection", "timetable.personal", "cms", "chat", "knowledgebase", "favorite", "membership", "weather",
            "inbox", "timetable.notes", "seal", "village.settings");
    private static final List<String> EXISTING_DOMAINS = List.of(
            "role", "team", "payment", "chart", "proxy", "errorreport", "resume", "billing");
    private static final List<String> RETAINED_UNTIL_STRONG = Stream.concat(
            TABLES.stream().filter(table -> !table.equals("dashboard_scope_tab_order")),
            Stream.of("user_action_memo_settings", "action_memo_tags", "point_card_user_settings",
                    "point_card_groups", "timeline_bookmarks", "search_saved_queries",
                    "timetable_slot_user_note_fields", "seal_scope_defaults")).toList();

    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountPurgeService accountPurgeService;
    @Autowired private GdprPurgeRetryFacade retryService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired @Qualifier("purge-pool") private Executor purgeExecutor;
    @PersistenceContext private EntityManager entityManager;

    @Test
    @DisplayName("通常退会の承認済猶予保持から38親設定と論理行・子の強消去、別owner保持と27domain完了を確認する")
    void retainsSettingsUntilStrongPurgeAndPreservesOtherOwner() {
        // 共通基底の外部Redis mockだけを補完し、退会受付の実レートリミット処理を通す。
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        Long target = createUser("本人");
        Long other = createUser("別所有者");
        Long contact = createUser("対象外の連絡先");
        List<Long> chatFolderIds = new ArrayList<>();
        List<Long> scopeFolderIds = new ArrayList<>();
        try {
            Long targetChatFolder = seedSettings(target, contact);
            chatFolderIds.add(targetChatFolder);
            Long otherChatFolder = seedSettings(other, contact);
            chatFolderIds.add(otherChatFolder);
            scopeFolderIds.addAll(seedAdditionalSettings(target, contact));
            scopeFolderIds.addAll(seedAdditionalSettings(other, contact));
            List<Long> targetScopeFolders = scopeFolderIds(target);
            List<Long> otherScopeFolders = scopeFolderIds(other);
            Map<String, Long> targetCounts = readOwnerCounts(target);
            Map<String, Long> otherCounts = readOwnerCounts(other);
            transactionTemplate.executeWithoutResult(tx -> entityManager.persist(
                    ContactRequestBlockEntity.builder().userId(other).blockedId(target).build()));
            transactionTemplate.executeWithoutResult(tx -> entityManager.persist(
                    UserBlockEntity.builder().blockerId(other).blockedId(target).build()));
            transactionTemplate.executeWithoutResult(tx -> insert("chat_contact_folder_items",
                    "folder_id,item_type,item_id,is_pinned,created_at",
                    otherChatFolder + ",'CONTACT'," + target + ",false,NOW()"));
            userService.requestWithdrawal(target, new RequestWithdrawalRequest(null));
            for (String table : RETAINED_UNTIL_STRONG) {
                // 既承認の30日保持だけを検証し、scope tab/弱10表のタイミングを変更しない。
                assertThat(countOwner(table, target)).as("猶予中 %s", table).isEqualTo(targetCounts.get(table));
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
            for (String table : ALL_SETTINGS_TABLES) {
                expected.put("本人/" + table, 0L);
                expected.put("別所有者/" + table, otherCounts.get(table));
            }
            expected.put("本人/chat_contact_folder_items", 0L);
            expected.put("別所有者/chat_contact_folder_items", 1L);
            expected.put("退会者へのcontact_request_blocks", 0L);
            expected.put("退会者へのchat_contact_folder_items", 0L);
            expected.put("本人/user_blocks", 0L);
            expected.put("別所有者/user_blocks", 1L);
            expected.put("退会者へのuser_blocks", 0L);
            expected.put("本人/my_scope_folder_items", 0L);
            expected.put("別所有者/my_scope_folder_items", 2L);
            addExpectedCompletionCounts(expected, null);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Map<String, Long> actual = new LinkedHashMap<>();
                actual.put("本人/users", count("SELECT COUNT(*) FROM users WHERE id = " + target));
                for (String table : ALL_SETTINGS_TABLES) {
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
                actual.put("本人/my_scope_folder_items", countFolderItems(targetScopeFolders));
                actual.put("別所有者/my_scope_folder_items", countFolderItems(otherScopeFolders));
                addActualCompletionCounts(actual, target);
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
                for (Long folderId : scopeFolderIds) {
                    entityManager.createNativeQuery("DELETE FROM my_scope_folder_items WHERE folder_id = "
                            + folderId).executeUpdate();
                }
                for (String table : ALL_SETTINGS_TABLES) {
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

    @Test
    @DisplayName("設定0件の公開batchと強イベントを重複実行しても27domainは一意完了し別ownerの全設定を保つ")
    void emptySettingsAndDuplicateEventsPreserveOtherOwner() {
        stubRedisValueOperations();
        Long target = createUser("設定0件の本人");
        Long other = createUser("別所有者");
        Long contact = createUser("対象外の連絡先");
        List<Long> chatFolders = new ArrayList<>();
        List<Long> scopeFolders = new ArrayList<>();
        try {
            chatFolders.add(seedSettings(other, contact));
            scopeFolders.addAll(seedAdditionalSettings(other, contact));
            Map<String, Long> retained = readOwnerCounts(other);
            prepareExpiredWithdrawal(target);
            accountPurgeService.purgeExpiredAccounts();
            awaitPurgePoolIdle();
            accountPurgeService.purgeExpiredAccounts();
            transactionTemplate.executeWithoutResult(tx -> {
                eventPublisher.publishEvent(new AccountPurgedEvent(target, "a".repeat(64)));
                eventPublisher.publishEvent(new AccountPurgedEvent(target, "a".repeat(64)));
            });
            Map<String, Long> expected = new LinkedHashMap<>();
            addExpectedCompletionCounts(expected, null);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Map<String, Long> actual = new LinkedHashMap<>();
                addActualCompletionCounts(actual, target);
                assertThat(actual).as("空と重複イベントのdomain登録・完了").containsExactlyInAnyOrderEntriesOf(expected);
                assertThat(readOwnerCounts(target).values()).containsOnly(0L);
                assertThat(readOwnerCounts(other)).as("空と重複イベント後の別所有者")
                        .containsExactlyInAnyOrderEntriesOf(retained);
                assertThat(countFolderItems(scopeFolders)).isEqualTo(2);
            });
        } finally {
            cleanupFullSettings(List.of(target, other), contact, chatFolders, scopeFolders);
        }
    }

    @Test
    @DisplayName("本人限定DELETE障害はdashboardだけPENDINGに残り、失敗記録と実retry後commitでSUCCESSになる")
    void partialDeleteFailureRemainsPendingUntilRealRetryCommits() {
        stubRedisValueOperations();
        Long target = createUser("部分失敗の本人");
        Long other = createUser("別所有者");
        Long contact = createUser("対象外の連絡先");
        List<Long> chatFolders = new ArrayList<>();
        List<Long> scopeFolders = new ArrayList<>();
        String trigger = "cmp1243_widget_" + target;
        try {
            chatFolders.add(seedSettings(target, contact));
            chatFolders.add(seedSettings(other, contact));
            scopeFolders.addAll(seedAdditionalSettings(target, contact));
            scopeFolders.addAll(seedAdditionalSettings(other, contact));
            Map<String, Long> retained = readOwnerCounts(other);
            executeOwnedTriggerDdl("CREATE TRIGGER " + trigger + " BEFORE DELETE ON dashboard_widget_settings FOR EACH ROW "
                            + "BEGIN IF OLD.user_id = " + target + " THEN SIGNAL SQLSTATE '45000' "
                            + "SET MESSAGE_TEXT = 'cmp1243 owned delete failure'; END IF; END");
            prepareExpiredWithdrawal(target);
            accountPurgeService.purgeExpiredAccounts();
            Map<String, Long> expected = new LinkedHashMap<>();
            addExpectedCompletionCounts(expected, "dashboard");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Map<String, Long> actual = new LinkedHashMap<>();
                addActualCompletionCounts(actual, target);
                assertThat(actual).as("部分失敗と他domainのcommit").containsExactlyInAnyOrderEntriesOf(expected);
                assertThat(countDomain(target, "dashboard", "PENDING")).isEqualTo(1);
                assertThat(countOwner("dashboard_widget_settings", target)).isEqualTo(1);
                assertThat(count("SELECT COUNT(*) FROM users WHERE id = " + target)).isZero();
            });
            awaitPurgePoolIdle();
            RetryResultResponse failed = retryService.retryDomainPurge(target, "dashboard");
            assertThat(failed.succeeded()).isFalse();
            assertThat(failed.newStatus()).isEqualTo("PENDING");
            assertThat(failed.retryCount()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM account_purge_completion_status WHERE user_id = " + target
                    + " AND domain_name = 'dashboard' AND status = 'PENDING' AND retry_count = 1"
                    + " AND last_retried_at IS NOT NULL AND completed_at IS NULL")).isEqualTo(1);
            dropOwnedTrigger(trigger);
            RetryResultResponse recovered = retryService.retryDomainPurge(target, "dashboard");
            assertThat(recovered.succeeded()).isTrue();
            assertThat(recovered.newStatus()).isEqualTo("SUCCESS");
            assertThat(recovered.retryCount()).isEqualTo(2);
            // retry が返った時点で別TXから0/SUCCESSが見えることを確認する。
            assertThat(readOwnerCounts(target).values()).containsOnly(0L);
            assertThat(count("SELECT COUNT(*) FROM chat_contact_folder_items WHERE folder_id = " + chatFolders.get(0)))
                    .isZero();
            assertThat(countDomain(target, "dashboard", "SUCCESS")).isEqualTo(1);
            assertThat(retryService.retryDomainPurge(target, "dashboard").retryCount()).isEqualTo(2);
            assertThat(readOwnerCounts(other)).containsExactlyInAnyOrderEntriesOf(retained);
        } finally {
            awaitPurgePoolIdle();
            dropOwnedTrigger(trigger);
            cleanupFullSettings(List.of(target, other), contact, chatFolders, scopeFolders);
        }
    }

    @Test
    @DisplayName("既存purge-poolの実queue拒否でも27domainのPENDINGを残し、解放後の実retryで本人だけを消去する")
    void rejectedPurgeQueueRetainsPendingForRealRetry() throws InterruptedException {
        stubRedisValueOperations();
        Long target = createUser("queue拒否の本人");
        Long other = createUser("別所有者");
        Long contact = createUser("対象外の連絡先");
        List<Long> chatFolders = new ArrayList<>();
        List<Long> scopeFolders = new ArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        try {
            chatFolders.add(seedSettings(target, contact));
            chatFolders.add(seedSettings(other, contact));
            scopeFolders.addAll(seedAdditionalSettings(target, contact));
            scopeFolders.addAll(seedAdditionalSettings(other, contact));
            Map<String, Long> retained = readOwnerCounts(other);
            prepareExpiredWithdrawal(target);
            awaitPurgePoolIdle();
            ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) purgeExecutor;
            CountDownLatch active = new CountDownLatch(pool.getMaxPoolSize());
            Runnable ownedBlocker = () -> {
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
                    pool.execute(ownedBlocker);
                } catch (TaskRejectedException expectedRejection) {
                    rejected = true;
                    break;
                }
            }
            assertThat(rejected).as("新pool設定ではなく既存実queueの拒否境界").isTrue();
            assertThat(active.await(10, TimeUnit.SECONDS)).as("自隊blockerが既存max workerを占有").isTrue();
            assertThat(pool.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
            accountPurgeService.purgeExpiredAccounts();
            assertThat(count("SELECT COUNT(*) FROM users WHERE id = " + target)).isZero();
            Map<String, Long> pending = new LinkedHashMap<>();
            for (String domain : SETTING_DOMAINS) {
                pending.put(domain, countDomain(target, domain, "PENDING"));
            }
            assertThat(pending.values()).as("配送拒否でもcommit済みPENDINGを全27domainから回復できる")
                    .containsOnly(1L);
            for (String domain : EXISTING_DOMAINS) {
                assertThat(countDomain(target, domain, null)).as("既存domain登録 %s", domain).isEqualTo(1);
            }
            release.countDown();
            awaitPurgePoolIdle();
            for (String domain : SETTING_DOMAINS) {
                RetryResultResponse recovered = retryService.retryDomainPurge(target, domain);
                assertThat(recovered.succeeded()).as("配送拒否から実retry %s", domain).isTrue();
                assertThat(recovered.retryCount()).isEqualTo(1);
                assertThat(countDomain(target, domain, "SUCCESS")).isEqualTo(1);
            }
            assertThat(readOwnerCounts(target).values()).containsOnly(0L);
            assertThat(count("SELECT COUNT(*) FROM chat_contact_folder_items WHERE folder_id = " + chatFolders.get(0))).isZero();
            assertThat(countFolderItems(scopeFolders.subList(0, 2))).isZero();
            assertThat(readOwnerCounts(other)).containsExactlyInAnyOrderEntriesOf(retained);
        } finally {
            // 自隊latchだけを解放し、実idleにならなければfixture DELETEへ進まない。
            release.countDown();
            cleanupFullSettings(List.of(target, other), contact, chatFolders, scopeFolders);
        }
    }

    private void dropOwnedTrigger(String trigger) {
        executeOwnedTriggerDdl("DROP TRIGGER IF EXISTS " + trigger);
    }

    private void executeOwnedTriggerDdl(String sql) {
        // binlog有効時のtrigger権限はOrgMemberProfileCopyRollbackITと同じ所有containerのroot金型を使う。
        // 業務Beanや共有container設定は差し替えない。
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException("所有containerの試練trigger DDL失敗", failure);
        }
    }

    @Test
    @DisplayName("owner DELETE commit後のSUCCESS記録障害はデータ0のPENDINGを残し、実retryで完了記録を回復する")
    void completionFailureRemainsPendingAfterOwnerDeleteCommit() {
        stubRedisValueOperations();
        Long target = createUser("完了記録障害の本人");
        Long other = createUser("別所有者");
        Long contact = createUser("対象外の連絡先");
        List<Long> chatFolders = new ArrayList<>();
        List<Long> scopeFolders = new ArrayList<>();
        String trigger = "cmp1243_success_" + target;
        try {
            chatFolders.add(seedSettings(target, contact));
            chatFolders.add(seedSettings(other, contact));
            scopeFolders.addAll(seedAdditionalSettings(target, contact));
            scopeFolders.addAll(seedAdditionalSettings(other, contact));
            Map<String, Long> retained = readOwnerCounts(other);
            executeOwnedTriggerDdl("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON account_purge_completion_status FOR EACH ROW "
                            + "BEGIN IF OLD.user_id = " + target + " AND OLD.domain_name = 'dashboard' "
                            + "AND NEW.status = 'SUCCESS' THEN SIGNAL SQLSTATE '45000' "
                            + "SET MESSAGE_TEXT = 'cmp1243 owned completion failure'; END IF; END");
            prepareExpiredWithdrawal(target);
            accountPurgeService.purgeExpiredAccounts();
            Map<String, Long> expected = new LinkedHashMap<>();
            addExpectedCompletionCounts(expected, "dashboard");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Map<String, Long> actual = new LinkedHashMap<>();
                addActualCompletionCounts(actual, target);
                assertThat(actual).as("owner commit後の完了記録障害").containsExactlyInAnyOrderEntriesOf(expected);
                assertThat(readOwnerCounts(target).values()).containsOnly(0L);
                assertThat(countDomain(target, "dashboard", "PENDING")).isEqualTo(1);
                assertThat(count("SELECT COUNT(*) FROM chat_contact_folder_items WHERE folder_id = " + chatFolders.get(0))).isZero();
                assertThat(countFolderItems(scopeFolders.subList(0, 2))).isZero();
            });
            awaitPurgePoolIdle();
            dropOwnedTrigger(trigger);
            RetryResultResponse recovered = retryService.retryDomainPurge(target, "dashboard");
            assertThat(recovered.succeeded()).isTrue();
            assertThat(recovered.retryCount()).isEqualTo(1);
            assertThat(countDomain(target, "dashboard", "SUCCESS")).isEqualTo(1);
            assertThat(readOwnerCounts(other)).containsExactlyInAnyOrderEntriesOf(retained);
        } finally {
            awaitPurgePoolIdle();
            dropOwnedTrigger(trigger);
            cleanupFullSettings(List.of(target, other), contact, chatFolders, scopeFolders);
        }
    }

    private void awaitPurgePoolIdle() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) purgeExecutor;
        await().atMost(Duration.ofSeconds(10)).until(() ->
                pool.getActiveCount() == 0 && pool.getThreadPoolExecutor().getQueue().isEmpty());
    }

    private void cleanupFullSettings(List<Long> owners, Long contact, List<Long> chatFolders, List<Long> scopeFolders) {
        awaitPurgePoolIdle();
        String ownerIds = String.join(",", owners.stream().map(String::valueOf).toList());
        transactionTemplate.executeWithoutResult(tx -> {
            for (Long folder : chatFolders) {
                entityManager.createNativeQuery("DELETE FROM chat_contact_folder_items WHERE folder_id = " + folder)
                        .executeUpdate();
            }
            for (Long folder : scopeFolders) {
                entityManager.createNativeQuery("DELETE FROM my_scope_folder_items WHERE folder_id = " + folder)
                        .executeUpdate();
            }
            entityManager.createNativeQuery("DELETE FROM inbox_label_links WHERE user_id IN (" + ownerIds + ")")
                    .executeUpdate();
            for (String table : ALL_SETTINGS_TABLES) {
                entityManager.createNativeQuery("DELETE FROM " + table + " WHERE user_id IN (" + ownerIds + ")")
                        .executeUpdate();
            }
            entityManager.createNativeQuery("DELETE FROM user_blocks WHERE blocker_id IN (" + ownerIds
                    + ") OR blocked_id IN (" + ownerIds + ")").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM account_purge_completion_status WHERE user_id IN (" + ownerIds + ")")
                    .executeUpdate();
            entityManager.createNativeQuery("DELETE FROM users WHERE id IN (" + ownerIds + "," + contact + ")")
                    .executeUpdate();
        });
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

    /** Entity生成schemaの必須列を埋め、本文テーブルを作らず本人専用設定だけを用意する。 */
    private List<Long> seedAdditionalSettings(Long owner, Long contact) {
        return transactionTemplate.execute(tx -> {
            insert("user_action_memo_settings",
                    "user_id,mood_enabled,default_category,reminder_enabled,created_at,updated_at",
                    owner + ",false,'PRIVATE',false,NOW(),NOW()");
            insert("action_memo_tags", "user_id,name,sort_order,created_at,updated_at",
                    owner + ",'現役の私有タグ',0,NOW(),NOW()");
            insert("action_memo_tags", "user_id,name,sort_order,deleted_at,created_at,updated_at",
                    owner + ",'論理削除済の私有タグ',1,NOW(),NOW(),NOW()");
            insert("point_card_user_settings", "user_id,is_enabled,require_biometric_on_show,created_at,updated_at",
                    owner + ",false,false,NOW(),NOW()");
            insert("point_card_groups", "id,user_id,name,display_order,created_at,updated_at",
                    "'" + UuidV7.generate() + "'," + owner + ",'本人の分類',0,NOW(),NOW()");
            insert("timeline_bookmarks", "user_id,timeline_post_id,created_at", owner + ",123,NOW()");
            insert("search_saved_queries", "user_id,name,query_params,created_at", owner + ",'本人検索','{}',NOW()");
            insert("appearance_settings", "id,user_id,theme,bg_color,dark_bg_color,hide_chat_preview,created_at,updated_at",
                    binaryUuid() + "," + owner + ",'LIGHT','#ffffff','#18181b',false,NOW(),NOW()");
            insert("user_nav_settings", "user_id,hidden_nav_keys,updated_at", owner + ",'[]',NOW()");
            insert("gamification_user_settings",
                    "user_id,scope_type,scope_id,show_in_ranking,show_badges,created_at,updated_at",
                    owner + ",'TEAM',123,true,true,NOW(),NOW()");
            insert("user_reflection_settings", "user_id,remind_hour,created_at,updated_at", owner + ",9,NOW(),NOW()");
            insert("personal_timetable_settings", "user_id,auto_reflect_class_changes_to_calendar,"
                            + "notify_team_slot_note_updates,default_period_template,visible_default_fields,created_at,updated_at",
                    owner + ",true,true,'CUSTOM','[]',NOW(),NOW()");
            // 定義だけを対象とする。既存メモの本文・custom_field_values JSONは作成も変更もしない。
            insert("timetable_slot_user_note_fields", "user_id,label,placeholder,sort_order,max_length,created_at,updated_at",
                    owner + ",'本人の定義','本人の入力案内',0,2000,NOW(),NOW()");
            // Entity生成schemaではseal_idはscalar列。印鑑・押印履歴を削除対象へ混ぜない。
            for (String scope : List.of("DEFAULT", "TEAM", "ORGANIZATION")) {
                insert("seal_scope_defaults", "user_id,scope_type,scope_id,seal_id,created_at,updated_at",
                        owner + ",'" + scope + "'," + (scope.equals("DEFAULT") ? "NULL" : "123")
                                + ",123,NOW(),NOW()");
            }
            insert("user_blog_settings", "user_id,self_review_enabled,self_review_start,self_review_end,created_at,updated_at",
                    owner + ",false,'23:00:00','06:00:00',NOW(),NOW()");
            insert("chat_message_bookmarks", "user_id,message_id,created_at", owner + ",123,NOW()");
            insert("kb_page_favorites", "user_id,kb_page_id,created_at", owner + ",123,NOW()");
            insert("user_mutes", "user_id,muted_type,muted_id,created_at", owner + ",'USER'," + contact + ",NOW()");
            insert("user_favorites", "id,user_id,entity_type,entity_id,display_order,created_at",
                    binaryUuid() + "," + owner + ",'TEAM','123',0,NOW()");
            insert("scope_member_calendar_settings", "id,user_id,scope_type,scope_id,calendar_color",
                    binaryUuid() + "," + owner + ",'TEAM',123,'#123456'");
            insert("notification_preferences", "user_id,scope_type,scope_id,is_enabled,created_at,updated_at",
                    owner + ",'TEAM',123,true,NOW(),NOW()");
            insert("notification_type_preferences",
                    "user_id,notification_type,is_enabled,channel_override,in_app_enabled,push_enabled,created_at,updated_at",
                    owner + ",'CMP1243',true,false,true,true,NOW(),NOW()");
            insert("push_subscriptions", "user_id,endpoint,p256dh_key,auth_key,created_at",
                    owner + ",'https://example.invalid/cmp1243/" + owner + "','fixture','fixture',NOW()");
            insert("user_calendar_layer_settings", "id,user_id,scope_type,scope_id,hidden,created_at,updated_at",
                    binaryUuid() + "," + owner + ",'PERSONAL',0,false,NOW(),NOW()");
            insert("user_weather_locations", "id,user_id,label,country_code,postal_code_hash,latitude_rounded,"
                            + "longitude_rounded,place_name_snapshot,derived_at,created_at,updated_at",
                    binaryUuid() + "," + owner + ",'home','JP','" + "a".repeat(64)
                            + "',35.0,139.0,'試練地点',NOW(),NOW(),NOW()");
            insert("inbox_item_states", "id,user_id,source_type,source_id,archived_at,created_at,updated_at",
                    binaryUuid() + "," + owner + ",'NOTIFICATION',123,NOW(),NOW(),NOW()");
            String labelId = binaryUuid();
            insert("notification_labels", "id,user_id,name,sort_order,created_at,updated_at",
                    labelId + "," + owner + ",'現役の私有ラベル',0,NOW(),NOW()");
            insert("notification_labels", "id,user_id,name,sort_order,deleted_at,created_at,updated_at",
                    binaryUuid() + "," + owner + ",'論理削除済の私有ラベル',1,NOW(),NOW(),NOW()");
            insert("inbox_label_links", "id,user_id,label_id,source_type,source_id,created_at",
                    binaryUuid() + "," + owner + "," + labelId + ",'NOTIFICATION',123,NOW()");
            insert("my_scope_folders", "user_id,scope_type,name,sort_order,is_default,deleted_at,created_at,updated_at",
                    owner + ",'ORGANIZATION','論理削除済の私有分類',1,false,NOW(),NOW(),NOW()");
            List<Long> folders = scopeFolderIds(owner);
            for (Long folder : folders) {
                insert("my_scope_folder_items", "folder_id,scope_id,sort_order,assigned_via,created_at",
                        folder + ",123,0,'MANUAL',NOW()");
            }
            return folders;
        });
    }

    private String binaryUuid() {
        return "UNHEX('" + UuidV7.generate().toString().replace("-", "") + "')";
    }

    private List<Long> scopeFolderIds(Long owner) {
        return transactionTemplate.execute(tx -> {
            List<?> ids = entityManager.createNativeQuery("SELECT id FROM my_scope_folders WHERE user_id = :owner")
                    .setParameter("owner", owner).getResultList();
            return ids.stream().map(id -> ((Number) id).longValue()).toList();
        });
    }

    private long countFolderItems(List<Long> folders) {
        if (folders.isEmpty()) {
            return 0;
        }
        return count("SELECT COUNT(*) FROM my_scope_folder_items WHERE folder_id IN ("
                + String.join(",", folders.stream().map(String::valueOf).toList()) + ")");
    }

    private Map<String, Long> readOwnerCounts(Long owner) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : ALL_SETTINGS_TABLES) {
            counts.put(table, countOwner(table, owner));
        }
        counts.put("user_blocks", count("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = " + owner));
        return counts;
    }

    private void addExpectedCompletionCounts(Map<String, Long> expected, String pendingDomain) {
        for (String domain : EXISTING_DOMAINS) {
            expected.put("既存domain登録/" + domain, 1L);
        }
        for (String domain : SETTING_DOMAINS) {
            expected.put("設定domain登録/" + domain, 1L);
            expected.put("設定domainSUCCESS/" + domain, domain.equals(pendingDomain) ? 0L : 1L);
        }
        expected.put("全domain登録数", (long) EXISTING_DOMAINS.size() + SETTING_DOMAINS.size());
    }

    private void addActualCompletionCounts(Map<String, Long> actual, Long owner) {
        for (String domain : EXISTING_DOMAINS) {
            actual.put("既存domain登録/" + domain, countDomain(owner, domain, null));
        }
        for (String domain : SETTING_DOMAINS) {
            actual.put("設定domain登録/" + domain, countDomain(owner, domain, null));
            actual.put("設定domainSUCCESS/" + domain, countDomain(owner, domain, "SUCCESS"));
        }
        actual.put("全domain登録数", count("SELECT COUNT(*) FROM account_purge_completion_status WHERE user_id = " + owner));
    }

    private long countDomain(Long owner, String domain, String status) {
        return count("SELECT COUNT(*) FROM account_purge_completion_status WHERE user_id = " + owner
                + " AND domain_name = '" + domain + "'" + (status == null ? "" : " AND status = '" + status + "'"));
    }

    private long countOwner(String table, Long owner) {
        return count("SELECT COUNT(*) FROM " + table + " WHERE user_id = " + owner);
    }

    private long count(String sql) {
        return transactionTemplate.execute(tx -> ((Number) entityManager.createNativeQuery(sql)
                .getSingleResult()).longValue());
    }
}
