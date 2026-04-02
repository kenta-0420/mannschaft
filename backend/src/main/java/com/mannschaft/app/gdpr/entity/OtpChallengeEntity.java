package com.mannschaft.app.gdpr.entity;

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
 * OTPチャレンジエンティティ。
 * GDPR退会フローにおけるOTP認証チャレンジを管理する。
 */
@Entity
@Table(name = "otp_challenges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class OtpChallengeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 30)
    private String purpose;

    @Column(nullable = false, length = 200)
    private String codeHash;

    @Column(nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    private LocalDateTime lockedUntil;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime usedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * OTPが期限切れかどうかを返す。
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }

    /**
     * OTPがロック中かどうかを返す。
     */
    public boolean isLocked() {
        return this.lockedUntil != null && LocalDateTime.now().isBefore(this.lockedUntil);
    }

    /**
     * 試行回数をインクリメントする。
     */
    public void incrementAttempt() {
        this.attemptCount++;
    }

    /**
     * 指定分数だけロックする。
     */
    public void lockFor(int minutes) {
        this.lockedUntil = LocalDateTime.now().plusMinutes(minutes);
    }

    /**
     * OTPを使用済みにする。
     */
    public void markUsed() {
        this.usedAt = LocalDateTime.now();
    }
}
