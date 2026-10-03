package com.mannschaft.app.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
 * F20.1 課金・エンタイトルメント基盤: 提示プランのマスタ（{@code plans}）。
 *
 * <p><b>主キーは自然キー {@code plan_key}</b>（{@code FREE}/{@code BASIC}/{@code FULL}・
 * UUIDv7 でない）。CLAUDE.md「マスタテーブル例外」に該当する（設計書 01 §0）。</p>
 *
 * <p>{@code BASIC} は構成・価格が未確定（README §8 R-3）。行は用意するが
 * {@code base_monthly_price_jpy=NULL}。実装ブロックしない（ベータ計測後に運用側で確定）。</p>
 *
 * <p>このフェーズでは Entity/Repo 骨格のみ（Service/Controller は別部隊）。</p>
 *
 * <p>設計書: docs/features/F20.1_entitlement_billing/01_data_model.md §2.2</p>
 */
@Entity
@Table(name = "plans")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(of = "planKey")
public class PlanEntity {

    /** PK・自然キー（{@code FREE} / {@code BASIC} / {@code FULL}）。 */
    @Id
    @Column(name = "plan_key", nullable = false, length = 32)
    private String planKey;

    @Column(name = "display_name_key", nullable = false, length = 128)
    private String displayNameKey;

    @Column(name = "description_key", nullable = false, length = 128)
    private String descriptionKey;

    /** 基準月額（円・USER スコープ/バンド未定義時）。NULL=未定。FULL=2000 想定（実額はベータ終了時決定）。 */
    @Column(name = "base_monthly_price_jpy")
    private Integer baseMonthlyPriceJpy;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** false=新規契約不可（既存契約は維持）。 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

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
        if (this.enabled == null) {
            this.enabled = Boolean.TRUE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
