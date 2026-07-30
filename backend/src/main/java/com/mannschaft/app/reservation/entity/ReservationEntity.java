package com.mannschaft.app.reservation.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.reservation.CancelledBy;
import com.mannschaft.app.reservation.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 予約エンティティ。ユーザーによる予約情報を管理する。
 */
@Entity
@Table(name = "reservations")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ReservationEntity extends BaseEntity {

    @Column(nullable = false)
    private Long reservationSlotId;

    @Column(nullable = false)
    private Long lineId;

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long userId;

    /**
     * 予約グループID（F03.4.3 機能G・案(b) 兄弟行方式）。
     *
     * <p><b>NULL = 単枠予約 = 既存互換</b>。アプリ層で UUIDv7 を採番する論理グループで、
     * 専用親テーブルは持たない（§3.1/§3.2）。同一 group_id の兄弟行は
     * {@code team_id}/{@code user_id}/{@code line_id}/{@code menu_id}/{@code status} が全行同値。</p>
     */
    @Column(name = "group_id")
    private java.util.UUID groupId;

    /**
     * 選択メニューID（F03.4.3・FK → reservation_menus ON DELETE RESTRICT）。
     *
     * <p>NULL = メニュー未使用（単枠・自由グループ）。メニュー名の履歴解決（G-14）は
     * {@code findByIdIncludingDeleted} 経由で削除済みメニューからも行う。</p>
     */
    @Column(name = "menu_id")
    private java.util.UUID menuId;

    /**
     * 定期予約の series ID（F03.4.5 §6.2 W2-5・案(b) 兄弟行方式）。
     *
     * <p><b>NULL = 単発予約 = 既存互換</b>。「毎週繰り返す」で作られた各週の予約行を束ねる
     * アプリ層採番の UUIDv7（専用親テーブルは持たない）。{@code group_id}（同日連続枠の横軸）とは
     * <b>独立直交</b>し併存可能。</p>
     *
     * <p>成立が 1 件だけになった場合（2 週目以降が全てスキップ）は NULL に戻す
     * （1 行だけの series は単発予約と区別する意味がない・AC-5-13）。</p>
     */
    @Column(name = "recurring_series_id")
    private java.util.UUID recurringSeriesId;

    /**
     * グループ代表行フラグ（F03.4.3 §3.2）。
     *
     * <p>単枠予約（group_id NULL）は常に TRUE。グループでは先頭枠（最小 slot_date + start_time）の
     * 行のみ TRUE（不変条件: 同一グループにちょうど 1 行）。一覧・統計の重複計上防止（§5.6）と
     * イベント一本化（§5.5）の基準になる。</p>
     */
    @Column(name = "is_group_primary", nullable = false)
    @Builder.Default
    private Boolean isGroupPrimary = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReservationStatus status = ReservationStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime bookedAt = LocalDateTime.now();

    private LocalDateTime confirmedAt;

    private LocalDateTime cancelledAt;

    @Column(length = 500)
    private String cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private CancelledBy cancelledBy;

    private LocalDateTime completedAt;

    @Column(length = 500)
    private String userNote;

    @Column(length = 500)
    private String adminNote;

    private LocalDateTime deletedAt;

    /**
     * 予約を確定する。
     */
    public void confirm() {
        this.status = ReservationStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    /**
     * 予約をキャンセルする。
     *
     * @param reason      キャンセル理由
     * @param cancelledBy キャンセル実行者
     */
    public void cancel(String reason, CancelledBy cancelledBy) {
        this.status = ReservationStatus.CANCELLED;
        this.cancelReason = reason;
        this.cancelledBy = cancelledBy;
        this.cancelledAt = LocalDateTime.now();
    }

    /**
     * 予約を完了する。
     */
    public void complete() {
        this.status = ReservationStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * ノーショーとしてマークする。
     */
    public void noShow() {
        this.status = ReservationStatus.NO_SHOW;
    }

    /**
     * スロットを変更（リスケジュール）する。
     *
     * @param newSlotId 新しいスロットID
     */
    public void reschedule(Long newSlotId) {
        this.reservationSlotId = newSlotId;
        this.status = ReservationStatus.PENDING;
        this.confirmedAt = null;
    }

    /**
     * 管理者メモを更新する。
     *
     * @param note 管理者メモ
     */
    public void updateAdminNote(String note) {
        this.adminNote = note;
    }

    /**
     * 確定可能かどうかを判定する。
     *
     * @return PENDING ステータスの場合 true
     */
    public boolean isConfirmable() {
        return this.status == ReservationStatus.PENDING;
    }

    /**
     * キャンセル可能かどうかを判定する。
     *
     * @return PENDING または CONFIRMED ステータスの場合 true
     */
    public boolean isCancellable() {
        return this.status == ReservationStatus.PENDING
                || this.status == ReservationStatus.CONFIRMED;
    }

    /**
     * 定期予約の series を解除する（F03.4.5 §6.2・AC-5-13）。
     *
     * <p>「毎週繰り返す」で 2 週目以降が全てスキップされ、成立が起点週の 1 件だけになった場合に呼ぶ。
     * 1 行だけの series は単発予約と区別する意味がないため NULL に戻し、
     * 従来どおりの単発予約として扱えるようにする。</p>
     */
    public void clearRecurringSeries() {
        this.recurringSeriesId = null;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
