package com.mannschaft.app.auth;

import com.mannschaft.app.auth.event.AuthAnonymizationEventListener;
import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
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
@DisplayName("AuthAnonymizationEventListener")
class AuthAnonymizationEventListenerTest {

    @Mock
    private OAuthAccountRepository oAuthAccountRepository;

    @Mock
    private TwoFactorAuthRepository twoFactorAuthRepository;

    @InjectMocks
    private AuthAnonymizationEventListener listener;

    @Nested
    @DisplayName("handleUserAnonymized")
    class HandleUserAnonymized {

        @Test
        @DisplayName("正常系: OAuth連携と2FA設定が削除される")
        void deletesOAuthAndTwoFactorAuth() {
            var event = new UserAnonymizedEvent(42L, "user@example.com");

            listener.handleUserAnonymized(event);

            verify(oAuthAccountRepository).deleteByUserId(42L);
            verify(twoFactorAuthRepository).deleteByUserId(42L);
        }

        @Test
        @DisplayName("例外系: Repositoryが例外を投げてもRuntimeExceptionを外に伝播させない")
        void doesNotPropagateException() {
            var event = new UserAnonymizedEvent(99L, "fail@example.com");
            doThrow(new RuntimeException("DB error")).when(oAuthAccountRepository).deleteByUserId(99L);

            assertDoesNotThrow(() -> listener.handleUserAnonymized(event));
        }
    }
}
