package com.mannschaft.app.directmail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ダイレクトメール受信者エンティティ。各受信者の配信状態を管理する。
 */
@Entity
@Table(name = "direct_mail_recipients")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class DirectMailRecipientEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long mailLogId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(length = 100)
    private String sesMessageId;

    private LocalDateTime openedAt;

    private LocalDateTime bouncedAt;

    @Column(length = 20)
    private String bounceType;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 送信済みにする。
     */
    public void markSent(String sesMessageId) {
        this.status = "SENT";
        this.sesMessageId = sesMessageId;
    }

    /**
     * 開封記録を設定する。
     */
    public void markOpened() {
        if (this.openedAt == null) {
            this.openedAt = LocalDateTime.now();
        }
    }

    /**
     * バウンスを記録する。
     */
    public void markBounced(String bounceType) {
        this.status = "BOUNCED";
        this.bouncedAt = LocalDateTime.now();
        this.bounceType = bounceType;
    }

    /**
     * 苦情を記録する。
     */
    public void markComplained() {
        this.status = "COMPLAINED";
    }
}
