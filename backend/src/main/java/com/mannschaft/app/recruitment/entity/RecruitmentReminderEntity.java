package com.mannschaft.app.recruitment.entity;

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
 * F03.11 募集型予約: リマインダーエンティティ。
 * 確定参加者へのリマインド通知の設定・送信状態を管理する (Phase 2)。
 * 設計書 §3.8 recruitment_reminders テーブル参照。
 */
@Entity
@Table(name = "recruitment_reminders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class RecruitmentReminderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long listingId;

    @Column(nullable = false)
    private Long participantId;

    /** リマインド送信予定日時 (UTC)。start_at - 24h */
    @Column(nullable = false)
    private LocalDateTime remindAt;

    /** 送信完了日時。null = 未送信 */
    private LocalDateTime sentAt;

    /** 送信した通知のID (ON DELETE SET NULL のため nullable) */
    private Long notificationId;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 送信済みとしてマークする。
     *
     * @param notificationId 送信した通知のID
     */
    public void markSent(Long notificationId) {
        this.sentAt = LocalDateTime.now();
        this.notificationId = notificationId;
    }
}
