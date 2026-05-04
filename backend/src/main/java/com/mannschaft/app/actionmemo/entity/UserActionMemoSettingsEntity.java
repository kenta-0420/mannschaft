package com.mannschaft.app.actionmemo.entity;

import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import com.mannschaft.app.gdpr.PersonalData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * F02.5 ユーザー別 行動メモ設定エンティティ。
 *
 * <p>PK = user_id（1ユーザー1レコード）。{@link com.mannschaft.app.common.BaseEntity} は継承せず
 * 独自に createdAt / updatedAt を持つ（AUTO_INCREMENT の id は不要）。</p>
 *
 * <p>レコード未作成のユーザーは Service 層で「デフォルト値（mood_enabled = false）」と等価に扱う。</p>
 *
 * <p><b>Phase 3 追加フィールド</b>: default_post_team_id / default_category。</p>
 * <p><b>Phase 4-β 追加フィールド</b>: reminder_enabled / reminder_time。</p>
 */
@PersonalData(category = "action_memos")
@Entity
@Table(name = "user_action_memo_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class UserActionMemoSettingsEntity {

    @Id
    private Long userId;

    @Setter
    @Column(nullable = false)
    @Builder.Default
    private Boolean moodEnabled = false;

    /**
     * Phase 3: デフォルト投稿先チームID。NULL = 未設定。
     * FK → teams.id（ON DELETE SET NULL）。
     * チームから脱退した場合に自動 NULL 化される。
     */
    @Setter
    @Column(name = "default_post_team_id")
    private Long defaultPostTeamId;

    /**
     * Phase 3: メモ作成時のデフォルトカテゴリ。
     * 未設定時は PRIVATE。
     */
    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "default_category", nullable = false, length = 16)
    @Builder.Default
    private ActionMemoCategory defaultCategory = ActionMemoCategory.PRIVATE;

    /**
     * Phase 4-β: 毎日リマインド機能の有効/無効。
     * true の場合、reminderTime に設定した時刻に通知を送る（バッチは F04.3 後に実装）。
     */
    @Setter
    @Column(name = "reminder_enabled", nullable = false)
    @Builder.Default
    private Boolean reminderEnabled = false;

    /**
     * Phase 4-β: リマインド通知時刻（HH:mm）。
     * reminderEnabled = true の場合のみ有効。NULL = 未設定。
     */
    @Setter
    @Column(name = "reminder_time")
    private LocalTime reminderTime;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.moodEnabled == null) {
            this.moodEnabled = false;
        }
        if (this.defaultCategory == null) {
            this.defaultCategory = ActionMemoCategory.PRIVATE;
        }
        if (this.reminderEnabled == null) {
            this.reminderEnabled = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
