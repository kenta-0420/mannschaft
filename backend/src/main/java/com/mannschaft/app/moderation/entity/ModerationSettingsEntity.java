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

/**
 * モデレーション設定エンティティ。Key-Value形式の設定を管理する。
 */
@Entity
@Table(name = "moderation_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ModerationSettingsEntity extends BaseEntity {

    @Column(nullable = false, length = 100, unique = true)
    private String settingKey;

    @Column(nullable = false, length = 500)
    private String settingValue;

    @Column(length = 500)
    private String description;

    private Long updatedBy;

    /**
     * 設定値を更新する。
     *
     * @param newValue  新しい値
     * @param updaterId 更新者ID
     */
    public void updateValue(String newValue, Long updaterId) {
        this.settingValue = newValue;
        this.updatedBy = updaterId;
    }
}
