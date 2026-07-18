package com.mannschaft.app.template.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 組織有効モジュールエンティティ。組織ごとの選択式モジュール有効化状態を管理する。
 * team_enabled_modules と対称的な設計で、組織スコープのモジュール管理を実現する。
 * 中間テーブル（organization_id × module_id の複合ユニーク）のため BaseEntity（BIGINT AUTO_INCREMENT）を継承。
 */
@Entity
@Table(name = "organization_enabled_modules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class OrganizationEnabledModuleEntity extends BaseEntity {

    @Column(nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private Long moduleId;

    @Column(nullable = false)
    private Boolean isEnabled;

    /**
     * グランドファザリング行フラグ。既存テナントへ自動投入した「既得機能」の enable 行を示す。
     * true の行は通常どおり有効（nav 表示・isModuleEnabled 判定は通常扱い）だが、
     * 無料プラン上限（FREE_PLAN_MODULE_LIMIT）のカウントからは除外する。
     * ddl-auto=create のテストで NOT NULL 制約違反を避けるため {@code @Builder.Default} で false を明示。
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isGrandfathered = false;

    private LocalDateTime enabledAt;

    private LocalDateTime disabledAt;

    private Long enabledBy;

    /**
     * モジュールの有効/無効状態を更新する（toBuilder を使わない直接ミューテート）。
     */
    public void applyToggle(boolean enabled, java.time.LocalDateTime enabledAt,
                             java.time.LocalDateTime disabledAt, Long enabledBy) {
        this.isEnabled = enabled;
        this.enabledAt = enabledAt;
        this.disabledAt = disabledAt;
        this.enabledBy = enabledBy;
    }
}
