package com.mannschaft.app.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * F20.1 課金・エンタイトルメント基盤: 人数バンド別単価（{@code plan_price_bands}・機構のみ）。
 *
 * <p>マスタ例外（自然キー複合）。{@code plan_key} への FK は同一ドメイン内のため CASCADE 可。
 * アクティブ人数の正準は {@code memberships} の {@code left_at IS NULL} 行数
 * （{@code MembershipRepository.countActiveDistinctUsersByScope}・設計書 01 §3.4）。
 * バンド単価は全て NULL 可（実額はベータ終了時に実データで決定・機構のみ）。</p>
 *
 * <p>このフェーズでは Entity/Repo 骨格のみ（Service/Controller は別部隊）。</p>
 *
 * <p>設計書: docs/features/F20.1_entitlement_billing/01_data_model.md §2.4</p>
 */
@Entity
@Table(name = "plan_price_bands")
@IdClass(PlanPriceBandId.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder
public class PlanPriceBandEntity {

    @Id
    @Column(name = "plan_key", nullable = false, length = 32)
    private String planKey;

    /** TEAM / ORG（USER は plans.base_monthly_price_jpy を使用しバンド無し）。 */
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 8)
    private PlanPriceBandScopeKind scopeKind;

    /** バンド番号（1〜・昇順）。 */
    @Id
    @Column(name = "band_no", nullable = false)
    private Short bandNo;

    /** アクティブ人数下限（この値以上）。 */
    @Column(name = "min_members", nullable = false)
    private Integer minMembers;

    /** アクティブ人数上限（この値以下）。NULL=無制限（最終バンド）。 */
    @Column(name = "max_members")
    private Integer maxMembers;

    /** 月額（円）。NULL=未定（実額はベータ終了時に実データで決定）。 */
    @Column(name = "monthly_price_jpy")
    private Integer monthlyPriceJpy;

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
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
