package com.mannschaft.app.schedule.entity;

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
 * スケジュール・Googleイベントマッピングエンティティ。
 */
@Entity
@Table(name = "user_schedule_google_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class UserScheduleGoogleEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long scheduleId;

    @Column(nullable = false)
    private String googleEventId;

    @Column(nullable = false)
    private LocalDateTime lastSyncedAt;

    /**
     * 同期日時を現在時刻に更新する。
     */
    public void updateSyncedAt() {
        this.lastSyncedAt = LocalDateTime.now();
    }
}
