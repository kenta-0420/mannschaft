package com.mannschaft.app.todo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * マイルストーン（中間目標）エンティティ。プロジェクトをフェーズ分割する。
 *
 * <p>F02.7 にて「関所（ゲート）」機能を拡張。前マイルストーンが未完了のあいだ後続を
 * ロックし、紐付く TODO を操作不能にする。達成判定モードは AUTO / MANUAL の2種。</p>
 */
@Entity
@Table(name = "project_milestones")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ProjectMilestoneEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 200)
    private String title;

    private LocalDate dueDate;

    @Column(nullable = false)
    private Short sortOrder;

    @Column(nullable = false)
    private Boolean isCompleted;

    private LocalDateTime completedAt;

    /** 達成率（%。紐付く TODO の完了率から算出して非正規化保存） */
    @Column(name = "progress_rate", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal progressRate = BigDecimal.ZERO;

    /** ロック状態フラグ。前マイルストーンが未完了の場合 TRUE */
    @Column(name = "is_locked", nullable = false)
    @Builder.Default
    private Boolean isLocked = false;

    /** ロック原因の前マイルストーン ID（FK → project_milestones。ON DELETE SET NULL） */
    @Column(name = "locked_by_milestone_id")
    private Long lockedByMilestoneId;

    /** 完了判定モード。AUTO / MANUAL（Enum 化はアプリ層後続 Phase で実施） */
    @Column(name = "completion_mode", nullable = false, length = 16)
    @Builder.Default
    private String completionMode = "AUTO";

    /** ロック開始日時（監査用） */
    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    /** アンロック日時（監査用） */
    @Column(name = "unlocked_at")
    private LocalDateTime unlockedAt;

    /** 強制アンロック済みフラグ。TRUE の場合、前マイルストーンが未完了に戻っても再ロックしない（冪等性保証） */
    @Column(name = "force_unlocked", nullable = false)
    @Builder.Default
    private Boolean forceUnlocked = false;

    /** 楽観的ロック用バージョン（JPA @Version）。TODO ステータス変更と並行する状態更新の競合制御に使用 */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.sortOrder == null) {
            this.sortOrder = (short) 0;
        }
        if (this.isCompleted == null) {
            this.isCompleted = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * マイルストーンを完了にする。
     */
    public void complete() {
        this.isCompleted = true;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * マイルストーンを未完了に戻す。
     */
    public void reopen() {
        this.isCompleted = false;
        this.completedAt = null;
    }

    /**
     * 前マイルストーンによりロックする。
     *
     * @param precedingMilestoneId ロック原因となる前マイルストーン ID
     */
    public void lockByMilestone(Long precedingMilestoneId) {
        this.isLocked = true;
        this.lockedByMilestoneId = precedingMilestoneId;
        this.lockedAt = LocalDateTime.now();
    }

    /**
     * 前マイルストーン達成による自動アンロック。
     */
    public void unlock() {
        this.isLocked = false;
        this.lockedByMilestoneId = null;
        this.unlockedAt = LocalDateTime.now();
    }

    /**
     * ADMIN による強制アンロック（冪等性保証のため force_unlocked = true）。
     */
    public void forceUnlock() {
        this.isLocked = false;
        this.lockedByMilestoneId = null;
        this.forceUnlocked = true;
        this.unlockedAt = LocalDateTime.now();
    }

    /**
     * TODO の完了数と総数から達成率を更新する。
     *
     * @param total     総 TODO 数（論理削除を除く）
     * @param completed 完了 TODO 数
     */
    public void updateProgressRate(long total, long completed) {
        if (total == 0) {
            this.progressRate = BigDecimal.ZERO;
            return;
        }
        this.progressRate = BigDecimal.valueOf(completed * 100.0 / total)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
