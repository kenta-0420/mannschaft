package com.mannschaft.app.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * F20.1 課金・エンタイトルメント基盤: 機能キーの台帳（{@code feature_catalog}）。
 *
 * <p><b>主キーは自然キー {@code feature_key}</b>（UUIDv7 でない）。CLAUDE.md「マスタテーブル例外」に
 * 該当する（全テナント共通の参照データ・書込はシスアド運用のみ。{@code fee_policies} 前例に倣う。
 * 設計書 01 §0・§2.1）。</p>
 *
 * <p>{@code enabled=false} はカタログ非表示＋{@code isEntitled} 常に false（fail-safe）。
 * {@code category=REVENUE} の行は {@code free_for_nonprofit=TRUE} にしてはならない
 * （アプリ層バリデーションで拒否・DB CHECK は運用変更余地を残すため付けない）。</p>
 *
 * <p>このフェーズでは Entity/Repo 骨格のみ（Service/Controller は別部隊）。</p>
 *
 * <p>設計書: docs/features/F20.1_entitlement_billing/01_data_model.md §2.1</p>
 */
@Entity
@Table(name = "feature_catalog")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(of = "featureKey")
public class FeatureCatalogEntity {

    /** PK・自然キー（英小文字ドット区切り。例: {@code reservation.notification_recipients_extended}）。 */
    @Id
    @Column(name = "feature_key", nullable = false, length = 64)
    private String featureKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 8)
    private FeatureCategory category;

    @Column(name = "addon_available", nullable = false)
    private Boolean addonAvailable;

    /** アドオン月額（円）。NULL=未定（実額はベータ終了時決定・機構のみ）。 */
    @Column(name = "addon_price_jpy")
    private Integer addonPriceJpy;

    /** 非営利スコープに無料開放するか（INTERNAL の無料枠の機構・値は運用設定）。 */
    @Column(name = "free_for_nonprofit", nullable = false)
    private Boolean freeForNonprofit;

    @Column(name = "display_name_key", nullable = false, length = 128)
    private String displayNameKey;

    @Column(name = "description_key", nullable = false, length = 128)
    private String descriptionKey;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** false=カタログ非表示＋isEntitled は常に false（fail-safe）。 */
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
        if (this.addonAvailable == null) {
            this.addonAvailable = Boolean.FALSE;
        }
        if (this.freeForNonprofit == null) {
            this.freeForNonprofit = Boolean.FALSE;
        }
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
