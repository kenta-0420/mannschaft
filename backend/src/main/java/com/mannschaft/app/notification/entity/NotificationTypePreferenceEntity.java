package com.mannschaft.app.notification.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 通知種別設定エンティティ。通知種別単位のON/OFFを管理する。
 */
@Entity
@Table(name = "notification_type_preferences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class NotificationTypePreferenceEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(length = 50)
    private String notificationType;

    @Column(nullable = false)
    @SuperBuilder.Default
    private Boolean isEnabled = true;

    /**
     * 通知種別の有効/無効を更新する。
     *
     * @param enabled 有効にする場合 true
     */
    public void updateEnabled(boolean enabled) {
        this.isEnabled = enabled;
    }
}
