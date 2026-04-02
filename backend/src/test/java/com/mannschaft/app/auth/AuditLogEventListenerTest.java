package com.mannschaft.app.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.event.AccountLockedEvent;
import com.mannschaft.app.auth.event.AccountUnlockedEvent;
import com.mannschaft.app.auth.event.AuditLogEventListener;
import com.mannschaft.app.auth.event.EmailChangedEvent;
import com.mannschaft.app.auth.event.LoginFailedEvent;
import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.auth.event.LogoutEvent;
import com.mannschaft.app.auth.event.MfaEnabledEvent;
import com.mannschaft.app.auth.event.OAuthLinkedEvent;
import com.mannschaft.app.auth.event.UserFrozenEvent;
import com.mannschaft.app.auth.event.UserRegisteredEvent;
import com.mannschaft.app.auth.service.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogEventListener")
class AuditLogEventListenerTest {

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper;

    @InjectMocks
    private AuditLogEventListener listener;

    @Nested
    @DisplayName("AUTH カテゴリ")
    class AuthCategory {

        @Test
        @DisplayName("LoginSuccessEvent → LOGIN_SUCCESS として記録")
        void handleLoginSuccess() {
            var event = new LoginSuccessEvent(1L, "192.168.0.1", "Mozilla/5.0", "PASSWORD");

            listener.handleLoginSuccess(event);

            verify(auditLogService).record(
                    eq("LOGIN_SUCCESS"), eq(1L), isNull(),
                    isNull(), isNull(),
                    eq("192.168.0.1"), eq("Mozilla/5.0"), isNull(), isNull()
            );
        }

        @Test
        @DisplayName("LoginFailedEvent → LOGIN_FAILED として記録し reason を metadata に設定")
        void handleLoginFailed() {
            var event = new LoginFailedEvent("user@example.com", "192.168.0.1", "Mozilla/5.0", "INVALID_PASSWORD");

            listener.handleLoginFailed(event);

            ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).record(
                    eq("LOGIN_FAILED"), isNull(), isNull(),
                    isNull(), isNull(),
                    eq("192.168.0.1"), eq("Mozilla/5.0"), isNull(),
                    metadataCaptor.capture()
            );
            assertThat(metadataCaptor.getValue()).contains("INVALID_PASSWORD");
        }

