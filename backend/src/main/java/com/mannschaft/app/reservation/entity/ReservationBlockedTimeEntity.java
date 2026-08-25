package com.mannschaft.app.reservation.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.reservation.ReservationBlockedResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 予約ブロック時間エンティティ。チームの臨時休業・休憩時間・予約不可枠を管理する。
 *
 * <p>機能B（§3.B）で対象軸カラム（{@link #resourceType} / {@link #resourceId}）を追加し、
 * TEAM 全体だけでなく特定スタッフ単位の予約不可枠を表現できるよう拡張した。
 * 既存行（ALTER 前データ）は DB デフォルト {@code 'TEAM'} / {@code NULL} にフォールバックし
 * 全 slot 対象として判定される（後方互換）。</p>
 */
@Entity
@Table(name = "reservation_blocked_times")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ReservationBlockedTimeEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private LocalDate blockedDate;

    private LocalTime startTime;

    private LocalTime endTime;

    @Column(name = "ends_next_day", nullable = false)
    @lombok.Builder.Default
    private Boolean endsNextDay = false;

    @Column(length = 200)
    private String reason;

    /**
     * 機能B: 予約不可枠の対象軸。{@code TEAM}（全 slot）/ {@code STAFF}（{@link #resourceId} の
     * 担当スタッフの slot のみ）。DB は {@code NOT NULL DEFAULT 'TEAM'}。
     * JPA 経路の新規保存では Service 層で必ず正規化してセットする。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 20)
    private ReservationBlockedResourceType resourceType;

    /**
     * 機能B: {@code resourceType='STAFF'} のとき対象スタッフの {@code staff_user_id}
     * （users ドメイン参照・クロスドメインFKなし）。{@code TEAM} のときは {@code null}。
     */
    @Column(name = "resource_id")
    private Long resourceId;

    private Long createdBy;

    /**
     * ブロック時間を更新する（機能B: 対象軸込み）。
     *
     * @param blockedDate  ブロック日
     * @param startTime    開始時刻
     * @param endTime      終了時刻
     * @param reason       理由
     * @param resourceType 対象軸（正規化済み・非 null）
     * @param resourceId   STAFF 時の対象スタッフ user_id（TEAM 時は null）
     */
    public void update(LocalDate blockedDate, LocalTime startTime, LocalTime endTime, String reason,
                       ReservationBlockedResourceType resourceType, Long resourceId) {
        this.blockedDate = blockedDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.reason = reason;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public void update(LocalDate blockedDate, LocalTime startTime, LocalTime endTime, String reason,
                       ReservationBlockedResourceType resourceType, Long resourceId, Boolean endsNextDay) {
        update(blockedDate, startTime, endTime, reason, resourceType, resourceId);
        this.endsNextDay = endsNextDay == null ? false : endsNextDay;
    }
}
