package com.mannschaft.app.reservation.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.reservation.ApprovalMode;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 週間テンプレートエンティティ（F03.4.2 §3.2）。
 *
 * <p><b>1行 = 1曜日 × 1連続時間帯 × 1ライン（または共通）</b>。「週間テンプレート」は
 * チームのテンプレ行の集合であり、生成時（{@code ReservationSlotGenerationService}）に
 * 30 分セルへ分割される（§5.2）。</p>
 *
 * <p>新規テーブルのため主キーは UUIDv7（アーキ原則6・{@link UuidV7Entity} 継承）。
 * {@code team_id}/{@code created_by}/{@code staff_user_id} はクロスドメイン参照のため FK なし
 * （アーキ原則1）。{@code line_id} は同一 reservation ドメイン内 FK（ON DELETE RESTRICT）。</p>
 *
 * <p>論理削除は持たない（{@code is_active} で無効化・物理削除可 — §3 テーブル一覧）。
 * 物理削除時、生成済み枠は FK {@code ON DELETE SET NULL} で独立して残る。</p>
 */
@Entity
@Table(name = "reservation_slot_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ReservationSlotTemplateEntity extends UuidV7Entity {

    /** チームID（teams ドメインへのクロスドメイン参照・FK なし）。 */
    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** テンプレ名（管理用メモ・任意）。NULL = 曜日＋時間で自動表示。 */
    @Column(length = 100)
    private String name;

    /** 対象ライン。NULL = 共通枠テンプレ（同一ドメイン内 FK・ON DELETE RESTRICT）。 */
    @Column(name = "line_id")
    private Long lineId;

    /** 曜日（正準3文字大文字 MON..SUN。business_hours と完全同一表現 — §3.2）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 3)
    private ReservationDayOfWeek dayOfWeek;

    /** 帯の開始（30分単位: :00 / :30）。 */
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    /** 帯の終了（30分単位・start_time より後）。 */
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "ends_next_day", nullable = false)
    @Builder.Default
    private Boolean endsNextDay = false;

    /** 生成する各セル枠の定員（V140 の capacity へコピー）。ライン軸テンプレは 1 を標準。 */
    @Column(nullable = false)
    @Builder.Default
    private Integer capacity = 1;

    /** 生成枠の担当スタッフ（任意・FKなし=usersドメイン参照）。NULL = 担当なし。 */
    @Column(name = "staff_user_id")
    private Long staffUserId;

    /** 生成枠の title へコピー（任意）。 */
    @Column(length = 200)
    private String title;

    /** 生成枠の price へコピー（表示用・任意）。 */
    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    /** 生成枠の枠単位承認モード上書きへコピー。NULL = チーム既定（親 §3 の解決規則）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_mode", length = 10)
    private ApprovalMode approvalMode;

    /** FALSE のテンプレは生成対象外（既生成枠は不変）。 */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /** 作成者（FKなし・バッチは NULL）。 */
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

    /**
     * この帯が 1 日あたり生成する 30 分セル数（{@code (end - start) / 30}・§4 の {@code cellCount}）。
     */
    public int cellCount() {
        long minutes = endsNextDay != null && endsNextDay
                ? Duration.between(this.startTime, this.endTime).toMinutes() + Duration.ofDays(1).toMinutes()
                : Duration.between(this.startTime, this.endTime).toMinutes();
        return (int) (minutes / 30);
    }

    /** テンプレートを有効化する。 */
    public void activate() {
        this.isActive = true;
    }

    /** テンプレートを無効化する（生成停止。既生成枠は不変）。 */
    public void deactivate() {
        this.isActive = false;
    }

    /** テンプレ名を変更する（部分更新）。 */
    public void changeName(String name) {
        this.name = name;
    }

    /** 対象ラインを変更する（部分更新）。 */
    public void changeLine(Long lineId) {
        this.lineId = lineId;
    }

    /** 対象ラインの指定を解除し、共通枠テンプレへ戻す（§4 {@code clearLineId}）。 */
    public void clearLine() {
        this.lineId = null;
    }

    /** 曜日を変更する（部分更新）。 */
    public void changeDayOfWeek(ReservationDayOfWeek dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    /** 帯の時間を変更する（部分更新・対で更新）。 */
    public void changeTimeRange(LocalTime startTime, LocalTime endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public void changeTimeRange(LocalTime startTime, LocalTime endTime, Boolean endsNextDay) {
        changeTimeRange(startTime, endTime);
        this.endsNextDay = endsNextDay == null ? false : endsNextDay;
    }

    /** 定員を変更する（部分更新）。 */
    public void changeCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    /** 担当スタッフを変更する（部分更新）。 */
    public void changeStaffUser(Long staffUserId) {
        this.staffUserId = staffUserId;
    }

    /** 生成枠タイトルを変更する（部分更新）。 */
    public void changeTitle(String title) {
        this.title = title;
    }

    /** 生成枠価格を変更する（部分更新）。 */
    public void changePrice(BigDecimal price) {
        this.price = price;
    }

    /** 承認モード上書きを変更する（部分更新）。 */
    public void changeApprovalMode(ApprovalMode approvalMode) {
        this.approvalMode = approvalMode;
    }
}
