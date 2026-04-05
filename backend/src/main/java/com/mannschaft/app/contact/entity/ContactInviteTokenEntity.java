package com.mannschaft.app.contact.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * 個人連絡先招待トークンエンティティ。招待URL/QRコード用のトークンを管理する。
 */
@Entity
@Table(name = "contact_invite_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ContactInviteTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** トークン発行者のユーザーID */
    @Column(nullable = false)
    private Long userId;

    /** UUID v4 トークン */
    @Column(nullable = false, length = 36)
    private String token;

    /** 管理用ラベル */
    @Column(length = 50)
    private String label;

    /** 利用回数上限。NULL=無制限 */
    private Integer maxUses;

    /** 利用済み回数 */
    @Column(nullable = false)
    @Builder.Default
    private Integer usedCount = 0;

    /** 有効期限。NULL=無期限 */
    private LocalDateTime expiresAt;

    /** 無効化日時。NULL=有効 */
    private LocalDateTime revokedAt;

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
     * トークンを無効化する。
     */
    public void revoke() {
        this.revokedAt = LocalDateTime.now();
    }

    /**
     * 利用回数をインクリメントする。
     */
    public void incrementUsedCount() {
        this.usedCount++;
    }

    /**
     * トークンが有効かどうかを確認する。
     * 無効化済み・期限切れ・利用回数超過のいずれかの場合は無効。
     */
    public boolean isValid() {
        if (revokedAt != null) return false;
        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) return false;
        if (maxUses != null && usedCount >= maxUses) return false;
        return true;
    }
}
