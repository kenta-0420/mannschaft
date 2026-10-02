package com.mannschaft.app.schedule.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.schedule.repository.ScheduleTargetRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ScheduleTargetAnonymizationEventListenerTest {

    @Test
    void deletes_target_rows_by_anonymized_user_without_touching_schedule_mode() {
        var repository = mock(ScheduleTargetRepository.class);
        var listener = new ScheduleTargetAnonymizationEventListener(repository);

        listener.handleUserAnonymized(new UserAnonymizedEvent(42L, "old@example.com"));

        verify(repository).deleteByUserId(42L);
    }

    @Test
    void cleanup_failure_does_not_escape_after_commit_listener() {
        var repository = mock(ScheduleTargetRepository.class);
        doThrow(new RuntimeException("db")).when(repository).deleteByUserId(42L);
        var listener = new ScheduleTargetAnonymizationEventListener(repository);

        assertDoesNotThrow(() -> listener.handleUserAnonymized(
                new UserAnonymizedEvent(42L, "old@example.com")));
    }
}
