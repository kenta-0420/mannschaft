package com.mannschaft.app.quickmemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.time.LocalTime;

/**
 * ユーザーごとのポイっとメモ設定エンティティ。
 * リマインドのデフォルト値（3枠）を保持する。PK = user_id。
 */
@Entity
@Table(name = "user_quick_memo_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class UserQuickMemoSettingsEntity {

    /** PK = user_id（AUTO_INCREMENT なし） */
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "reminder_enabled", nullable = false)
    @Builder.Default
    private Boolean reminderEnabled = false;

    /** 1枠目: 何日後か（1-90） */
    @Column(name = "default_offset_1_days")
    private Integer defaultOffset1Days;

    /** 1枠目: 時刻（HH:00 or HH:30） */
    @Column(name = "default_time_1")
    private LocalTime defaultTime1;

    /** 2枠目: 何日後か（1-90） */
    @Column(name = "default_offset_2_days")
    private Integer defaultOffset2Days;

    /** 2枠目: 時刻（HH:00 or HH:30） */
    @Column(name = "default_time_2")
    private LocalTime defaultTime2;

    /** 3枠目: 何日後か（1-90） */
    @Column(name = "default_offset_3_days")
    private Integer defaultOffset3Days;

    /** 3枠目: 時刻（HH:00 or HH:30） */
    @Column(name = "default_time_3")
    private LocalTime defaultTime3;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.reminderEnabled == null) {
            this.reminderEnabled = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
