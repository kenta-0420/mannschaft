package com.mannschaft.app.reservation;

import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.entity.ReservationWaitlistEntryEntity;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationWaitlistEntryRepository;
import com.mannschaft.app.reservation.service.ReservationSlotService;
import com.mannschaft.app.reservation.service.ReservationWaitlistService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * キャンセル待ち 空き復帰通知の<b>並行制御</b>結合テスト（実 MySQL・F03.4.5 §6.1 の lost wakeup / 二重発火根治）。
 *
 * <p>capacity≥2 の枠は {@code normalizeCapacity} で実在到達可能ゆえ、以下の破綻を DB 事実で根治したことを番人化する:</p>
 * <ol>
 *   <li><b>(B) 二重発火の源封じ</b>: capacity=3・満席を 3 tx が同時キャンセルしても、FULL→AVAILABLE 遷移を
 *       起こす（＝{@code reopenSlotIfFull} が 1 を返す）tx は<b>ちょうど 1 つ</b>。
 *       {@code WHERE slot_status='FULL'} の行ロック直列化ガードが源で二重発火を消す。</li>
 *   <li><b>(A) lost wakeup の解消</b>: in-memory スナップショットが AVAILABLE でも、DB が FULL→AVAILABLE を
 *       起こしていれば通知が届く（発火判定を DB 事実に切替えた効果。旧 {@code wasFull} 実装では通知漏れ）。</li>
 *   <li><b>抑制の並行安全</b>: 空き通知が万一並行で複数回起動しても、{@code FOR UPDATE}＋{@code notified_at}
 *       抑制の原子化により同一 WAITING への push は 1 回だけ。</li>
 * </ol>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} によりスキップされる。実走裏取り推奨。</p>
 */
@DisplayName("キャンセル待ち 空き復帰通知 並行制御結合テスト（実MySQL・F03.4.5 §6.1）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationWaitlistConcurrencyIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationSlotService slotService;
    @Autowired
    private ReservationWaitlistService waitlistService;
    @Autowired
    private ReservationSlotRepository slotRepository;
    @Autowired
    private ReservationWaitlistEntryRepository waitlistRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private PlatformTransactionManager txManager;

    private static final LocalDate FUTURE = LocalDate.now().plusMonths(1);

    private Long seedSlot(Long teamId, LocalTime start, SlotStatus status, int capacity, int booked) {
        return slotRepository.save(ReservationSlotEntity.builder()
                .teamId(teamId).title("並行枠").slotDate(FUTURE).startTime(start).endTime(start.plusMinutes(30))
                .capacity(capacity).bookedCount(booked).slotStatus(status).build()).getId();
    }

    private void seedWaiter(Long teamId, Long slotId, Long userId) {
        waitlistRepository.save(ReservationWaitlistEntryEntity.builder()
                .teamId(teamId).slotId(slotId).userId(userId).status(WaitlistStatus.WAITING).build());
    }

    private long waitlistNotificationCount(Long slotId) {
        return notificationRepository.countBySourceTypeAndSourceId("RESERVATION", slotId);
    }

    // ────────────────────────────────────────────────────────────
    // (B) 二重発火の源封じ — reopen 遷移を起こす tx はちょうど 1 つ
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(B) capacity=3満席を3tx同時キャンセル → FULL→AVAILABLE遷移を起こすのは1txだけ")
    void 並行キャンセルでreopen遷移は1txのみ() throws Exception {
        Long teamId = 971001L;
        Long slotId = seedSlot(teamId, LocalTime.of(10, 0), SlotStatus.FULL, 3, 3);

        int threads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reopenedSum = new AtomicInteger();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    // 各 tx: デクリメント → reopen 専用 UPDATE（affected-rows がゲート）
                    tx.executeWithoutResult(s -> {
                        slotRepository.decrementBookedCount(slotId);
                        reopenedSum.addAndGet(slotRepository.reopenSlotIfFull(slotId));
                    });
                } catch (Exception ignored) {
                    // 例外は下の検証（合計=1）で破綻として検出させる
                }
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("全スレッド完了").isTrue();

        assertThat(reopenedSum.get())
                .as("FULL→AVAILABLE 遷移を起こした tx はちょうど 1 つ（二重発火の源封じ）").isEqualTo(1);
        ReservationSlotEntity after = slotRepository.findById(slotId).orElseThrow();
        assertThat(after.getSlotStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(after.getBookedCount()).isZero();

        slotRepository.deleteById(slotId);
    }

    // ────────────────────────────────────────────────────────────
    // (A) lost wakeup の解消 — 古いスナップショットでも DB 遷移を検知して通知
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(A) in-memoryがAVAILABLEでもDBがFULL→AVAILABLE遷移すれば通知が必ず届く（lost wakeup根治）")
    void 古いスナップショットでもDB遷移を検知して通知() {
        Long teamId = 971002L;
        Long waiter = 971102L;
        // DB は満席（booked=2/capacity=2/FULL）。会員が待機中。
        Long slotId = seedSlot(teamId, LocalTime.of(11, 0), SlotStatus.FULL, 2, 2);
        seedWaiter(teamId, slotId, waiter);

        // 呼出元が「空きに見える」古いスナップショット（status=AVAILABLE）で decrementAndReopen を呼ぶ。
        // 旧 wasFull 実装なら wasFull=false でイベント未発行 → 通知が永久に来ない（lost wakeup）。
        ReservationSlotEntity stale = ReservationSlotEntity.builder()
                .id(slotId).teamId(teamId).slotDate(FUTURE)
                .startTime(LocalTime.of(11, 0)).endTime(LocalTime.of(11, 30))
                .capacity(2).slotStatus(SlotStatus.AVAILABLE).bookedCount(1).build();

        slotService.decrementAndReopen(stale);

        // AFTER_COMMIT + @Async 通知が届くことを待つ（旧実装なら来ないのでタイムアウト = red）。
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(waitlistNotificationCount(slotId)).isEqualTo(1L));

        ReservationSlotEntity after = slotRepository.findById(slotId).orElseThrow();
        assertThat(after.getSlotStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(after.getBookedCount()).isEqualTo(1);
    }

    // ────────────────────────────────────────────────────────────
    // 抑制の並行安全 — FOR UPDATE + notified_at で二重通知しない
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("抑制並行安全: notifySlotReopenedを2tx同時実行しても同一WAITINGへの通知は1回だけ")
    void 並行通知でも二重にpushしない() throws Exception {
        Long teamId = 971003L;
        Long waiter = 971103L;
        // 空き復帰済みの枠（AVAILABLE）＋待機者。通知だけを並行起動する。
        Long slotId = seedSlot(teamId, LocalTime.of(12, 0), SlotStatus.AVAILABLE, 2, 0);
        seedWaiter(teamId, slotId, waiter);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    waitlistService.notifySlotReopened(teamId, slotId);
                } catch (Exception ignored) {
                    // 例外は下の検証（通知=1）で破綻として検出させる
                }
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("全スレッド完了").isTrue();

        // notifySlotReopened は同期メソッドのため join 後に確定済み。FOR UPDATE + notified_at 抑制で 1 回だけ。
        assertThat(waitlistNotificationCount(slotId))
                .as("並行起動でも同一 WAITING への通知は 1 回だけ").isEqualTo(1L);

        slotRepository.deleteById(slotId);
    }
}
