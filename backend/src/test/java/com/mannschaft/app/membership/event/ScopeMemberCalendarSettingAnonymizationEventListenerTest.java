package com.mannschaft.app.membership.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.membership.repository.ScopeMemberCalendarSettingRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ScopeMemberCalendarSettingAnonymizationEventListenerTest {

    @Test
    void deletes_calendar_color_rows_by_anonymized_user() {
        var repository = mock(ScopeMemberCalendarSettingRepository.class);
        var listener = new ScopeMemberCalendarSettingAnonymizationEventListener(repository);

        listener.handleUserAnonymized(new UserAnonymizedEvent(42L, "old@example.com"));

        verify(repository).deleteByUserId(42L);
    }

    @Test
    void cleanup_failure_does_not_escape_after_commit_listener() {
        var repository = mock(ScopeMemberCalendarSettingRepository.class);
        doThrow(new RuntimeException("db")).when(repository).deleteByUserId(42L);
        var listener = new ScopeMemberCalendarSettingAnonymizationEventListener(repository);

        assertDoesNotThrow(() -> listener.handleUserAnonymized(
                new UserAnonymizedEvent(42L, "old@example.com")));
    }
}
