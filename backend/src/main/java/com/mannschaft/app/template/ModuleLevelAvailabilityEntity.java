package com.mannschaft.app.template;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * モジュール×レベル別利用可否エンティティ。組織・チーム・個人レベルでの利用可否を管理する。
 */
@Entity
@Table(name = "module_level_availability")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
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
