package com.mannschaft.app.role.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 招待トークンエンティティ。チーム・組織への招待リンクを管理する。
 */
@Entity
@Table(name = "invite_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class InviteTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String token;

    private Long teamId;

    private Long organizationId;

    @Column(nullable = false)
    private Long roleId;

    private LocalDateTime expiresAt;

    private Integer maxUses;

    @Column(nullable = false)
    private Integer usedCount;

    private LocalDateTime revokedAt;

    private Long createdBy;

    /**
     * 承諾型招待の宛先ユーザー（F04.12）。
     * NULL = 従来の共有リンク型（誰でも参加可） / 非 NULL = 宛先付き承諾型（この user のみ承諾可）。
     * users は user ドメインのため FK は張らない（原則1）。
     */
    private Long targetUserId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * トークンを無効化する。
     */
    public void revoke() {
        this.revokedAt = LocalDateTime.now();
    }

    /**
     * 使用回数をインクリメントする。
     */
    public void incrementUsedCount() {
        this.usedCount++;
    }

    /**
     * トークンが有効かどうかを判定する。
     *
     * @return 有効であれば true
     */
    public boolean isValid() {
        if (this.revokedAt != null) {
            return false;
        }
        if (this.expiresAt != null && this.expiresAt.isBefore(LocalDateTime.now())) {
            return false;
        }
        if (this.maxUses != null && this.usedCount >= this.maxUses) {
            return false;
        }
        return true;
    }
}
