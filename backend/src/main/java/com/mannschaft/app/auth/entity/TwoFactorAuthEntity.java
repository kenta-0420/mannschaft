package com.mannschaft.app.auth.entity;

import com.mannschaft.app.auth.TwoFactorMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Builder;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 二要素認証エンティティ。TOTP設定とバックアップコードを管理する。
 */
@Entity
@Table(name = "two_factor_auth")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class TwoFactorAuthEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    /**
     * 2FA の主手段（CMP-261012-0910）。既存行は Flyway で TOTP が入る。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private TwoFactorMethod method = TwoFactorMethod.TOTP;

    /**
     * AES-256-GCM 暗号化済み TOTP 秘密鍵。{@link TwoFactorMethod#EMAIL} 方式では NULL。
     */
    @Column(length = 500)
    private String totpSecret;

    @Column(nullable = false, columnDefinition = "JSON")
    private String backupCodes;

    @Column(nullable = false)
    private Boolean isEnabled;

    private LocalDateTime verifiedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 二要素認証を有効化する。
     */
    public void enable() {
        this.isEnabled = true;
        this.verifiedAt = LocalDateTime.now();
    }

    /**
     * 二要素認証を無効化する。
     */
    public void disable() {
        this.isEnabled = false;
    }
}
