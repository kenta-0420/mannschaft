package com.mannschaft.app.reservation;

import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReservationEntity} および {@link ReservationSlotEntity} のエンティティ単体テスト。
 * JaCoCoでカバーされていないメソッドを補完する。
 */
@DisplayName("Reservationエンティティ 単体テスト")
class ReservationEntityTest {

    // ========================================
    // ReservationEntity
    // ========================================

    @Nested
    @DisplayName("ReservationEntity")
    class ReservationEntityTests {

        private ReservationEntity createReservation() {
            return ReservationEntity.builder()
                    .reservationSlotId(10L)
                    .lineId(20L)
                    .teamId(30L)
                    .userId(100L)
                    .status(ReservationStatus.PENDING)
                    .userNote("メモ")
                    .build();
        }

        @Test
        @DisplayName("confirm_PENDING状態_CONFIRMED状態になる")
        void confirm_PENDING状態_CONFIRMED状態になる() {
            // Given
            ReservationEntity reservation = createReservation();

            // When
            reservation.confirm();

            // Then
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(reservation.getConfirmedAt()).isNotNull();
        }

        @Test
        @DisplayName("cancel_PENDING状態_CANCELLED状態になる")
        void cancel_PENDING状態_CANCELLED状態になる() {
            // Given
            ReservationEntity reservation = createReservation();

            // When
            reservation.cancel("都合が悪くなりました", CancelledBy.USER);

            // Then
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(reservation.getCancelReason()).isEqualTo("都合が悪くなりました");
            assertThat(reservation.getCancelledBy()).isEqualTo(CancelledBy.USER);
            assertThat(reservation.getCancelledAt()).isNotNull();
        }

        @Test
        @DisplayName("complete_CONFIRMED状態_COMPLETED状態になる")
        void complete_CONFIRMED状態_COMPLETED状態になる() {
            // Given
            ReservationEntity reservation = createReservation();
            reservation.confirm();

            // When
            reservation.complete();

            // Then
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
            assertThat(reservation.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("noShow_CONFIRMED状態_NO_SHOW状態になる")
        void noShow_CONFIRMED状態_NO_SHOW状態になる() {
            // Given
            ReservationEntity reservation = createReservation();
            reservation.confirm();

            // When
            reservation.noShow();

            // Then
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        }

        @Test
        @DisplayName("reschedule_新しいスロットに変更_PENDING状態に戻る")
        void reschedule_新しいスロットに変更_PENDING状態に戻る() {
            // Given
            ReservationEntity reservation = createReservation();
            reservation.confirm();

            // When
            reservation.reschedule(99L);

            // Then
            assertThat(reservation.getReservationSlotId()).isEqualTo(99L);
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING);
            assertThat(reservation.getConfirmedAt()).isNull();
        }

        @Test
        @DisplayName("updateAdminNote_管理者メモ更新_値が反映される")
        void updateAdminNote_管理者メモ更新_値が反映される() {
            // Given
            ReservationEntity reservation = createReservation();

            // When
            reservation.updateAdminNote("管理者コメント");

            // Then
            assertThat(reservation.getAdminNote()).isEqualTo("管理者コメント");
        }

        @Test
        @DisplayName("isConfirmable_PENDING状態_true返却")
        void isConfirmable_PENDING状態_true返却() {
            // Given
            ReservationEntity reservation = createReservation();

            // When & Then
            assertThat(reservation.isConfirmable()).isTrue();
        }

        @Test
        @DisplayName("isConfirmable_CONFIRMED状態_false返却")
        void isConfirmable_CONFIRMED状態_false返却() {
            // Given
            ReservationEntity reservation = createReservation();
            reservation.confirm();

            // When & Then
            assertThat(reservation.isConfirmable()).isFalse();
        }

        @Test
        @DisplayName("isCancellable_PENDING状態_true返却")
        void isCancellable_PENDING状態_true返却() {
            // Given
            ReservationEntity reservation = createReservation();

            // When & Then
            assertThat(reservation.isCancellable()).isTrue();
        }

        @Test
        @DisplayName("isCancellable_CONFIRMED状態_true返却")
        void isCancellable_CONFIRMED状態_true返却() {
            // Given
            ReservationEntity reservation = createReservation();
            reservation.confirm();

            // When & Then
            assertThat(reservation.isCancellable()).isTrue();
        }

        @Test
        @DisplayName("isCancellable_COMPLETED状態_false返却")
        void isCancellable_COMPLETED状態_false返却() {
            // Given
            ReservationEntity reservation = createReservation();
            reservation.confirm();
            reservation.complete();

            // When & Then
            assertThat(reservation.isCancellable()).isFalse();
        }

        @Test
        @DisplayName("softDelete_論理削除_deletedAtが設定される")
        void softDelete_論理削除_deletedAtが設定される() {
            // Given
            ReservationEntity reservation = createReservation();

            // When
            reservation.softDelete();

            // Then
            assertThat(reservation.getDeletedAt()).isNotNull();
        }
    }

