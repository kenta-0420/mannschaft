package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * チーム・組織別カレンダー同期設定エンティティ。
 */
@Entity
@Table(name = "user_calendar_sync_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class UserCalendarSyncSettingEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isEnabled = true;

    /**
     * 同期を有効化する。
     */
    public void enable() {
        this.isEnabled = true;
    }

    /**
     * 同期を無効化する。
     */
    public void disable() {
        this.isEnabled = false;
    }
}
