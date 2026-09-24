package com.mannschaft.app.reservation.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.reservation.ReservationDayOfWeek;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.time.LocalTime;

/**
 * 定期予約不可枠エンティティ（F03.4.5 §4 W2-2）。
 *
 * <p>「毎週火曜19-20時は研修」のような週次繰り返しの予約不可を1回の登録で恒久化する。
 * 新規テーブルのため主キーは UUIDv7（アーキ原則6・{@link UuidV7Entity} 継承）。
 * {@code team_id}/{@code created_by} はクロスドメイン参照のため FK なし（アーキ原則1）。
 * {@code line_id} は同一 reservation ドメイン内 FK（ON DELETE RESTRICT）。</p>
 *
 * <p>enforcement は {@code ReservationUnavailabilityChecker} の runtime overlap 統合（§4.2）。
 * 生成はスキップせず、表示/予約時に runtime で判定する（機能B と同一方針）。</p>
 */
@Entity
@Table(name = "reservation_recurring_blocked_times")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ReservationRecurringBlockedTimeEntity extends UuidV7Entity {

    /** チームID（teams ドメインへのクロスドメイン参照・FK なし）。 */
    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** 対象ライン。NULL = チーム全体（同一ドメイン内 FK・ON DELETE RESTRICT）。 */
    @Column(name = "line_id")
    private Long lineId;

    /** 曜日（正準3文字大文字 MON..SUN・§4.1）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 3)
    private ReservationDayOfWeek dayOfWeek;

    /** 開始（30分単位）。全日型は許可しない（§4.3）。 */
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    /** 終了（30分単位・start より後）。 */
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "ends_next_day", nullable = false)
    @Builder.Default
    private Boolean endsNextDay = false;

    /** 事由ラベル（必須・§4.1）。 */
    @Column(length = 100, nullable = false)
    private String reason;

    /** TRUE = 会員のマトリックス該当セルに reason を表示。FALSE = 従来どおり UNAVAILABLE 表示のみ。 */
    @Column(name = "is_public", nullable = false)
    @Builder.Default
    private Boolean isPublic = false;

    /** FALSE は判定対象外（一時停止）。 */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /** 作成者（FKなし）。 */
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
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 会員に事由ラベルを見せるか（{@code isPublic} の null 安全アクセサ）。 */
    public boolean isPublicRule() {
        return Boolean.TRUE.equals(this.isPublic);
    }

    /** 判定対象かどうか（{@code isActive} の null 安全アクセサ）。 */
    public boolean isActiveRule() {
        return Boolean.TRUE.equals(this.isActive);
    }

    /** 対象ラインを変更する（部分更新）。 */
    public void changeLine(Long lineId) {
        this.lineId = lineId;
    }

    /** 対象ラインの指定を解除し、チーム全体へ戻す（{@code clearLineId}）。 */
    public void clearLine() {
        this.lineId = null;
    }

    /** 曜日を変更する（部分更新）。 */
    public void changeDayOfWeek(ReservationDayOfWeek dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    /** 時間帯を変更する（部分更新・対で更新）。 */
    public void changeTimeRange(LocalTime startTime, LocalTime endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public void changeTimeRange(LocalTime startTime, LocalTime endTime, Boolean endsNextDay) {
        changeTimeRange(startTime, endTime);
        this.endsNextDay = endsNextDay == null ? false : endsNextDay;
    }

    /** 事由ラベルを変更する（部分更新）。 */
    public void changeReason(String reason) {
        this.reason = reason;
    }

    /** 公開可否を変更する（部分更新）。 */
    public void changeIsPublic(Boolean isPublic) {
        this.isPublic = isPublic;
    }

    /** 有効化する。 */
    public void activate() {
        this.isActive = true;
    }

    /** 無効化する（判定対象外へ・一時停止）。 */
    public void deactivate() {
        this.isActive = false;
    }
}
