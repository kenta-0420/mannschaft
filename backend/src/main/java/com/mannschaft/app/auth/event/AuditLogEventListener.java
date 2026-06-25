package com.mannschaft.app.auth.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.circulation.event.CirculationExportGeneratedEvent;
import com.mannschaft.app.circulation.event.CirculationExportRequestedEvent;
import com.mannschaft.app.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * 監査ログイベントリスナー。
 * 全認証・アカウント関連イベントをAuditLogServiceに配線する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogEventListener {

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────────
    // AUTH
    // ─────────────────────────────────────────────

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleLoginSuccess(LoginSuccessEvent event) {
        auditLogService.record(
            AuditEventType.LOGIN_SUCCESS.name(),
            event.getUserId(),
            null,
            null,
            null,
            event.getIpAddress(),
            event.getUserAgent(),
            event.getSessionHash(),
            null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleLoginFailed(LoginFailedEvent event) {
        auditLogService.record(
            AuditEventType.LOGIN_FAILED.name(),
            null,
            null,
            null,
            null,
            event.getIpAddress(),
            event.getUserAgent(),
            null,
            toJson(Map.of("reason", event.getReason()))
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleLogout(LogoutEvent event) {
        AuditEventType type;
        String metadata = null;
        if (event.getLogoutType() == LogoutEvent.LogoutType.ALL_SESSIONS) {
            type = AuditEventType.LOGOUT_ALL_SESSIONS;
        } else {
            type = AuditEventType.LOGOUT_SESSION;
            if (event.getSessionId() != null) {
                metadata = toJson(Map.of("session_id", event.getSessionId()));
            }
        }
        auditLogService.record(
            type.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            event.getSessionHash(),
            metadata
        );
    }

    // ─────────────────────────────────────────────
    // ACCOUNT
    // ─────────────────────────────────────────────

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserRegistered(UserRegisteredEvent event) {
        auditLogService.record(
            AuditEventType.USER_REGISTERED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleEmailVerified(EmailVerifiedEvent event) {
        auditLogService.record(
            AuditEventType.EMAIL_VERIFIED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePasswordResetRequested(PasswordResetRequestedEvent event) {
        auditLogService.record(
            AuditEventType.PASSWORD_RESET_REQUESTED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePasswordResetCompleted(PasswordResetCompletedEvent event) {
        auditLogService.record(
            AuditEventType.PASSWORD_RESET_COMPLETED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePasswordChanged(PasswordChangedEvent event) {
        auditLogService.record(
            AuditEventType.PASSWORD_CHANGED.name(),
            event.getUserId(),
            null,
            null,
            null,
            event.getIpAddress(),
            null,
            SecurityUtils.getCurrentSessionHash(),
            null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleEmailChangeRequested(EmailChangeRequestedEvent event) {
        auditLogService.record(
            AuditEventType.EMAIL_CHANGE_REQUESTED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            SecurityUtils.getCurrentSessionHash(),
            toJson(Map.of("new_email", event.getNewEmail()))
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleEmailChanged(EmailChangedEvent event) {
        auditLogService.record(
            AuditEventType.EMAIL_CHANGED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            SecurityUtils.getCurrentSessionHash(),
            toJson(Map.of("old_email", event.getOldEmail(), "new_email", event.getNewEmail()))
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWithdrawalRequested(WithdrawalRequestedEvent event) {
        auditLogService.record(
            AuditEventType.WITHDRAWAL_REQUESTED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            SecurityUtils.getCurrentSessionHash(),
            null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWithdrawalCancelled(WithdrawalCancelledEvent event) {
        auditLogService.record(
            AuditEventType.WITHDRAWAL_CANCELLED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            SecurityUtils.getCurrentSessionHash(),
            null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleAccountLocked(AccountLockedEvent event) {
        auditLogService.record(
            AuditEventType.ACCOUNT_LOCKED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            null,
            toJson(Map.of(
                "reason", event.getReason(),
                "unlock_at", event.getUnlockAt().toString()
            ))
        );
    }

    // ─────────────────────────────────────────────
    // OAUTH
    // ─────────────────────────────────────────────

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOAuthLinked(OAuthLinkedEvent event) {
        auditLogService.record(
            AuditEventType.OAUTH_LINKED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            SecurityUtils.getCurrentSessionHash(),
            toJson(Map.of("provider", event.getProvider()))
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOAuthUnlinked(OAuthUnlinkedEvent event) {
        auditLogService.record(
            AuditEventType.OAUTH_UNLINKED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            SecurityUtils.getCurrentSessionHash(),
            toJson(Map.of("provider", event.getProvider()))
        );
    }

    // ─────────────────────────────────────────────
    // MFA
    // ─────────────────────────────────────────────

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMfaEnabled(MfaEnabledEvent event) {
        auditLogService.record(
            AuditEventType.MFA_ENABLED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            SecurityUtils.getCurrentSessionHash(),
            null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMfaDisabled(MfaDisabledEvent event) {
        auditLogService.record(
            AuditEventType.MFA_DISABLED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            SecurityUtils.getCurrentSessionHash(),
            null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMfaRecoveryRequested(MfaRecoveryRequestedEvent event) {
        // 未認証フロー（MFA リカバリーはセッション外で発生）: session_hash は null のまま
        auditLogService.record(
            AuditEventType.MFA_RECOVERY_REQUESTED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMfaBackupCodesRegenerated(MfaBackupCodesRegeneratedEvent event) {
        auditLogService.record(
            AuditEventType.MFA_BACKUP_CODES_REGENERATED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            SecurityUtils.getCurrentSessionHash(),
            null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMfaRecoveryCompleted(MfaRecoveryCompletedEvent event) {
        // 未認証フロー（MFA リカバリー完了はセッション確立前）: session_hash は null のまま
        auditLogService.record(
            AuditEventType.MFA_RECOVERY_COMPLETED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    // ─────────────────────────────────────────────
    // WEBAUTHN
    // ─────────────────────────────────────────────

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWebAuthnRegistered(WebAuthnRegisteredEvent event) {
        auditLogService.record(
            AuditEventType.WEBAUTHN_CREDENTIAL_REGISTERED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            SecurityUtils.getCurrentSessionHash(),
            toJson(Map.of("device_name", event.getDeviceName() != null ? event.getDeviceName() : ""))
        );
    }

    // ─────────────────────────────────────────────
    // ADMIN_ACTION
    // ─────────────────────────────────────────────

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleAccountUnlocked(AccountUnlockedEvent event) {
        auditLogService.record(
            AuditEventType.ACCOUNT_UNLOCKED.name(),
            event.getAdminId(),
            event.getTargetUserId(),
            null,
            null,
            null,
            null,
            SecurityUtils.getCurrentSessionHash(),
            toJson(Map.of("reason", "ADMIN_ACTION"))
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserFrozen(UserFrozenEvent event) {
        auditLogService.record(
            AuditEventType.USER_FROZEN.name(),
            event.getAdminId(),
            event.getTargetUserId(),
            null,
            null,
            null,
            null,
            SecurityUtils.getCurrentSessionHash(),
            toJson(Map.of("reason", event.getReason()))
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserUnfrozen(UserUnfrozenEvent event) {
        auditLogService.record(
            AuditEventType.USER_UNFROZEN.name(),
            event.getAdminId(),
            event.getTargetUserId(),
            null,
            null,
            null,
            null,
            SecurityUtils.getCurrentSessionHash(),
            null
        );
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("監査ログmetadata JSON化失敗: {}", e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────
    // WEBAUTHN (追加分)
    // ─────────────────────────────────────────────

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWebAuthnLogin(WebAuthnLoginEvent event) {
        auditLogService.record(
            AuditEventType.WEBAUTHN_LOGIN.name(),
            event.getUserId(),
            null,
            null,
            null,
            event.getIpAddress(),
            event.getUserAgent(),
            SecurityUtils.getCurrentSessionHash(),
            event.getCredentialId() != null
                ? toJson(Map.of("credential_id", event.getCredentialId()))
                : null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWebAuthnLoginFailed(WebAuthnLoginFailedEvent event) {
        auditLogService.record(
            AuditEventType.WEBAUTHN_LOGIN_FAILED.name(),
            event.getUserId(),
            null,
            null,
            null,
            event.getIpAddress(),
            event.getUserAgent(),
            null,
            event.getCredentialId() != null
                ? toJson(Map.of("credential_id", event.getCredentialId()))
                : null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWebAuthnCredentialRemoved(WebAuthnCredentialRemovedEvent event) {
        auditLogService.record(
            AuditEventType.WEBAUTHN_CREDENTIAL_REMOVED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            SecurityUtils.getCurrentSessionHash(),
            event.getCredentialId() != null
                ? toJson(Map.of("credential_id", event.getCredentialId()))
                : null
        );
    }

    // ─────────────────────────────────────────────
    // TOKEN / DEVICE (追加分)
    // ─────────────────────────────────────────────

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTokenReuseDetected(TokenReuseDetectedEvent event) {
        auditLogService.record(
            AuditEventType.TOKEN_REUSE_DETECTED.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            null,
            event.getTokenId() != null
                ? toJson(Map.of("token_id", event.getTokenId()))
                : null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleDeviceFingerprintMismatch(DeviceFingerprintMismatchEvent event) {
        auditLogService.record(
            AuditEventType.DEVICE_FINGERPRINT_MISMATCH.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            SecurityUtils.getCurrentSessionHash(),
            event.getTokenId() != null
                ? toJson(Map.of("token_id", event.getTokenId()))
                : null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleNewDeviceLogin(NewDeviceLoginEvent event) {
        auditLogService.record(
            AuditEventType.NEW_DEVICE_LOGIN.name(),
            event.getUserId(),
            null,
            null,
            null,
            event.getIpAddress(),
            null,
            SecurityUtils.getCurrentSessionHash(),
            toJson(Map.of(
                "device_fingerprint", event.getDeviceFingerprint() != null ? event.getDeviceFingerprint() : "",
                "device_name", event.getDeviceName() != null ? event.getDeviceName() : ""
            ))
        );
    }

    // ─────────────────────────────────────────────
    // ACCOUNT (追加分)
    // ─────────────────────────────────────────────

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePasswordSetup(PasswordSetupEvent event) {
        auditLogService.record(
            AuditEventType.PASSWORD_SETUP.name(),
            event.getUserId(),
            null,
            null,
            null,
            null,
            null,
            SecurityUtils.getCurrentSessionHash(),
            null
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOAuthUserRegistered(OAuthUserRegisteredEvent event) {
        auditLogService.record(
            AuditEventType.OAUTH_USER_REGISTERED.name(),
            event.getUserId(),
            null,
            null,
            null,
            event.getIpAddress(),
            event.getUserAgent(),
            null,
            event.getProvider() != null
                ? toJson(Map.of("provider", event.getProvider()))
                : null
        );
    }

    // ─────────────────────────────────────────────
    // OAUTH (追加分)
    // ─────────────────────────────────────────────

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOAuthLinkRequested(OAuthLinkRequestedEvent event) {
        auditLogService.record(
            AuditEventType.OAUTH_LINK_REQUESTED.name(),
            event.getUserId(),
            null,
            null,
            null,
            event.getIpAddress(),
            event.getUserAgent(),
            null,
            event.getProvider() != null
                ? toJson(Map.of("provider", event.getProvider()))
                : null
        );
    }

    // ─────────────────────────────────────────────
    // CIRCULATION (F05.2 Phase 11 4-C)
    // ─────────────────────────────────────────────

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleCirculationExportRequested(CirculationExportRequestedEvent event) {
        auditLogService.record(
            AuditEventType.CIRCULATION_EXPORT_REQUESTED.name(),
            event.getActorId(),
            null,
            event.getTeamId(),
            event.getOrganizationId(),
            null,
            null,
            null,
            toJson(Map.of("documentId", event.getDocumentId()))
        );
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleCirculationExportGenerated(CirculationExportGeneratedEvent event) {
        auditLogService.record(
            AuditEventType.CIRCULATION_EXPORT_GENERATED.name(),
            event.getActorId(),
            null,
            event.getTeamId(),
            event.getOrganizationId(),
            null,
            null,
            null,
            toJson(Map.of("documentId", event.getDocumentId()))
        );
    }
}
