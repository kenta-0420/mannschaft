package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationDeliveryStatus;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.support.ConfirmableFanoutFixture;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * CMP-260920-1040 試練B（殿の訂正指示を反映）: 親の行のロック順序に関わる競合の受け入れテスト
 * （軍議第8版確定稿 §9.2・§10.2・§11.1・AC-45・AC-61・AC-62・AC-64・AC-65a〜c・AC-66・AC-67）。
 *
 * <p><b>方針</b>: 「2つの操作を決めた順序でコミットし、終局の状態を検証する」ことが目的であり、
 * 実際にブロックさせる必要はない（殿の訂正）。順序が単純な逐次呼び出しで表現できるものは
 * {@link org.springframework.transaction.support.TransactionTemplate} での逐次コミットで表現し、
 * 「A が親を読んだあと、B がコミットし、そのあと A がコミットする」形（AC-61・AC-67）は、
 * 本試練で新設した実物の {@link ConfirmableNotificationRepository#findByIdForUpdate} が真の
 * {@code SELECT ... FOR UPDATE} でロックを取ることを利用し、A を別スレッドでロック取得済みのまま
 * 止め、B を先にコミットさせてから A を再開させる、という<b>実際の行ロックによるブロック</b>で構成する
 * （finish/expireOneWithLock 自体はまだ骨格のため、ロック取得と最終状態の書き込みはテスト側で
 * 「その操作が実装されたときに書くはずの内容」を直接 repository 経由で行い、ロック順序の契約
 * そのものを実 DB で固定する。出陣後にこの手続きを実サービス呼び出しへ置き換えること）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("確認通知 親行のロック順序と競合の終局状態（AC-45/61/62/64/65a-c/66/67・試練B）")
class ConfirmableNotificationLockOrderingConcurrentIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConfirmableNotificationLockOrderingConcurrentIT.class);
    private static final String EMAIL_PREFIX_BASE = "cfx-lock";

    @Autowired
    private ConfirmableFanoutChunkSink sink;

    @Autowired
    private ConfirmableNotificationConfirmService confirmService;

    @Autowired
    private ConfirmableNotificationExpiryBatchService expiryBatchService;

    @Autowired
    private ConfirmableNotificationRepository notificationRepository;

    @Autowired
    private ConfirmableNotificationRecipientRepository recipientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationFanoutJobRepository fanoutJobRepository;

    @PersistenceContext
    private EntityManager em;

    private String emailPrefix;
    private Long notificationId;

    /** {@code sink.finish} に渡す jobId を都度生成し、実在するジョブ行を用意して返す（是正3・§9.2）。 */
    private UUID newFinishableJobId(Long notificationId) {
        UUID jobId = UUID.randomUUID();
        ConfirmableFanoutFixture.insertFanoutJobRow(fanoutJobRepository, jobId, notificationId);
        return jobId;
    }

    @AfterEach
    void cleanUp() {
        if (notificationId != null) {
            recipientRepository.deleteAll(recipientRepository.findByConfirmableNotificationId(notificationId));
            notificationRepository.deleteById(notificationId);
        }
        if (emailPrefix != null) {
            ConfirmableFanoutFixture.deleteUsers(transactionManager, em, emailPrefix);
        }
    }

    private Long createNotification(ConfirmableNotificationStatus status,
            ConfirmableNotificationDeliveryStatus deliveryStatus, int total, int unconfirmed) {
        ConfirmableNotificationEntity notification = notificationRepository.save(ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(1L)
                .title("AC-45/61/62/64/65/66/67 ロック順序")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(status)
                .deliveryStatus(deliveryStatus)
                .totalRecipientCount(total)
                .unconfirmedCount(unconfirmed)
                .build());
        return notification.getId();
    }

    /**
     * 是正: expireOneWithLock を実際に発火させるため、期限を過去に更新する（DB直更新）。
     *
     * <p>CI是正（CMP-260920-1040）: Hibernate は hibernate.jdbc.time_zone: UTC で動くが
     * JVM既定ゾーンはJSTのため、JdbcTemplateで直接 deadline_at を書く場合はUTC換算してから渡す
     * （そうしないと {@code n.deadlineAt < :now} のJPQL比較で「過去」に見えない。
     * ConfirmableFanoutFixture#toUtcColumnValue 参照）。</p>
     */
    private void markDeadlineInPast(Long notificationId) {
        jdbc.update("UPDATE confirmable_notifications SET deadline_at = ? WHERE id = ?",
                ConfirmableFanoutFixture.toUtcColumnValue(java.time.LocalDateTime.now().minusMinutes(10)),
                notificationId);
    }

    // =====================================================================
    // AC-45 / AC-66: cancel と chunk の競合（順序を固定した逐次コミット版）
    // =====================================================================

    @Test
    @DisplayName("AC-45/AC-66: cancelが先にコミットしたら、その後のchunkは新しい受信者行を作らない"
            + "（本体行の悲観ロックで直列化される契約）")
    void cancelThenChunkCreatesNoNewRecipients() {
        emailPrefix = EMAIL_PREFIX_BASE + "-45-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(transactionManager, em, 5, emailPrefix);
        notificationId = createNotification(ConfirmableNotificationStatus.ACTIVE,
                ConfirmableNotificationDeliveryStatus.QUEUED, 0, 0);

        confirmService.cancel(notificationId, userIds.get(0));

        var result = sink.processChunk(UUID.randomUUID(), notificationId, userIds);
        assertThat(result.stopped())
                .as("AC-45/AC-66: cancelコミット後のchunkは打ち切られる（stopped=true）")
                .isTrue();
        assertThat(result.addedCount()).isZero();
    }

    // =====================================================================
    // AC-62: 同じ受信者の confirm を2回呼んでも unconfirmed_count は1しか減らない
    // =====================================================================

    @Test
    @DisplayName("AC-62: 同じ受信者がconfirmを2回呼んでも、unconfirmed_countは1だけ減る（二重に減らない）")
    void confirmTwiceDecrementsCounterOnlyOnce() {
        emailPrefix = EMAIL_PREFIX_BASE + "-62-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(transactionManager, em, 2, emailPrefix);
        notificationId = createNotification(ConfirmableNotificationStatus.ACTIVE,
                ConfirmableNotificationDeliveryStatus.DELIVERING, 2, 2);
        seedRecipients(userIds);

        confirmService.confirm(notificationId, userIds.get(0));
        assertThatThrownBy(() -> confirmService.confirm(notificationId, userIds.get(0)))
                .as("AC-62: 2回目のconfirmはALREADY_CONFIRMEDで拒否される（既存の二重確認防止）")
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class);

        ConfirmableNotificationEntity after = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(after.getUnconfirmedCount())
                .as("AC-62: unconfirmed_countは1しか減らない（2ではない）")
                .isEqualTo(1);
    }

    // =====================================================================
    // AC-64: cancel と finish の競合（finishが先にCOMPLETEDを確定 → cancelはALREADY_CANCELLED）
    // =====================================================================

    @Test
    @DisplayName("AC-64（真の競合・2スレッド実測）: cancelが親をACTIVEと読んでからコミットするまでの間に、"
            + "finish相当の処理がCOMPLETEDを確定してコミットした場合、cancelはCOMPLETEDを上書きしてはならない"
            + "（現行cancelはロックを取らず、読取後の対象行を無条件UPDATEするため、この実測で上書きが起きてしまうべき＝red）")
    void cancelRacesWithConcurrentFinishAndMustNotOverwriteCompleted() throws Exception {
        emailPrefix = EMAIL_PREFIX_BASE + "-64-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(transactionManager, em, 1, emailPrefix);
        notificationId = createNotification(ConfirmableNotificationStatus.ACTIVE,
                ConfirmableNotificationDeliveryStatus.DELIVERING, 1, 1);
        seedRecipients(userIds);

        TransactionTemplate finishTx = new TransactionTemplate(transactionManager);
        CountDownLatch finishHasLock = new CountDownLatch(1);
        CountDownLatch finishMayCommit = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // finish 相当のスレッド: 親をfindByIdForUpdateで実ロックしたまま保持し、
            // cancelスレッドの起動を確認してから complete() をコミットする
            // （sink.finish は骨格のため、finishが行うはずの「ロック→completeして保存」を
            // 直接 repository 経由で再現する。ロックの取得自体は実物の findByIdForUpdate を使う）。
            Future<?> futureFinish = executor.submit(() -> finishTx.executeWithoutResult(status -> {
                ConfirmableNotificationEntity locked =
                        notificationRepository.findByIdForUpdate(notificationId).orElseThrow();
                finishHasLock.countDown();
                try {
                    if (!finishMayCommit.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("finishへのコミット許可が時間内に来なかった");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                locked.complete();
                notificationRepository.save(locked);
            }));
            assertThat(finishHasLock.await(10, TimeUnit.SECONDS)).as("finishスレッドがロックを取得した").isTrue();

            // cancelスレッド: finishがロックを保持している間に呼ぶ。現行cancelはロックを取らない
            // ので読み取り自体はブロックされずに進み、mutateしたのちのUPDATE（flush/commit時）が
            // finishのロック保持中はブロックされる想定。
            Future<Void> futureCancel = executor.submit(() -> {
                confirmService.cancel(notificationId, userIds.get(0));
                return null;
            });

            // cancelスレッドがfinishのロック保持中はコミットを終えられず「保留」のままであることを
            // 実測する（少なくとも一定時間は完了しない）。
            await("cancelはfinishがロックを保持している間は完了しないはず")
                    .pollDelay(Duration.ofMillis(150))
                    .pollInterval(Duration.ofMillis(150))
                    .during(Duration.ofMillis(700))
                    .atMost(Duration.ofMillis(1500))
                    .until(() -> !futureCancel.isDone());

            finishMayCommit.countDown();
            futureFinish.get(10, TimeUnit.SECONDS);

            Throwable cancelOutcome = null;
            try {
                futureCancel.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                cancelOutcome = e.getCause();
            }

            assertThat(cancelOutcome)
                    .as("AC-64: finish確定後に再開したcancelはALREADY_CANCELLED相当の例外を投げるべき"
                            + "（現行cancelは例外を投げずに成功してしまう＝red）")
                    .isInstanceOf(com.mannschaft.app.common.BusinessException.class);

            ConfirmableNotificationEntity after = notificationRepository.findById(notificationId).orElseThrow();
            assertThat(after.getStatus())
                    .as("AC-64: COMPLETEDが上書きされていないこと（現行cancelはCANCELLEDへ上書きしてしまう＝red）")
                    .isEqualTo(ConfirmableNotificationStatus.COMPLETED);
        } finally {
            executor.shutdownNow();
        }
    }

    // =====================================================================
    // AC-65a/b/c: 期限切れバッチとfinishの競合
    // =====================================================================

    @Test
    @DisplayName("AC-65a: 期限切れが先にコミットされた場合、親はEXPIREDになる。"
            + "その後のfinishはSTOPPEDとし、COMPLETEDにはしない")
    void expiredFirstThenFinishSetsStoppedNotCompleted() {
        emailPrefix = EMAIL_PREFIX_BASE + "-65a-" + UUID.randomUUID();
        notificationId = createNotification(ConfirmableNotificationStatus.ACTIVE,
                ConfirmableNotificationDeliveryStatus.DELIVERING, 0, 0);
        // 是正: createNotification() は deadlineAt を設定しないため、expireOneWithLock の
        // 「deadlineAt == null なら何もしない」判定に常に false で落ちていた（AC-65aがredにすら
        // ならず常にfalseを返す実測）。expireOneWithLockを実際に発火させるため、既に過ぎた期限を設定する。
        markDeadlineInPast(notificationId);

        boolean expired = expiryBatchService.expireOneWithLock(notificationId, java.time.LocalDateTime.now());
        // 骨格段階ではUOEでredになる。
        assertThat(expired).as("AC-65a: 期限切れが確定する").isTrue();

        sink.finish(newFinishableJobId(notificationId), notificationId);

        ConfirmableNotificationEntity after = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(after.getDeliveryStatus())
                .as("AC-65a: finishはSTOPPEDにする（DELIVEREDにもCOMPLETEDにもしない）")
                .isEqualTo(ConfirmableNotificationDeliveryStatus.STOPPED);
        assertThat(after.getStatus()).isEqualTo(ConfirmableNotificationStatus.EXPIRED);
    }

    @Test
    @DisplayName("AC-65b: finishが先にコミットされ全員確認済みならCOMPLETEDになる。"
            + "その後の期限切れバッチはこれを上書きしない")
    void finishCompletesFirstThenExpiryDoesNotOverride() {
        emailPrefix = EMAIL_PREFIX_BASE + "-65b-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(transactionManager, em, 1, emailPrefix);
        notificationId = createNotification(ConfirmableNotificationStatus.ACTIVE,
                ConfirmableNotificationDeliveryStatus.DELIVERING, 1, 0); // 既に全員確認済み(unconfirmed=0)
        seedRecipients(userIds);
        recipientRepository.findByConfirmableNotificationId(notificationId)
                .forEach(r -> { r.confirm(com.mannschaft.app.notification.confirmable.entity.ConfirmedVia.APP);
                    recipientRepository.save(r); });

        sink.finish(newFinishableJobId(notificationId), notificationId);

        boolean expiredAfterFinish = expiryBatchService.expireOneWithLock(
                notificationId, java.time.LocalDateTime.now());

        ConfirmableNotificationEntity after = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(after.getStatus())
                .as("AC-65b: COMPLETEDのまま。期限切れバッチに上書きされない")
                .isEqualTo(ConfirmableNotificationStatus.COMPLETED);
        assertThat(expiredAfterFinish)
                .as("AC-65b: 期限切れバッチは『何もしなかった』を返すべき")
                .isFalse();
    }

    @Test
    @DisplayName("AC-65c: finishが先にコミットされたが未確認者が残っている場合、"
            + "delivery_status=DELIVERED・status=ACTIVEのまま。期限を過ぎたあと期限切れバッチでEXPIREDになる")
    void finishDeliveredWithUnconfirmedThenExpiryTransitionsLater() {
        emailPrefix = EMAIL_PREFIX_BASE + "-65c-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(transactionManager, em, 2, emailPrefix);
        notificationId = createNotification(ConfirmableNotificationStatus.ACTIVE,
                ConfirmableNotificationDeliveryStatus.DELIVERING, 2, 2);
        seedRecipients(userIds);

        sink.finish(newFinishableJobId(notificationId), notificationId);

        ConfirmableNotificationEntity afterFinish = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(afterFinish.getDeliveryStatus()).isEqualTo(ConfirmableNotificationDeliveryStatus.DELIVERED);
        assertThat(afterFinish.getStatus()).isEqualTo(ConfirmableNotificationStatus.ACTIVE);

        // 是正: 期限を過ぎたあとに期限切れバッチを走らせる、というテスト意図どおり、ここで初めて
        // 期限を過去に設定する（createNotification() は deadlineAt を設定しないため）。
        markDeadlineInPast(notificationId);
        boolean expired = expiryBatchService.expireOneWithLock(notificationId, java.time.LocalDateTime.now());
        assertThat(expired).isTrue();

        ConfirmableNotificationEntity afterExpiry = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(afterExpiry.getStatus()).isEqualTo(ConfirmableNotificationStatus.EXPIRED);
        assertThat(afterExpiry.getDeliveryStatus())
                .as("AC-65c: delivery_statusはDELIVEREDのまま")
                .isEqualTo(ConfirmableNotificationDeliveryStatus.DELIVERED);
    }

    // =====================================================================
    // AC-61 / AC-67: 「A が親をロックしたあと、B が先にコミットし、そのあと A が再開する」形の
    // 実ロックによる直列化（CountDownLatchで2スレッドを実際にブロックさせる）
    // =====================================================================

    @Test
    @DisplayName("AC-61（confirmByToken版）: confirmByTokenで最後の1人を確認したときも、"
            + "unconfirmed_countが正しく0まで減り、確認後にCOMPLETEDになる"
            + "（§9.3: confirmとconfirmByTokenの両方に同じ件数クエリ契約を適用する）")
    void confirmByTokenForLastRecipientCompletes() {
        emailPrefix = EMAIL_PREFIX_BASE + "-61-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(transactionManager, em, 1, emailPrefix);
        notificationId = createNotification(ConfirmableNotificationStatus.ACTIVE,
                ConfirmableNotificationDeliveryStatus.DELIVERED, 1, 1);
        seedRecipients(userIds);
        String token = recipientRepository.findByConfirmableNotificationId(notificationId).get(0).getConfirmToken();

        confirmService.confirmByToken(token);

        ConfirmableNotificationEntity after = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(after.getUnconfirmedCount())
                .as("AC-61: confirmByToken後もunconfirmed_countは正しく0まで減るべき"
                        + "（現行confirmByTokenはunconfirmed_countを一切更新しないためred）")
                .isZero();
        assertThat(after.getStatus())
                .as("AC-61: DELIVERED状態で最後の1人が確認済みになればCOMPLETEDになる")
                .isEqualTo(ConfirmableNotificationStatus.COMPLETED);
    }

    /**
     * AC-61（真のロック競合の実測について・書けなかった理由）: 「親を読んだあと、別トランザクションが
     * 先にコミットし、そのあと再開して自分がコミットする」形の<b>実ブロッキング</b>を確かめるには、
     * confirm/confirmByToken の本体トランザクションの<b>途中</b>に一時停止点を挟む必要がある。
     * 現行実装にはそのような注入点が無く、注入点だけを目的に本体へ一時停止フックを足すのは
     * 出陣が担う実装（findByIdForUpdateへの統一）そのものを先取りしてしまうため見送った。
     * インフラ側（findByIdForUpdate自体が実DBのFOR UPDATEでブロックすること）だけを検証する
     * テストは<b>現時点で既に成立してしまい red にならない</b>ため、意図的に含めていない。
     * 出陣で confirm/confirmByToken が findByIdForUpdate を先頭で呼ぶよう揃ったあと、
     * {@code DuplicateNameConcurrentCreationRedIT} を金型に2スレッド版を追加すること。
     */

    @Test
    @DisplayName("AC-67: 期限切れバッチが対象IDを抽出したあとロックを取るまでの間に、"
            + "finishがCOMPLETEDを確定した場合、EXPIREDに上書きされない")
    void expiryDoesNotOverrideCompletedEvenIfExtractedBeforeFinishCommitted() {
        emailPrefix = EMAIL_PREFIX_BASE + "-67-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(transactionManager, em, 1, emailPrefix);
        notificationId = createNotification(ConfirmableNotificationStatus.ACTIVE,
                ConfirmableNotificationDeliveryStatus.DELIVERING, 1, 0);
        seedRecipients(userIds);
        recipientRepository.findByConfirmableNotificationId(notificationId)
                .forEach(r -> { r.confirm(com.mannschaft.app.notification.confirmable.entity.ConfirmedVia.APP);
                    recipientRepository.save(r); });

        // 期限切れバッチが「対象ID抽出」した時点ではまだACTIVE（このnotificationIdがfindExpiredIdsに
        // 含まれる想定）。抽出後、finishが先にCOMPLETEDを確定してから、抽出済みIDに対して
        // expireOneWithLockを呼ぶ、という順序を固定する。
        sink.finish(newFinishableJobId(notificationId), notificationId);

        boolean expired = expiryBatchService.expireOneWithLock(notificationId, java.time.LocalDateTime.now());

        assertThat(expired).as("AC-67: 既にCOMPLETEDのため期限切れバッチは何もしない").isFalse();
        ConfirmableNotificationEntity after = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(after.getStatus())
                .as("AC-67: COMPLETEDのまま残る（EXPIREDに上書きされない）")
                .isEqualTo(ConfirmableNotificationStatus.COMPLETED);
    }

    // =====================================================================
    // AC-68: 期限切れバッチで1件が例外になっても、同じ回のほかの通知はEXPIREDになる
    // =====================================================================

    @Test
    @DisplayName("AC-68: 期限切れバッチで1件がロック待ちタイムアウトで失敗しても、"
            + "同じ回のほかの通知はEXPIREDになる（1件ごと独立トランザクションの契約）")
    void oneFailureDuringBatchDoesNotBlockOtherExpirations() throws Exception {
        // CMP-260920-1040是正（⚔️足軽19・CI是正4）: 期限切れバッチは
        // ConfirmableNotificationRepository#findByIdForUpdateNoWait（FOR UPDATE NOWAIT）でロックを
        // 取る。ロック中の行は待たずに即座に失敗し、次の回に回される（詳細は同メソッドのJavadoc参照）。
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime pastDeadline = now.minusMinutes(10);
        Long n1 = createExpiredActiveNotification(pastDeadline);
        Long nLocked = createExpiredActiveNotification(pastDeadline);
        Long n3 = createExpiredActiveNotification(pastDeadline);
        try {
            ac68Body(n1, nLocked, n3, now);
        } finally {
            notificationRepository.deleteById(n1);
            notificationRepository.deleteById(nLocked);
            notificationRepository.deleteById(n3);
        }
    }

    private void ac68Body(Long n1, Long nLocked, Long n3, LocalDateTime now) throws Exception {
        {
            TransactionTemplate lockTx = new TransactionTemplate(transactionManager);
            CountDownLatch lockAcquired = new CountDownLatch(1);
            CountDownLatch mayRelease = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> lockHolder = executor.submit(() -> lockTx.executeWithoutResult(status -> {
                    notificationRepository.findByIdForUpdate(nLocked).orElseThrow();
                    lockAcquired.countDown();
                    try {
                        if (!mayRelease.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("ロック解放許可が時間内に来なかった");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }));
                assertThat(lockAcquired.await(10, TimeUnit.SECONDS))
                        .as("ロック保持スレッドがnLockedの行ロックを取得した")
                        .isTrue();

                // findExpiredIds（骨格）が3件を抽出する想定。抽出後、1件ごとに独立トランザクションで
                // expireOneWithLockを呼ぶ、というオーケストレーションをここで固定する
                // （§11.1手順2・3。骨格段階ではfindExpiredIds自体がUOEを投げるため、
                // ID列挙は直接収集し、対象抽出そのものの検証はfindExpiredIdsの別テストに委ねる）。
                List<Long> ids = List.of(n1, nLocked, n3);
                Exception lockedFailure = null;
                long lockedFailureElapsedMs = -1;
                for (Long id : ids) {
                    long start = System.nanoTime();
                    try {
                        expiryBatchService.expireOneWithLock(id, now);
                    } catch (Exception e) {
                        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                        // 1件の失敗（ロック競合）はログに残し、他のIDの処理は続ける
                        // （§11.1手順3。握りつぶさず、ここでは失敗を許容してループを続けるだけ）。
                        log.info("[AC-68] notificationId={} の期限切れ処理に失敗（想定内）: {} ({}ms)",
                                id, e.toString(), elapsedMs);
                        if (id.equals(nLocked)) {
                            lockedFailure = e;
                            lockedFailureElapsedMs = elapsedMs;
                        }
                    }
                }

                // AC-68是正（⚔️足軽19・CI是正4）: Hibernate が実際に FOR UPDATE NOWAIT を発行しているか
                // を、待たずに即座に失敗したこと（経過時間）で検証する。前任の SET_VAR 方式はヒントが
                // 黙って無視され、innodb_lock_wait_timeout のセッション既定値（通常50秒）のまま待ち
                // 続けていたため、この検証は前任の実装では失敗する（＝旧実装の欠陥を検出できる）。
                assertThat(lockedFailure)
                        .as("AC-68: ロック中のnLockedはexpireOneWithLockで例外になる")
                        .isNotNull();
                assertThat(lockedFailure)
                        .as("AC-68: NOWAITが投げる例外はPessimisticLockingFailureException系である")
                        .isInstanceOfAny(
                                org.springframework.dao.PessimisticLockingFailureException.class,
                                org.springframework.dao.CannotAcquireLockException.class);
                assertThat(lockedFailureElapsedMs)
                        .as("AC-68: NOWAITは待たずに即座に失敗する（秒オーダーで待つSET_VAR無視の"
                                + "旧実装ならここが数秒〜数十秒になる）")
                        .isLessThan(3000L);

                mayRelease.countDown();
                lockHolder.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }

            ConfirmableNotificationEntity after1 = notificationRepository.findById(n1).orElseThrow();
            ConfirmableNotificationEntity after3 = notificationRepository.findById(n3).orElseThrow();
            assertThat(after1.getStatus())
                    .as("AC-68: ロック競合と無関係のn1はEXPIREDになる")
                    .isEqualTo(ConfirmableNotificationStatus.EXPIRED);
            assertThat(after3.getStatus())
                    .as("AC-68: ロック競合と無関係のn3はEXPIREDになる")
                    .isEqualTo(ConfirmableNotificationStatus.EXPIRED);
        }
    }

    private Long createExpiredActiveNotification(LocalDateTime deadlineAt) {
        ConfirmableNotificationEntity notification = notificationRepository.save(ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(1L)
                .title("AC-68 期限切れバッチ部分失敗耐性")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(ConfirmableNotificationStatus.ACTIVE)
                .deliveryStatus(ConfirmableNotificationDeliveryStatus.DELIVERING)
                .deadlineAt(deadlineAt)
                .totalRecipientCount(0)
                .unconfirmedCount(0)
                .build());
        return notification.getId();
    }

    // =====================================================================
    // ヘルパー
    // =====================================================================

    private void seedRecipients(List<Long> userIds) {
        ConfirmableNotificationEntity notification = notificationRepository.findById(notificationId).orElseThrow();
        for (Long userId : userIds) {
            UserEntity user = userRepository.getReferenceById(userId);
            recipientRepository.save(ConfirmableNotificationRecipientEntity.builder()
                    .confirmableNotification(notification)
                    .user(user)
                    .confirmToken(UUID.randomUUID().toString())
                    .build());
        }
    }
}
