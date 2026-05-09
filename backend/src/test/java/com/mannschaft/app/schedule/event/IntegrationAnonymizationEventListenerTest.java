package com.mannschaft.app.schedule.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.schedule.repository.UserGoogleCalendarConnectionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("IntegrationAnonymizationEventListener")
class IntegrationAnonymizationEventListenerTest {

    @Mock
    private UserGoogleCalendarConnectionRepository userGoogleCalendarConnectionRepository;

    @InjectMocks
    private IntegrationAnonymizationEventListener listener;

    @Nested
    @DisplayName("handleUserAnonymized")
    class HandleUserAnonymized {

        @Test
        @DisplayName("正常系: Google Calendar連携が削除される（GDPR: OAuthトークン含む）")
        void deletesGoogleCalendarConnection() {
            Long userId = 50L;
            var event = new UserAnonymizedEvent(userId, "user@example.com");

            listener.handleUserAnonymized(event);

            verify(userGoogleCalendarConnectionRepository).deleteByUserId(userId);
        }

        @Test
        @DisplayName("例外系: Repositoryが例外を投げてもRuntimeExceptionを外に伝播させない")
        void doesNotPropagateException() {
            Long userId = 99L;
            var event = new UserAnonymizedEvent(userId, "fail@example.com");
            doThrow(new RuntimeException("DB error"))
                    .when(userGoogleCalendarConnectionRepository).deleteByUserId(userId);

            assertDoesNotThrow(() -> listener.handleUserAnonymized(event));
        }
    }
}
