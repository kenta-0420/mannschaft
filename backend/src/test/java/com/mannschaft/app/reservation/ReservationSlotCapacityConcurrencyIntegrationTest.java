package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.service.ReservationSlotService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 予約枠の定員(capacity)による満席化・オーバーブッキング防止の<b>並行制御</b>結合テスト（実 MySQL）。
 *
 * <h2>このテストが守る不変条件（実機E2Eで発見したオーバーブッキングの根治）</h2>
 * <p>従来 {@code incrementAndCheckFull} は名前に反して {@code markFull()} を呼ばず、枠に定員も無かったため、
 * 同一予約枠へ<b>無制限に</b>予約できた（美容院 1:1 指名で同一枠に複数予約が入る事故）。本テストは:</p>
 * <ol>
 *   <li>capacity=1: 1 件で FULL 化し 2 件目は {@link ReservationErrorCode#SLOT_FULL} で拒否</li>
 *   <li>capacity=3: 3 件まで確保、4 件目拒否、満席後キャンセルで再び確保可能（AVAILABLE 復帰）</li>
 *   <li><b>並行</b>: 多数スレッドが同一枠へ同時予約しても、確保成功は定員数ちょうどで、
 *       {@code booked_count} が capacity を超えない（条件付きアトミック UPDATE の番人）</li>
 * </ol>
 *
 * <p>reservation_slots のクロスドメインFK（team_id→teams / staff_user_id→users）は
 * V95.001 / V103.001 で撤廃済みのため、親行を用意せず slot 行を直接シードできる。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる。実走裏取り推奨。</p>
 */
@DisplayName("予約枠 定員(capacity) 満席化・オーバーブッキング防止 並行制御結合テスト（実MySQL）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationSlotCapacityConcurrencyIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationSlotService service;

    @Autowired
    private ReservationSlotRepository slotRepository;

    private static final Long TEAM_ID = 990001L;
    private static final LocalDate SLOT_DATE = LocalDate.now().plusMonths(1);

    /** 定員 capacity の空き枠を 1 件コミットし、その ID を返す。 */
    private Long seedSlot(int capacity) {
        ReservationSlotEntity entity = ReservationSlotEntity.builder()
                .teamId(TEAM_ID)
                .title("並行テスト枠")
                .slotDate(SLOT_DATE)
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(11, 0))
                .capacity(capacity)
                .build();
        return slotRepository.save(entity).getId();
    }

    private ReservationSlotEntity reload(Long slotId) {
        return slotRepository.findById(slotId).orElseThrow();
    }

    @Test
    @DisplayName("capacity=1: 1 件予約で FULL 化し、2 件目は SLOT_FULL で拒否される")
    void 定員1で満席化し二件目は拒否() {
        Long slotId = seedSlot(1);
        ReservationSlotEntity ref = reload(slotId);

        // 1 件目: 確保成功
        service.incrementAndCheckFull(ref);

        ReservationSlotEntity afterFirst = reload(slotId);
        assertThat(afterFirst.getBookedCount()).isEqualTo(1);
        assertThat(afterFirst.getSlotStatus()).isEqualTo(SlotStatus.FULL);

        // 2 件目: 満席で拒否（オーバーブッキング防止）
        assertThatThrownBy(() -> service.incrementAndCheckFull(ref))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.SLOT_FULL);

        // booked_count は 1 のまま（超過しない）
        assertThat(reload(slotId).getBookedCount()).isEqualTo(1);

        slotRepository.deleteById(slotId);
    }

    @Test
    @DisplayName("capacity=3: 3 件まで確保、4 件目拒否、キャンセルで再確保可能（AVAILABLE 復帰）")
    void 定員3の確保と満席とキャンセル復帰() {
        Long slotId = seedSlot(3);
        ReservationSlotEntity ref = reload(slotId);

        service.incrementAndCheckFull(ref); // 1
        service.incrementAndCheckFull(ref); // 2
        service.incrementAndCheckFull(ref); // 3 → 満席

        ReservationSlotEntity full = reload(slotId);
        assertThat(full.getBookedCount()).isEqualTo(3);
        assertThat(full.getSlotStatus()).isEqualTo(SlotStatus.FULL);

        // 4 件目は拒否
        assertThatThrownBy(() -> service.incrementAndCheckFull(ref))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.SLOT_FULL);

        // キャンセル → AVAILABLE 復帰
        service.decrementAndReopen(ref);
        ReservationSlotEntity afterCancel = reload(slotId);
        assertThat(afterCancel.getBookedCount()).isEqualTo(2);
        assertThat(afterCancel.getSlotStatus()).isEqualTo(SlotStatus.AVAILABLE);

        // 空きが戻ったので再び確保できる
        service.incrementAndCheckFull(ref);
        assertThat(reload(slotId).getSlotStatus()).isEqualTo(SlotStatus.FULL);

        slotRepository.deleteById(slotId);
    }

    @Test
    @DisplayName("並行: 同一枠へ多数同時予約しても確保成功は定員ちょうど（オーバーブッキングしない）")
    void 並行予約で定員を超えない() throws Exception {
        int capacity = 5;
        int threads = 40;
        Long slotId = seedSlot(capacity);
        ReservationSlotEntity ref = reload(slotId);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    service.incrementAndCheckFull(ref);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ReservationErrorCode.SLOT_FULL) {
                        rejected.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // その他の例外はカウントせず、下の検証（成功数=定員）で破綻として検出させる
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown(); // 一斉スタート
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("全スレッド完了").isTrue();

        // 確保成功はちょうど定員数、残りは満席拒否。DB の booked_count も定員を超えない。
        assertThat(success.get()).as("確保成功は定員ちょうど").isEqualTo(capacity);
        assertThat(rejected.get()).as("残りは満席で拒否").isEqualTo(threads - capacity);

        ReservationSlotEntity after = reload(slotId);
        assertThat(after.getBookedCount()).as("booked_count が定員を超えない").isEqualTo(capacity);
        assertThat(after.getSlotStatus()).as("満席になっている").isEqualTo(SlotStatus.FULL);

        slotRepository.deleteById(slotId);
    }
}