    // ========================================
    // ReservationSlotEntity
    // ========================================

    @Nested
    @DisplayName("ReservationSlotEntity")
    class ReservationSlotEntityTests {

        private ReservationSlotEntity createSlot() {
            return ReservationSlotEntity.builder()
                    .teamId(30L)
                    .slotDate(LocalDate.now().plusDays(1))
                    .startTime(LocalTime.of(10, 0))
                    .endTime(LocalTime.of(11, 0))
                    .bookedCount(0)
                    .slotStatus(SlotStatus.AVAILABLE)
                    .build();
        }

        @Test
        @DisplayName("incrementBookedCount_予約数インクリメント_値が増加する")
        void incrementBookedCount_予約数インクリメント_値が増加する() {
            // Given
            ReservationSlotEntity slot = createSlot();

            // When
            slot.incrementBookedCount();

            // Then
            assertThat(slot.getBookedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("decrementBookedCount_予約数1_デクリメントで0になる")
        void decrementBookedCount_予約数1_デクリメントで0になる() {
            // Given
            ReservationSlotEntity slot = createSlot();
            slot.incrementBookedCount();

            // When
            slot.decrementBookedCount();

            // Then
            assertThat(slot.getBookedCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("decrementBookedCount_予約数0_0未満にならない")
        void decrementBookedCount_予約数0_0未満にならない() {
            // Given
            ReservationSlotEntity slot = createSlot();

            // When
            slot.decrementBookedCount();

            // Then
            assertThat(slot.getBookedCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("markFull_満席マーク_FULL状態になる")
        void markFull_満席マーク_FULL状態になる() {
            // Given
            ReservationSlotEntity slot = createSlot();

            // When
            slot.markFull();

            // Then
            assertThat(slot.getSlotStatus()).isEqualTo(SlotStatus.FULL);
        }

        @Test
        @DisplayName("markAvailable_利用可能に戻す_AVAILABLE状態になる")
        void markAvailable_利用可能に戻す_AVAILABLE状態になる() {
            // Given
            ReservationSlotEntity slot = createSlot();
            slot.markFull();

            // When
            slot.markAvailable();

            // Then
            assertThat(slot.getSlotStatus()).isEqualTo(SlotStatus.AVAILABLE);
        }

        @Test
        @DisplayName("close_クローズ_CLOSED状態になりreason設定")
        void close_クローズ_CLOSED状態になりreason設定() {
            // Given
            ReservationSlotEntity slot = createSlot();

            // When
            slot.close("メンテナンス");

            // Then
            assertThat(slot.getSlotStatus()).isEqualTo(SlotStatus.CLOSED);
            assertThat(slot.getClosedReason()).isEqualTo("メンテナンス");
        }

        @Test
        @DisplayName("isRecurring_繰り返しルールなし_false返却")
        void isRecurring_繰り返しルールなし_false返却() {
            // Given
            ReservationSlotEntity slot = createSlot();

            // When & Then
            assertThat(slot.isRecurring()).isFalse();
        }

        @Test
        @DisplayName("isRecurring_繰り返しルールあり_true返却")
        void isRecurring_繰り返しルールあり_true返却() {
            // Given
            ReservationSlotEntity slot = ReservationSlotEntity.builder()
                    .teamId(30L)
                    .slotDate(LocalDate.now().plusDays(1))
                    .startTime(LocalTime.of(10, 0))
                    .endTime(LocalTime.of(11, 0))
                    .recurrenceRule("{\"freq\":\"WEEKLY\"}")
                    .build();

            // When & Then
            assertThat(slot.isRecurring()).isTrue();
        }

        @Test
        @DisplayName("isAvailable_AVAILABLE状態_true返却")
        void isAvailable_AVAILABLE状態_true返却() {
            // Given
            ReservationSlotEntity slot = createSlot();

            // When & Then
            assertThat(slot.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("isAvailable_FULL状態_false返却")
        void isAvailable_FULL状態_false返却() {
            // Given
            ReservationSlotEntity slot = createSlot();
            slot.markFull();

            // When & Then
            assertThat(slot.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("softDelete_論理削除_deletedAtが設定される")
        void softDelete_論理削除_deletedAtが設定される() {
            // Given
            ReservationSlotEntity slot = createSlot();

            // When
            slot.softDelete();

            // Then
            assertThat(slot.getDeletedAt()).isNotNull();
        }
    }
}
