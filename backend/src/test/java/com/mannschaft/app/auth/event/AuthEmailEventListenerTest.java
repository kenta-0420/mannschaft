package com.mannschaft.app.auth.event;

import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthEmailEventListener のユニットテスト。
 *
 * <p>Phase 18-b 移行で {@link EmailOutboxService#enqueue} 呼び出しを検証する形に変更。
 * 旧 EmailService / EmailTemplateRenderer 直接呼びは Worker 側に移った。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthEmailEventListener")
class AuthEmailEventListenerTest {

    @Mock
    private EmailOutboxService emailOutboxService;

    @InjectMocks
    private AuthEmailEventListener listener;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(listener, "baseUrl", "http://localhost:3000");
        when(emailOutboxService.enqueue(any(EmailOutboxRequest.class))).thenReturn(UUID.randomUUID());
    }

    @Nested
    @DisplayName("handleUserRegistered")
    class HandleUserRegistered {

        @Test
        @DisplayName("正常系: VERIFICATION テンプレで outbox に enqueue される")
        void enqueuesVerificationOnUserRegistered() {
            var event = new UserRegisteredEvent(1L, "user@example.com", "山田 太郎", "token-abc123");

            listener.handleUserRegistered(event);

            ArgumentCaptor<EmailOutboxRequest> captor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
            verify(emailOutboxService).enqueue(captor.capture());
            EmailOutboxRequest req = captor.getValue();

            assertThat(req.templateKind()).isEqualTo("VERIFICATION");
            assertThat(req.locale()).isEqualTo("ja");
            assertThat(req.toAddress()).isEqualTo("user@example.com");
            assertThat(req.sourceDomain()).isEqualTo("auth");
            assertThat(req.userId()).isEqualTo(1L);
            assertThat(req.organizationId()).isNull();
            assertThat(req.sourceEventId()).isEqualTo("register:1");
            assertThat(req.payloadVars())
                    .containsEntry("displayName", "山田 太郎")
                    .containsEntry("verifyUrl", "http://localhost:3000/verify-email?token=token-abc123");
        }

        @Test
        @DisplayName("displayName が null の場合は空文字で enqueue される")
        void enqueuesWithEmptyDisplayNameWhenNull() {
            var event = new UserRegisteredEvent(99L, "anon@example.com", null, "token-null-name");

            listener.handleUserRegistered(event);

            ArgumentCaptor<EmailOutboxRequest> captor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
            verify(emailOutboxService).enqueue(captor.capture());

            assertThat(captor.getValue().payloadVars()).containsEntry("displayName", "");
        }
    }

    @Nested
    @DisplayName("handleEmailVerificationResent")
    class HandleEmailVerificationResent {

        @Test
        @DisplayName("再送時は displayName 空文字、sourceEventId に \"resent:\" 接頭辞で enqueue")
        void enqueuesResentVerification() {
            var event = new EmailVerificationResentEvent(2L, "resend@example.com", "token-resend456");

            listener.handleEmailVerificationResent(event);

            ArgumentCaptor<EmailOutboxRequest> captor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
            verify(emailOutboxService).enqueue(captor.capture());
            EmailOutboxRequest req = captor.getValue();

            assertThat(req.templateKind()).isEqualTo("VERIFICATION");
            assertThat(req.toAddress()).isEqualTo("resend@example.com");
            assertThat(req.payloadVars())
                    .containsEntry("displayName", "")
                    .containsEntry("verifyUrl", "http://localhost:3000/verify-email?token=token-resend456");
            assertThat(req.sourceEventId()).startsWith("resent:2:");
            assertThat(req.userId()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("handlePasswordResetRequested")
    class HandlePasswordResetRequested {

        @Test
        @DisplayName("PASSWORD_RESET テンプレで sourceEventId に \"pwreset:\" 接頭辞で enqueue")
        void enqueuesPasswordReset() {
            var event = new PasswordResetRequestedEvent(3L, "reset@example.com", "token-reset789");

            listener.handlePasswordResetRequested(event);

            ArgumentCaptor<EmailOutboxRequest> captor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
            verify(emailOutboxService).enqueue(captor.capture());
            EmailOutboxRequest req = captor.getValue();

            assertThat(req.templateKind()).isEqualTo("PASSWORD_RESET");
            assertThat(req.toAddress()).isEqualTo("reset@example.com");
            assertThat(req.payloadVars())
                    .containsEntry("resetUrl", "http://localhost:3000/reset-password?token=token-reset789");
            assertThat(req.sourceEventId()).startsWith("pwreset:3:");
            assertThat(req.userId()).isEqualTo(3L);
            assertThat(req.payloadVars()).doesNotContainKey("displayName");
        }
    }
}
