package com.mannschaft.app.reservation.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 予約通知メール宛先エンティティ（機能D）。
 *
 * <p>チーム単位で登録する「予約通知メール宛先」。メンバーの予約が成立するたびに、
 * ここに登録された任意のメールアドレス（非ユーザー＝店の代表アドレス等でも可）へ
 * 「日時＋メニュー＋予約者名」をメール送信する（{@code ReservationRecipientEmailEventListener}）。</p>
 *
 * <p><b>主キーは UUIDv7</b>（アーキ原則6・新規テーブル）。{@code team_id} / {@code created_by} は
 * teams / users ドメインへのクロスドメイン参照のため FK なし・インデックスのみ（アーキ原則1）。
 * 参照整合性はアプリ層で保証する。</p>
 *
 * <p>論理削除は持たない。宛先は物理削除 or {@code is_enabled=FALSE} で無効化する。
 * {@code UNIQUE(team_id, email)} により同一チーム内でのメール重複を DB レベルでも拒否する
 * （アプリ層でも事前に 409 = RESERVATION_030）。</p>
 */
@Entity
@Table(name = "reservation_notification_recipients")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ReservationNotificationRecipientEntity extends UuidV7Entity {

    /** チームID（teams テーブルへのクロスドメイン参照・FK なし・INDEX）。 */
    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** 通知先メールアドレス（{@code @Email} 検証・非ユーザー可）。 */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /** 宛先ラベル（例:「店代表」「予約担当」）。任意。 */
    @Column(name = "label", length = 100)
    private String label;

    /**
     * 有効/無効。FALSE の宛先には送らない（{@code findByTeamIdAndIsEnabledTrue}）。
     * 件数ゲートは有効・無効を問わず全登録行で数える（§5.D）。
     */
    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private Boolean isEnabled = true;

    /** 登録者 user_id（users テーブルへのクロスドメイン参照・FK なし）。 */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        // 万一 null で構築された場合の最終防御（既定 true）。
        if (this.isEnabled == null) {
            this.isEnabled = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 宛先の可変フィールドを部分更新する（PATCH）。null を渡したフィールドは更新しない。
     *
     * @param label     ラベル（null の場合は据え置き）
     * @param isEnabled 有効フラグ（null の場合は据え置き）
     */
    public void updateRecipient(String label, Boolean isEnabled) {
        if (label != null) {
            this.label = label;
        }
        if (isEnabled != null) {
            this.isEnabled = isEnabled;
        }
    }
}