        @Test
        @DisplayName("LogoutEvent(SESSION) → LOGOUT_SESSION として記録")
        void handleLogoutSession() {
            var event = new LogoutEvent(1L, 1, LogoutEvent.LogoutType.SESSION, 42L);

            listener.handleLogout(event);

            ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).record(
                    eq("LOGOUT_SESSION"), eq(1L), isNull(),
                    isNull(), isNull(), isNull(), isNull(), isNull(),
                    metadataCaptor.capture()
            );
            assertThat(metadataCaptor.getValue()).contains("42");
        }

        @Test
        @DisplayName("LogoutEvent(ALL_SESSIONS) → LOGOUT_ALL_SESSIONS として記録")
        void handleLogoutAllSessions() {
            var event = new LogoutEvent(1L, 3, LogoutEvent.LogoutType.ALL_SESSIONS);

            listener.handleLogout(event);

            verify(auditLogService).record(
                    eq("LOGOUT_ALL_SESSIONS"), eq(1L), isNull(),
                    isNull(), isNull(), isNull(), isNull(), isNull(), isNull()
            );
        }

        @Test
        @DisplayName("LogoutEvent(SESSION) で sessionId が null の場合、metadata も null")
        void handleLogoutSessionWithNullSessionId() {
            var event = new LogoutEvent(1L, 1, LogoutEvent.LogoutType.SESSION);

            listener.handleLogout(event);

            verify(auditLogService).record(
                    eq("LOGOUT_SESSION"), eq(1L), isNull(),
                    isNull(), isNull(), isNull(), isNull(), isNull(), isNull()
            );
        }
    }

    @Nested
    @DisplayName("ACCOUNT カテゴリ")
    class AccountCategory {

        @Test
        @DisplayName("UserRegisteredEvent → USER_REGISTERED として記録")
        void handleUserRegistered() {
            var event = new UserRegisteredEvent(1L, "user@example.com", "テストユーザー", "token123");

            listener.handleUserRegistered(event);

            verify(auditLogService).record(
                    eq("USER_REGISTERED"), eq(1L), isNull(),
                    isNull(), isNull(), isNull(), isNull(), isNull(), isNull()
            );
        }

        @Test
        @DisplayName("AccountLockedEvent → ACCOUNT_LOCKED として記録し reason と unlock_at を metadata に設定")
        void handleAccountLocked() {
            var unlockAt = LocalDateTime.of(2026, 4, 2, 10, 30);
            var event = new AccountLockedEvent(1L, "BRUTE_FORCE", unlockAt);

            listener.handleAccountLocked(event);

            ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).record(
                    eq("ACCOUNT_LOCKED"), eq(1L), isNull(),
                    isNull(), isNull(), isNull(), isNull(), isNull(),
                    metadataCaptor.capture()
            );
            assertThat(metadataCaptor.getValue())
                    .contains("BRUTE_FORCE")
                    .contains("unlock_at");
        }

        @Test
        @DisplayName("EmailChangedEvent → EMAIL_CHANGED として記録し old/new メールを metadata に設定")
        void handleEmailChanged() {
            var event = new EmailChangedEvent(1L, "old@example.com", "new@example.com");

            listener.handleEmailChanged(event);

            ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).record(
                    eq("EMAIL_CHANGED"), eq(1L), isNull(),
                    isNull(), isNull(), isNull(), isNull(), isNull(),
                    metadataCaptor.capture()
            );
            assertThat(metadataCaptor.getValue())
                    .contains("old@example.com")
                    .contains("new@example.com");
        }
    }

    @Nested
    @DisplayName("OAuth カテゴリ")
    class OAuthCategory {

        @Test
        @DisplayName("OAuthLinkedEvent → OAUTH_LINKED として記録し provider を metadata に設定")
        void handleOAuthLinked() {
            var event = new OAuthLinkedEvent(1L, "GOOGLE");

            listener.handleOAuthLinked(event);

            ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).record(
                    eq("OAUTH_LINKED"), eq(1L), isNull(),
                    isNull(), isNull(), isNull(), isNull(), isNull(),
                    metadataCaptor.capture()
            );
            assertThat(metadataCaptor.getValue()).contains("GOOGLE");
        }
    }

    @Nested
    @DisplayName("MFA カテゴリ")
    class MfaCategory {

        @Test
        @DisplayName("MfaEnabledEvent → MFA_ENABLED として記録")
        void handleMfaEnabled() {
            var event = new MfaEnabledEvent(1L);

            listener.handleMfaEnabled(event);

            verify(auditLogService).record(
                    eq("MFA_ENABLED"), eq(1L), isNull(),
                    isNull(), isNull(), isNull(), isNull(), isNull(), isNull()
            );
        }
    }

    @Nested
    @DisplayName("ADMIN_ACTION カテゴリ")
    class AdminActionCategory {

        @Test
        @DisplayName("AccountUnlockedEvent → ACCOUNT_UNLOCKED として記録し metadata に reason=ADMIN_ACTION を設定")
        void handleAccountUnlocked() {
            var event = new AccountUnlockedEvent(99L, 1L);

            listener.handleAccountUnlocked(event);

            ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).record(
                    eq("ACCOUNT_UNLOCKED"), eq(99L), eq(1L),
                    isNull(), isNull(), isNull(), isNull(), isNull(),
                    metadataCaptor.capture()
            );
            assertThat(metadataCaptor.getValue()).contains("ADMIN_ACTION");
        }

        @Test
        @DisplayName("UserFrozenEvent → USER_FROZEN として記録し adminId を userId、targetUserId を設定")
        void handleUserFrozen() {
            var event = new UserFrozenEvent(99L, 1L, "TERMS_VIOLATION");

            listener.handleUserFrozen(event);

            ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).record(
                    eq("USER_FROZEN"), eq(99L), eq(1L),
                    isNull(), isNull(), isNull(), isNull(), isNull(),
                    metadataCaptor.capture()
            );
            assertThat(metadataCaptor.getValue()).contains("TERMS_VIOLATION");
        }
    }
}
