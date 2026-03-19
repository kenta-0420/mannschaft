package com.mannschaft.app.membership;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * デジタル会員証エンティティ。ユーザーがチーム/組織に所属した際に自動発行される。
 */
@Entity
@Table(name = "member_cards")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MemberCardEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScopeType scopeType;

    private Long scopeId;

    @Column(nullable = false, length = 36)
    private String cardCode;

    @Column(nullable = false, length = 20)
    private String cardNumber;

    @Column(nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardStatus status;

    @Column(nullable = false)
    private LocalDateTime issuedAt;

    private LocalDateTime suspendedAt;

    private LocalDateTime revokedAt;

    private LocalDateTime lastCheckinAt;

    @Column(nullable = false)
    private Integer checkinCount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalSpend;

    @Column(nullable = false, length = 64)
    private String qrSecret;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.issuedAt == null) {
            this.issuedAt = now;
        }
        if (this.status == null) {
            this.status = CardStatus.ACTIVE;
        }
        if (this.checkinCount == null) {
            this.checkinCount = 0;
        }
        if (this.totalSpend == null) {
            this.totalSpend = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 会員証を一時停止する。
     */
    public void suspend() {
        this.status = CardStatus.SUSPENDED;
        this.suspendedAt = LocalDateTime.now();
    }

    /**
     * 一時停止を解除して有効化する。
     */
    public void reactivate() {
        this.status = CardStatus.ACTIVE;
        this.suspendedAt = null;
    }

    /**
     * 会員証を無効化する。
     */
    public void revoke() {
        this.status = CardStatus.REVOKED;
        this.revokedAt = LocalDateTime.now();
    }

    /**
     * QRシークレットを再生成する。
     *
     * @param newSecret 新しいシークレット
     */
    public void regenerateQrSecret(String newSecret) {
        this.qrSecret = newSecret;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
