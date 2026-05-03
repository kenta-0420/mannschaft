package com.mannschaft.app.team.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * チームシフト設定エンティティ。チーム単位のシフトリマインド間隔設定を管理する。
 */
@Entity
@Table(name = "team_shift_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamShiftSettingsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false, unique = true)
    private Long teamId;

    @Column(name = "reminder_48h_enabled", nullable = false)
    private boolean reminder48hEnabled = true;

    @Column(name = "reminder_24h_enabled", nullable = false)
    private boolean reminder24hEnabled = true;

    @Column(name = "reminder_12h_enabled", nullable = false)
    private boolean reminder12hEnabled = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * デフォルト設定でエンティティを生成する。
     * 48h・24h は有効、12h は無効がデフォルト。
     */
    public static TeamShiftSettingsEntity createDefault(Long teamId) {
        TeamShiftSettingsEntity e = new TeamShiftSettingsEntity();
        e.teamId = teamId;
        e.reminder48hEnabled = true;
        e.reminder24hEnabled = true;
        e.reminder12hEnabled = false;
        return e;
    }

    /**
     * リマインド設定を更新する。
     */
    public void update(boolean reminder48hEnabled, boolean reminder24hEnabled, boolean reminder12hEnabled) {
        this.reminder48hEnabled = reminder48hEnabled;
        this.reminder24hEnabled = reminder24hEnabled;
        this.reminder12hEnabled = reminder12hEnabled;
    }
}
