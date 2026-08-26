package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.dto.CreateReservationGroupRequest;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.entity.ReservationTeamSettingEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationTeamSettingRepository;
import com.mannschaft.app.reservation.service.ReservationGroupService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 予約グループの<b>並行確保</b>結合テスト（実 MySQL・F03.4.3 §8 <b>G-13 デッドロック耐性</b>）。
 *
 * <h2>このテストが守る不変条件（§5.2 の設計根拠の実 DB 検証）</h2>
 * <ol>
 *   <li>capacity=1 の同一 2 枠へ、slotId 昇順/逆順の slotIds で 2 スレッドが同時 POST しても、
 *       <b>一方が成功・他方が 409（RESERVATION_039）</b>で完了する
 *       （確保 UPDATE 先行 → INSERT の順序で S→X ロック昇格デッドロックが構造的に消えている）</li>
 *   <li>{@code DeadlockLoserDataAccessException} 等が<b>未変換のまま呼び出し側へ漏れない</b>
 *       （漏れた場合 HTTP 500 相当 — BusinessException 以外の例外件数 = 0 を観測）</li>
 *   <li>終了後の両 slot の {@code booked_count} 最終値が<b>ちょうど 1</b>（二重確保も取りこぼしもない）</li>
 *   <li>敗者側の {@code reservations} 行が残っていない（全ロールバック・部分成功禁止）</li>
 * </ol>
 *
 * <p>純 Mockito UT では InnoDB のロック挙動を踏まないため、G-13 は本 IT が唯一の担保
 * （設計書 §8 で実 DB 並行 IT へ格上げ済み）。Docker 未起動環境ではスキップされる。</p>
 */
@DisplayName("予約グループ 並行確保・デッドロック耐性 結合テスト（実MySQL・G-13）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationGroupConcurrencyIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationGroupService groupService;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ReservationSlotRepository slotRepository;
    @Autowired
    private ReservationLineRepository lineRepository;
    @Autowired
    private ReservationTeamSettingRepository teamSettingRepository;

    private static final Long TEAM_ID = 997001L;
    private static final Long USER_A = 997101L;
    private static final Long USER_B = 997102L;
    private static final LocalDate SLOT_DATE = LocalDate.now().plusMonths(1);

    @Test
    @DisplayName("G-13: 同一2枠へ slotId 昇順/逆順の同時 POST — 一方 201・他方 409(039)・booked_count=1・敗者行ゼロ")
    void 並行グループ予約で部分成功もデッドロック500も発生しない() throws Exception {
        // given: 公開予約 ON のチーム・active ライン・capacity=1 の連続 2 枠（AUTO 承認）
        teamSettingRepository.save(ReservationTeamSettingEntity.builder()
                .teamId(TEAM_ID)
                .allowPublicReservation(true)
                .build());
        ReservationLineEntity line = lineRepository.save(ReservationLineEntity.builder()
                .teamId(TEAM_ID)
                .name("並行テストライン")
                .isActive(true)
                .build());
        Long slot1 = seedSlot(LocalTime.of(10, 0));
        Long slot2 = seedSlot(LocalTime.of(10, 30));

        // when: 2 スレッドが CountDownLatch で同時開始（A は昇順・B は逆順の slotIds）
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict039 = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        Runnable taskA = () -> runCreate(USER_A, line.getId(), List.of(slot1, slot2),
                ready, start, success, conflict039, unexpected);
        Runnable taskB = () -> runCreate(USER_B, line.getId(), List.of(slot2, slot1),
                ready, start, success, conflict039, unexpected);
        pool.submit(taskA);
        pool.submit(taskB);

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).as("全スレッド完了").isTrue();

        // then ①: 一方が成功・他方が 409(039)。②: 未変換例外（500 相当）ゼロ。
        assertThat(unexpected)
                .as("DeadlockLoserDataAccessException 等が未変換のまま漏れないこと: " + unexpected)
                .isEmpty();
        assertThat(success.get()).as("成功はちょうど 1 件").isEqualTo(1);
        assertThat(conflict039.get()).as("敗者は 409=RESERVATION_039").isEqualTo(1);

        // then ③: booked_count 最終値はちょうど 1（二重確保も取りこぼしもない）
        assertThat(slotRepository.findById(slot1).orElseThrow().getBookedCount()).isEqualTo(1);
        assertThat(slotRepository.findById(slot2).orElseThrow().getBookedCount()).isEqualTo(1);
        assertThat(slotRepository.findById(slot1).orElseThrow().getSlotStatus()).isEqualTo(SlotStatus.FULL);
        assertThat(slotRepository.findById(slot2).orElseThrow().getSlotStatus()).isEqualTo(SlotStatus.FULL);

        // then ④: 敗者側の予約行が残っていない（勝者の 2 行のみ・同一 groupId）
        List<ReservationEntity> rows = reservationRepository.findAll().stream()
                .filter(r -> TEAM_ID.equals(r.getTeamId()))
                .toList();
        assertThat(rows).as("勝者の 2 行のみが存在する（全ロールバックの実体）").hasSize(2);
        assertThat(rows).extracting(ReservationEntity::getGroupId)
                .doesNotContainNull()
                .containsOnly(rows.get(0).getGroupId());
        assertThat(rows.stream().filter(ReservationEntity::getIsGroupPrimary))
                .as("代表行はちょうど 1 行（不変条件・§3.2）").hasSize(1);
    }

    private void runCreate(Long userId, Long lineId, List<Long> slotIds,
                           CountDownLatch ready, CountDownLatch start,
                           AtomicInteger success, AtomicInteger conflict039,
                           ConcurrentLinkedQueue<Throwable> unexpected) {
        ready.countDown();
        try {
            start.await();
            groupService.createGroup(TEAM_ID, userId,
                    new CreateReservationGroupRequest(null, lineId, slotIds, null));
            success.incrementAndGet();
        } catch (BusinessException e) {
            if (e.getErrorCode() == ReservationErrorCode.GROUP_SLOT_UNAVAILABLE
                    || e.getErrorCode() == ReservationErrorCode.DUPLICATE_RESERVATION) {
                conflict039.incrementAndGet();
            } else {
                unexpected.add(e);
            }
        } catch (Throwable t) {
            unexpected.add(t);
        }
    }

    /** capacity=1・AUTO 承認の 30 分セル枠を 1 件コミットし ID を返す（共通枠 line_id NULL）。 */
    private Long seedSlot(LocalTime start) {
        ReservationSlotEntity entity = ReservationSlotEntity.builder()
                .teamId(TEAM_ID)
                .title("並行グループ枠")
                .slotDate(SLOT_DATE)
                .startTime(start)
                .endTime(start.plusMinutes(30))
                .capacity(1)
                .approvalMode(ApprovalMode.AUTO)
                .build();
        return slotRepository.save(entity).getId();
    }
}
