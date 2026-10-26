package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * チーム・組織別カレンダー同期設定エンティティ。
 */
@Entity
@Table(
        name = "user_calendar_sync_settings",
        // ON DUPLICATE KEY UPDATE（UserCalendarSyncSettingRepository.upsert）が依存する一意制約。
        // Flyway DDL（V3.048 の uq_ucss_user_scope）と一致させ、ddl-auto=create の統合テストでも
        // 同一キーが張られるようにする。欠落すると upsert が単なる INSERT に退化し、
        // 同一ユーザー×スコープに重複行が生じて findByUserIdAndScopeTypeAndScopeId が
        // IncorrectResultSizeDataAccessException を投げる（AC-7 回帰の根本原因）。
        uniqueConstraints = @UniqueConstraint(
                name = "uq_ucss_user_scope",
                columnNames = {"user_id", "scope_type", "scope_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
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
