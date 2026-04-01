package com.mannschaft.app.auth;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AuditEventType {

    // ─── AUTH ───────────────────────────────────────────────
    LOGIN_SUCCESS(AuditEventCategory.AUTH),
    LOGIN_FAILED(AuditEventCategory.AUTH),
    WEBAUTHN_LOGIN(AuditEventCategory.AUTH),
    WEBAUTHN_LOGIN_FAILED(AuditEventCategory.AUTH),
    WEBAUTHN_CREDENTIAL_REGISTERED(AuditEventCategory.AUTH),
    WEBAUTHN_CREDENTIAL_REMOVED(AuditEventCategory.AUTH),
    LOGOUT(AuditEventCategory.AUTH),
    LOGOUT_SESSION(AuditEventCategory.AUTH),
    LOGOUT_ALL_SESSIONS(AuditEventCategory.AUTH),
    TOKEN_REUSE_DETECTED(AuditEventCategory.AUTH),
    DEVICE_FINGERPRINT_MISMATCH(AuditEventCategory.AUTH),
    NEW_DEVICE_LOGIN(AuditEventCategory.AUTH),

    // ─── ACCOUNT ────────────────────────────────────────────
    USER_REGISTERED(AuditEventCategory.ACCOUNT),
    OAUTH_USER_REGISTERED(AuditEventCategory.ACCOUNT),
    EMAIL_VERIFIED(AuditEventCategory.ACCOUNT),
    PASSWORD_RESET_REQUESTED(AuditEventCategory.ACCOUNT),
    PASSWORD_RESET_COMPLETED(AuditEventCategory.ACCOUNT),
    ACCOUNT_LOCKED(AuditEventCategory.ACCOUNT),
    PASSWORD_CHANGED(AuditEventCategory.ACCOUNT),
    PASSWORD_SETUP(AuditEventCategory.ACCOUNT),
    EMAIL_CHANGE_REQUESTED(AuditEventCategory.ACCOUNT),
    EMAIL_CHANGED(AuditEventCategory.ACCOUNT),
    WITHDRAWAL_REQUESTED(AuditEventCategory.ACCOUNT),
    WITHDRAWAL_CANCELLED(AuditEventCategory.ACCOUNT),
    WITHDRAWAL_COMPLETED(AuditEventCategory.ACCOUNT),
    PENDING_USER_CLEANED_UP(AuditEventCategory.ACCOUNT),

    // ─── OAUTH ──────────────────────────────────────────────
    OAUTH_LINK_REQUESTED(AuditEventCategory.OAUTH),
    OAUTH_LINKED(AuditEventCategory.OAUTH),
    OAUTH_UNLINKED(AuditEventCategory.OAUTH),

    // ─── MFA ────────────────────────────────────────────────
    MFA_ENABLED(AuditEventCategory.MFA),
    MFA_DISABLED(AuditEventCategory.MFA),
    MFA_BACKUP_CODES_REGENERATED(AuditEventCategory.MFA),
    MFA_RECOVERY_REQUESTED(AuditEventCategory.MFA),
    MFA_RECOVERY_COMPLETED(AuditEventCategory.MFA),

    // ─── ADMIN_ACTION ────────────────────────────────────────
    USER_FROZEN(AuditEventCategory.ADMIN_ACTION),
    USER_UNFROZEN(AuditEventCategory.ADMIN_ACTION),
    ACCOUNT_UNLOCKED(AuditEventCategory.ADMIN_ACTION),

    // ─── LIFECYCLE ───────────────────────────────────────────
    USER_ARCHIVED(AuditEventCategory.LIFECYCLE),
    USER_UNARCHIVED(AuditEventCategory.LIFECYCLE),
    TEAM_ARCHIVED(AuditEventCategory.LIFECYCLE),
    TEAM_UNARCHIVED(AuditEventCategory.LIFECYCLE),
    ORGANIZATION_ARCHIVED(AuditEventCategory.LIFECYCLE),
    ORGANIZATION_UNARCHIVED(AuditEventCategory.LIFECYCLE),

    // ─── TEAM (Phase 2+) ─────────────────────────────────────
    TEAM_MEMBER_INVITED(AuditEventCategory.TEAM),
    TEAM_MEMBER_JOINED(AuditEventCategory.TEAM),
    TEAM_MEMBER_ROLE_CHANGED(AuditEventCategory.TEAM),
    TEAM_MEMBER_REMOVED(AuditEventCategory.TEAM),
    TEAM_CREATED(AuditEventCategory.TEAM),
    TEAM_DELETED(AuditEventCategory.TEAM),
    TEAM_MEMBER_BLOCKED(AuditEventCategory.TEAM),
    TEAM_MEMBER_UNBLOCKED(AuditEventCategory.TEAM),

    // ─── ORGANIZATION (Phase 2+) ──────────────────────────────
    ORGANIZATION_CREATED(AuditEventCategory.ORGANIZATION),
    ORGANIZATION_DELETED(AuditEventCategory.ORGANIZATION),
    ORGANIZATION_MEMBER_JOINED(AuditEventCategory.ORGANIZATION),
    ORGANIZATION_MEMBER_ROLE_CHANGED(AuditEventCategory.ORGANIZATION),
    ORGANIZATION_MEMBER_REMOVED(AuditEventCategory.ORGANIZATION),

    // ─── PAYMENT (Phase 3+) ───────────────────────────────────
    PAYMENT_COMPLETED(AuditEventCategory.PAYMENT),
    PAYMENT_REFUNDED(AuditEventCategory.PAYMENT),

    // ─── SCHEDULE (Phase 3+) ──────────────────────────────────
    SCHEDULE_CREATED(AuditEventCategory.SCHEDULE),
    SCHEDULE_UPDATED(AuditEventCategory.SCHEDULE);

    private final AuditEventCategory category;
}
