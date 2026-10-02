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
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 予約メニューエンティティ（F03.4.1 機能E）。
 *
 * <p>チームが提供するサービスメニュー（例:「カット 60分」「整体 90分」）のマスタ。
 * 所要時間（30分の倍数・30〜480）を持ち、F03.4.3 の予約グループが必要枠数
 * （{@code duration_minutes / 30}）を自動確保する起点となる。料金は<b>表示のみ</b>（決済しない）。</p>
 *
 * <p><b>主キーは UUIDv7</b>（アーキ原則6・新規テーブル）。{@code team_id} / {@code created_by} は
 * teams / users ドメインへのクロスドメイン参照のため FK なし・インデックスのみ（アーキ原則1）。
 * 論理削除（{@code deleted_at}）を持ち、削除済みメニューは新規予約の起点にできないが、
 * 既存予約グループからの名前解決用に行は物理削除しない（F03.4.1 §3 備考）。</p>
 */
@Entity
@Table(name = "reservation_menus")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ReservationMenuEntity extends UuidV7Entity {

    /** 30分セル1枠あたりの分数（必要枠数 = durationMinutes / 30 の導出に使用）。 */
    public static final int SLOT_UNIT_MINUTES = 30;

    /** チームID（teams テーブルへのクロスドメイン参照・FK なし・INDEX）。 */
    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** メニュー名（例:「カット」「整体60分コース」）。 */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 所要時間（分）。30の倍数・30〜480（Service 一次検証＋DB CHECK 最終防御）。 */
    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    /** 表示用料金（決済しない）。NULL = 料金表示なし。 */
    @Column(name = "price", precision = 10, scale = 2)
    private BigDecimal price;

    /** メニュー説明（会員向け表示）。 */
    @Column(name = "description", length = 500)
    private String description;

    /** 表示順（1〜20・チーム内。Service 層で範囲検証）。 */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 1;

    /** 有効/無効。FALSE は会員向け一覧・予約起点から除外（既存予約は影響なし）。 */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /** 作成者 user_id（users テーブルへのクロスドメイン参照・FK なし）。 */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 論理削除日時（{@code @SQLRestriction} で通常クエリから除外）。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.displayOrder == null) {
            this.displayOrder = 1;
        }
        if (this.isActive == null) {
            this.isActive = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 必要枠数（30分セル枠の個数）を返す。BE 導出（FE で割り算を再実装させない・F03.4.1 §4）。
     *
     * @return {@code durationMinutes / 30}
     */
    public int getRequiredSlotCount() {
        return durationMinutes / SLOT_UNIT_MINUTES;
    }

    /** メニュー名を変更する。 */
    public void changeName(String name) {
        this.name = name;
    }

    /** 所要時間を変更する（検証は Service 層。既存予約グループの枠数は不変=遡及なし原則）。 */
    public void changeDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    /** 表示用料金を変更する。 */
    public void changePrice(BigDecimal price) {
        this.price = price;
    }

    /** 表示用料金を null（料金非表示）へ戻す（PATCH {@code clearPrice=true}）。 */
    public void clearPrice() {
        this.price = null;
    }

    /** 説明文を変更する。 */
    public void changeDescription(String description) {
        this.description = description;
    }

    /** 表示順を変更する。 */
    public void changeDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    /** メニューを有効化する。 */
    public void activate() {
        this.isActive = true;
    }

    /** メニューを無効化する（会員向け一覧・予約起点から除外）。 */
    public void deactivate() {
        this.isActive = false;
    }

    /** 論理削除を行う（既存予約グループからの名前解決用に行は残す）。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
