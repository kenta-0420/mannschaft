package com.mannschaft.app.template.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * モジュール×レベル別利用可否エンティティ。組織・チーム・個人レベルでの利用可否を管理する。
 */
@Entity
@Table(name = "module_level_availability")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ModuleLevelAvailabilityEntity extends BaseEntity {

    @Column(nullable = false)
    private Long moduleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Level level;

    @Column(nullable = false)
    private Boolean isAvailable;

    @Column(length = 200)
    private String note;

    /**
     * 利用可否を更新する（toBuilder を使わない直接ミューテート）。
     */
    public void applyUpdate(boolean isAvailable) {
        this.isAvailable = isAvailable;
    }

    /**
     * モジュール適用レベル
     */
    public enum Level {
        /** 組織レベル */
        ORGANIZATION,
        /** チームレベル */
        TEAM,
        /** 個人レベル */
        PERSONAL
    }
}
