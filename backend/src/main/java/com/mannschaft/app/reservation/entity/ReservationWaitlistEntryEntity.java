package com.mannschaft.app.reservation.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.reservation.WaitlistStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * キャンセル待ち（waitlist）エントリ（F03.4.5 §6.1）。
 *
 * <p>満席（FULL）枠に対して会員が登録し、キャンセルで空きに転じた瞬間に一斉通知される。
 * 新規テーブルのため主キーは UUIDv7（アーキ原則6・{@link UuidV7Entity} 継承）。
 * {@code team_id}/{@code user_id} はクロスドメイン参照のため FK なし（原則1）。
 * {@code slot_id} は同一 reservation ドメイン内 FK（ON DELETE CASCADE — 枠が物理削除されたら待ちも消滅が正）。</p>
 *
 * <p>重複登録ガードはアプリ層（{@code existsBySlotIdAndUserIdAndStatus(WAITING)}）で行い、
 * DB UNIQUE は張らない（取消→再登録を恒久に塞がないため・§6.1 設計判断）。</p>
 */
@Entity
@Table(name = "reservation_waitlist_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ReservationWaitlistEntryEntity extends UuidV7Entity {

    /** チームID（teams ドメインへのクロスドメイン参照・FK なし）。 */
    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** 対象枠（同一 reservation ドメイン内 FK・ON DELETE CASCADE）。 */
    @Column(name = "slot_id", nullable = false)
    private Long slotId;

    /** 登録ユーザー（users ドメインへのクロスドメイン参照・FK なし）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 状態（WAITING/CANCELLED/CONVERTED）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WaitlistStatus status;

    /** 最終通知時刻（再通知間隔制御・NULL = 未通知）。 */
    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = WaitlistStatus.WAITING;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 本人取消（WAITING → CANCELLED）。
     */
    public void cancel() {
        this.status = WaitlistStatus.CANCELLED;
    }

    /**
     * 予約成立による消し込み（WAITING → CONVERTED）。
     */
    public void markConverted() {
        this.status = WaitlistStatus.CONVERTED;
    }

    /**
     * 空き通知の記録（再通知抑制の基準となる最終通知時刻を更新する）。
     *
     * @param notifiedAt 通知時刻（注入 Clock 基準）
     */
    public void markNotified(LocalDateTime notifiedAt) {
        this.notifiedAt = notifiedAt;
    }
}
