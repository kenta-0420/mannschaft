package com.mannschaft.app.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * WebAuthn資格情報エンティティ。パスキー・FIDO2認証情報を管理する。
 */
@Entity
@Table(name = "webauthn_credentials")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class WebAuthnCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 500)
    private String credentialId;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String publicKey;

    @Column(nullable = false)
    private Long signCount;

    @Column(length = 100)
    private String deviceName;

    @Column(length = 36)
    private String aaguid;

    private LocalDateTime lastUsedAt;

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
     * 署名カウンタをインクリメントする。
     */
    public void incrementSignCount() {
        this.signCount++;
    }

    /**
     * 最終使用日時を更新する。
     */
    public void updateLastUsedAt() {
        this.lastUsedAt = LocalDateTime.now();
    }
}
