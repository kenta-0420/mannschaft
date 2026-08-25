package com.mannschaft.app.reservation.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationSlotEntityTest {

    @Test
    void slotDate変更時は日跨ぎのendDate差分を保持する() {
        ReservationSlotEntity slot = ReservationSlotEntity.builder()
                .slotDate(LocalDate.of(2026, 8, 24)).endDate(LocalDate.of(2026, 8, 25))
                .startTime(LocalTime.of(23, 0)).endTime(LocalTime.of(1, 0)).build();

        slot.changeSlotDate(LocalDate.of(2026, 8, 31));

        assertThat(slot.getEndDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(!slot.getEndDate().isBefore(slot.getSlotDate())).isTrue();
    }
}
