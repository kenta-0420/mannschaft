package com.mannschaft.app.moderation.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * モデレーション設定変更履歴エンティティ。設定変更の監査証跡を保持する。
 */
@Entity
@Table(name = "moderation_settings_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ModerationSettingsHistoryEntity extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String settingKey;

    @Column(nullable = false, length = 500)
    private String oldValue;

    @Column(nullable = false, length = 500)
    private String newValue;

    @Column(nullable = false)
    private Long changedBy;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime changedAt = LocalDateTime.now();
}
