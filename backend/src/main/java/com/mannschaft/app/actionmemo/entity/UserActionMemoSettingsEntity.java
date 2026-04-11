package com.mannschaft.app.actionmemo.entity;

import com.mannschaft.app.gdpr.PersonalData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * F02.5 ユーザー別 行動メモ設定エンティティ。
 *
 * <p>PK = user_id（1ユーザー1レコード）。{@link com.mannschaft.app.common.BaseEntity} は継承せず
 * 独自に createdAt / updatedAt を持つ（AUTO_INCREMENT の id は不要）。</p>
 *
 * <p>レコード未作成のユーザーは Service 層で「デフォルト値（mood_enabled = false）」と等価に扱う。</p>
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
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
