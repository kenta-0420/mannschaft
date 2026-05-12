package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.EmailService;
import com.mannschaft.app.common.EmailTemplateRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthEmailEventListener のユニットテスト。
 * EmailService と EmailTemplateRenderer をモックして送信が呼ばれることを検証する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuthEmailEventListener")
class AuthEmailEventListenerTest {

    @Mock
    private EmailService emailService;

    @Mock
    private EmailTemplateRenderer emailTemplateRenderer;

    @InjectMocks
    private AuthEmailEventListener listener;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(listener, "frontendUrl", "http://localhost:3000");
        when(emailTemplateRenderer.resolveMessage(anyString(), any(Locale.class))).thenReturn("テスト件名");
        when(emailTemplateRenderer.renderVerificationEmail(anyString(), anyString(), any(Locale.class)))
                .thenReturn("<html>verification</html>");
        when(emailTemplateRenderer.renderPasswordResetEmail(anyString(), any(Locale.class)))
                .thenReturn("<html>reset</html>");
    }

    @Nested
    @DisplayName("handleUserRegistered")
    class HandleUserRegistered {

        @Test
        @DisplayName("正常系: 登録イベント受信時にEmailService.sendEmailが呼ばれる")
        void callsSendEmailOnUserRegistered() {
            var event = new UserRegisteredEvent(1L, "user@example.com", "山田 太郎", "token-abc123");

            listener.handleUserRegistered(event);

            verify(emailTemplateRenderer).renderVerificationEmail(
                    eq("山田 太郎"),
                    contains("/verify-email?token=token-abc123"),
                    any(Locale.class)
            );
            verify(emailService).sendEmail(
                    eq("user@example.com"),
                    anyString(),
                    anyString()
            );
        }
    }

    @Nested
    @DisplayName("handleEmailVerificationResent")
    class HandleEmailVerificationResent {

        @Test
        @DisplayName("正常系: 認証再送イベント受信時にEmailService.sendEmailが呼ばれる")
        void callsSendEmailOnEmailVerificationResent() {
            var event = new EmailVerificationResentEvent(2L, "resend@example.com", "token-resend456");

            listener.handleEmailVerificationResent(event);

            verify(emailTemplateRenderer).renderVerificationEmail(
                    eq(""),
                    contains("/verify-email?token=token-resend456"),
                    any(Locale.class)
            );
            verify(emailService).sendEmail(
                    eq("resend@example.com"),
                    anyString(),
                    anyString()
            );
        }
    }

    @Nested
    @DisplayName("handlePasswordResetRequested")
    class HandlePasswordResetRequested {

        @Test
        @DisplayName("正常系: パスワードリセットイベント受信時にEmailService.sendEmailが呼ばれる")
        void callsSendEmailOnPasswordResetRequested() {
            var event = new PasswordResetRequestedEvent(3L, "reset@example.com", "token-reset789");

            listener.handlePasswordResetRequested(event);

            verify(emailTemplateRenderer).renderPasswordResetEmail(
                    contains("/reset-password?token=token-reset789"),
                    any(Locale.class)
            );
            verify(emailService).sendEmail(
                    eq("reset@example.com"),
                    anyString(),
                    anyString()
            );
        }
    }
}
