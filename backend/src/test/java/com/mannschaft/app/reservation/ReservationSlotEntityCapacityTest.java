package com.mannschaft.app.reservation;

import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReservationSlotEntity} の定員(capacity)ドメインロジック単体テスト（純 POJO・DB 不要）。
 *
 * <h2>背景（実機E2Eで発見・オーバーブッキング根治）</h2>
 * <p>従来 {@code incrementBookedCount} は満席化（{@code markFull}）を一切行わず、枠に定員の概念も
 * 無かったため、同一予約枠へ無制限に予約でき、予約後もグリッド/空き枠一覧が「空き」のままだった
 * （美容院 1:1 指名で同一枠に複数予約が入る事故）。本テストは「{@code booked_count >= capacity}
 * で FULL」「キャンセルで {@code booked_count < capacity} に戻れば AVAILABLE 復帰」という
 * 定員ドメインルールを固定する。実際の並行制御はリポジトリのアトミック UPDATE が担うが、
 * このエンティティ内ロジックは両者が同一ルールであることの番人でもある。</p>
 */
@DisplayName("ReservationSlotEntity 定員(capacity)ドメインロジック単体テスト")
class ReservationSlotEntityCapacityTest {

    private ReservationSlotEntity slot(Integer capacity) {
        ReservationSlotEntity.ReservationSlotEntityBuilder<?, ?> builder = ReservationSlotEntity.builder()
                .teamId(1L)
                .slotDate(LocalDate.of(2026, 7, 1))
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(11, 0));
        if (capacity != null) {
            builder.capacity(capacity);
        }
        return builder.build();
    }

    @Nested
    @DisplayName("既定値")
    class Defaults {

        @Test
        @DisplayName("capacity 未指定なら既定 1（＝1:1 指名）")
        void 既定は1() {
            assertThat(slot(null).getCapacity()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("capacity=1（1:1 指名）")
    class CapacityOne {

        @Test
        @DisplayName("1 件予約で満席（FULL）化し isFull=true")
        void 一件で満席() {
            ReservationSlotEntity s = slot(1);

            s.incrementBookedCount();

            assertThat(s.getBookedCount()).isEqualTo(1);
            assertThat(s.getSlotStatus()).isEqualTo(SlotStatus.FULL);
            assertThat(s.isFull()).isTrue();
            assertThat(s.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("満席後キャンセルで AVAILABLE に復帰する")
        void キャンセルで復帰() {
            ReservationSlotEntity s = slot(1);
            s.incrementBookedCount();

            s.decrementBookedCount();

            assertThat(s.getBookedCount()).isEqualTo(0);
            assertThat(s.getSlotStatus()).isEqualTo(SlotStatus.AVAILABLE);
            assertThat(s.isFull()).isFalse();
        }
    }

    @Nested
    @DisplayName("capacity=3（複数受付）")
    class CapacityThree {

        @Test
        @DisplayName("2 件目までは AVAILABLE、3 件目で満席化する")
        void 三件目で満席() {
            ReservationSlotEntity s = slot(3);

            s.incrementBookedCount(); // 1
            assertThat(s.getSlotStatus()).isEqualTo(SlotStatus.AVAILABLE);
            s.incrementBookedCount(); // 2
            assertThat(s.getSlotStatus()).isEqualTo(SlotStatus.AVAILABLE);
            s.incrementBookedCount(); // 3 → 満席

            assertThat(s.getBookedCount()).isEqualTo(3);
            assertThat(s.getSlotStatus()).isEqualTo(SlotStatus.FULL);
            assertThat(s.isFull()).isTrue();
        }

        @Test
        @DisplayName("満席後 1 件キャンセルで AVAILABLE に復帰する")
        void 満席後キャンセルで復帰() {
            ReservationSlotEntity s = slot(3);
            s.incrementBookedCount();
            s.incrementBookedCount();
            s.incrementBookedCount(); // FULL

            s.decrementBookedCount(); // 2 に戻る → AVAILABLE

            assertThat(s.getBookedCount()).isEqualTo(2);
            assertThat(s.getSlotStatus()).isEqualTo(SlotStatus.AVAILABLE);
        }
    }

    @Nested
    @DisplayName("changeCapacity（定員変更で満席/空きを再評価）")
    class ChangeCapacity {

        @Test
        @DisplayName("定員を予約数以下へ下げると FULL 化する")
        void 定員縮小で満席() {
            ReservationSlotEntity s = slot(3);
            s.incrementBookedCount();
            s.incrementBookedCount(); // booked=2, AVAILABLE

            s.changeCapacity(2); // 定員 2 に縮小 → booked(2) >= capacity(2) → FULL

            assertThat(s.getSlotStatus()).isEqualTo(SlotStatus.FULL);
        }

        @Test
        @DisplayName("満席枠の定員を上げると AVAILABLE に復帰する")
        void 定員拡大で復帰() {
            ReservationSlotEntity s = slot(1);
            s.incrementBookedCount(); // FULL

            s.changeCapacity(3); // 定員 3 に拡大 → booked(1) < capacity(3) → AVAILABLE

            assertThat(s.getCapacity()).isEqualTo(3);
            assertThat(s.getSlotStatus()).isEqualTo(SlotStatus.AVAILABLE);
        }

        @Test
        @DisplayName("CLOSED 枠は定員変更しても CLOSED を据え置く")
        void クローズは据え置き() {
            ReservationSlotEntity s = slot(1);
            s.close("受付終了");

            s.changeCapacity(5);

            assertThat(s.getSlotStatus()).isEqualTo(SlotStatus.CLOSED);
        }
    }

    @Nested
    @DisplayName("CLOSED の据え置き（decrement で AVAILABLE に戻さない）")
    class ClosedPreserved {

        @Test
        @DisplayName("CLOSED 枠は decrement で AVAILABLE に戻らない")
        void クローズはデクリメントで復帰しない() {
            ReservationSlotEntity s = slot(1);
            s.incrementBookedCount(); // FULL
            s.close("受付終了");       // CLOSED

            s.decrementBookedCount();

            assertThat(s.getSlotStatus()).isEqualTo(SlotStatus.CLOSED);
        }
    }
}
