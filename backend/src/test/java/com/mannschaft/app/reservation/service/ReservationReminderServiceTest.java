package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.ReminderStatus;
import com.mannschaft.app.reservation.ReservationMapper;
import com.mannschaft.app.reservation.entity.ReservationReminderEntity;
import com.mannschaft.app.reservation.repository.ReservationReminderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationReminderServiceTest {
    @Mock ReservationReminderRepository repository;
    @Mock ReservationMapper mapper;

    @Test
    void serverRepresentationIsComparedByInstantBeforeAtAndAfterDeadline() {
        Instant now = Instant.parse("2026-11-01T17:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        ReservationReminderEntity before = reminder(LocalDateTime.of(2026, 11, 2, 1, 59));
        ReservationReminderEntity equal = reminder(LocalDateTime.of(2026, 11, 2, 2, 0));
        ReservationReminderEntity after = reminder(LocalDateTime.of(2026, 11, 2, 2, 1));
        when(repository.findByStatusAndRemindAtBefore(eq(ReminderStatus.PENDING), eq(LocalDateTime.of(2026, 11, 2, 2, 0))))
                .thenReturn(List.of(before, equal));
        ReservationReminderService service = new ReservationReminderService(repository, mapper, clock);

        assertThat(service.findDueReminders()).containsExactly(before, equal).doesNotContain(after);
    }

    private static ReservationReminderEntity reminder(LocalDateTime at) {
        return ReservationReminderEntity.builder().reservationId(1L).remindAt(at).build();
    }
}
