package com.mannschaft.app.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * メニュー×ライン（予約対象）の提供可否エンティティ（F03.4.1 機能E）。
 *
 * <p><b>提供可否のセマンティクス（入力摩擦ゼロ既定・§3）</b>: あるメニューの行が
 * <b>0 件 = 全ライン（active な全予約対象）で提供可</b>（既定・作成直後は 0 件）。
 * 行が 1 件以上 = 列挙されたラインのみ提供可。「全ラインで提供不可」は
 * {@code is_active = FALSE} で表現する（空配列に意味を持たせない）。</p>
 *
 * <p>同一ドメイン（reservation）内の親子のため FK を張る（アーキ原則1・2 に適合）:
 * {@code menu_id} は ON DELETE CASCADE（メニュー物理削除時に孤児行を防ぐ）、
 * {@code line_id} は ON DELETE RESTRICT（ライン論理削除運用の番人。論理削除時は
 * アプリ層で提供可否行を明示削除する — F03.4.2 §5 のライン削除フロー）。</p>
 */
@Entity
@Table(name = "reservation_menu_lines")
@IdClass(ReservationMenuLineId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ReservationMenuLineEntity {

    /** メニューID（FK → reservation_menus.id・ON DELETE CASCADE）。 */
    @Id
    @Column(name = "menu_id", nullable = false)
    private UUID menuId;

    /** ラインID（FK → reservation_lines.id・ON DELETE RESTRICT）。 */
    @Id
    @Column(name = "line_id", nullable = false)
    private Long lineId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
