package com.mannschaft.app.template.entity;

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
 * チーム有効モジュールエンティティ。チームごとの選択式モジュール有効化状態を管理する。
 */
@Entity
@Table(name = "team_enabled_modules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TeamEnabledModuleEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long moduleId;

    @Column(nullable = false)
    private Boolean isEnabled;

    private LocalDateTime enabledAt;

    private LocalDateTime disabledAt;

    private Long enabledBy;

    private LocalDateTime trialExpiresAt;

    @Column(nullable = false)
    private Boolean trialUsed;
}
