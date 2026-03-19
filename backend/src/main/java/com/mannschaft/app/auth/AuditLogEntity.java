package com.mannschaft.app.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 監査ログエンティティ。ユーザー操作の監査証跡を記録する。
 */
@Entity
@Table(name = "audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AuditEventType eventType;

    @Column(length = 50)
    private String targetType;

    private Long targetId;

    @Column(length = 45)
    private String ipAddress;

    @Column(length = 500)
    private String userAgent;

    @Column(columnDefinition = "JSON")
    private String details;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 監査イベント種別
     */
    public enum AuditEventType {
        USER_REGISTERED,
        LOGIN_SUCCESS,
        LOGIN_FAILED,
        LOGOUT,
        PASSWORD_CHANGED,
        EMAIL_CHANGED,
        MFA_ENABLED,
        MFA_DISABLED,
        WITHDRAWAL_REQUESTED,
        WITHDRAWAL_CANCELLED,
        USER_FROZEN,
        USER_UNFROZEN,
        USER_ARCHIVED,
        USER_UNARCHIVED
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
