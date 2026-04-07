package com.mannschaft.app.quickmemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 音声入力同意履歴エンティティ（GDPR 同意証跡）。
 * localStorage を信頼せず、サーバー側で同意状態を管理する。
 */
@Entity
@Table(name = "user_voice_input_consents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class UserVoiceInputConsentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "consented_at", nullable = false, updatable = false)
    private LocalDateTime consentedAt;

    /** NULL = 有効、非NULL = 取消済み */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** IPv4 or IPv6 */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @PrePersist
    protected void onCreate() {
        if (this.consentedAt == null) {
            this.consentedAt = LocalDateTime.now();
        }
    }

    public boolean isActive() {
        return this.revokedAt == null;
    }

    public void revoke() {
        this.revokedAt = LocalDateTime.now();
    }
}
