package com.mannschaft.app.dashboard.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * F22.1: 横スワイプ・ダッシュボードのチーム/組織タグ表示順（ユーザー個人設定）。
 *
 * <p>1 ユーザー × スコープ種別（TEAM / ORGANIZATION）× スコープ ID ごとに最大 1 行。
 * 表示順が保存されていないスコープはサービス層で末尾に補完するため、
 * 全所属スコープ分の行を持つ必要はない（ユーザーが並べ替えたものだけ INSERT）。</p>
 *
 * <p>設計原則:</p>
 * <ul>
 *   <li>原則1: クロスドメイン FK なし（{@code user_id} / {@code scope_id} に FK 制約を張らない）。</li>
 *   <li>原則6: 主キーは UUIDv7（{@link UuidV7Entity} 継承）。</li>
 *   <li>原則7: {@code organization_id} を持たない user_id 単位の個人設定のため
 *       {@code AbstractTenantAwareRepository} は不適用（01_db_design.md §3 判断記録）。</li>
 * </ul>
 *
 * <p>設計書: docs/features/F22.1_swipe_scope_dashboard/01_db_design.md §2.1</p>
 */
@Entity
@Table(name = "dashboard_scope_tab_order")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class DashboardScopeTabOrderEntity extends UuidV7Entity {

    /** users.id（FK 制約なし。整合性はアプリ層で保証）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * タグ種別（{@code TEAM} / {@code ORGANIZATION}）。
     *
     * <p>本テーブルは PERSONAL を扱わない（個人パネルにタグはない）。
     * 文字列で保持し、サービス層で enum バリデーションする。</p>
     */
    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    /** チーム ID または 組織 ID（FK 制約なし）。 */
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /** 表示順（昇順。小さいほど先頭）。 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

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
        this.updatedAt = now;
        if (this.sortOrder == null) {
            this.sortOrder = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
