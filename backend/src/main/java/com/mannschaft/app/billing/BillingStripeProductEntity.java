package com.mannschaft.app.billing;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 決定9改訂（第5版・重大2対応）: {@code productKind+productKey+stripeTaxCode} ごとに解決した
 * Stripe Product ref の永続マッピング（{@code billing_stripe_products}）。
 *
 * <p>試練隊（第1陣）が追加する migration（決定5・3本目）が対象テーブルを作る。本エンティティは
 * 試練隊（第2陣）が E群・F群（AC-83/84/88a/88b/88c）の red テストのために新設した契約であり、
 * migration 未適用の環境では実データアクセスができない（Mockito 単体試練はDBに触れないため
 * 影響しない。IT で使う場合は migration 適用後に限る）。</p>
 *
 * <p>{@code stripeTaxCode} が null の組を一意制約で守るため、DB 側は
 * {@code stripe_tax_code_norm GENERATED ALWAYS AS (COALESCE(stripe_tax_code, ''))} を持つ想定
 * （決定9改訂）。JPA からは {@code stripeTaxCode} のみを読み書きし、生成列は Hibernate 管理外とする。</p>
 */
@Entity
@Table(name = "billing_stripe_products",
        uniqueConstraints = @UniqueConstraint(name = "uk_bsp_kind_key_tax",
                columnNames = {"product_kind", "product_key", "stripe_tax_code_norm"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(callSuper = true)
public class BillingStripeProductEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "product_kind", nullable = false, length = 16)
    private BillingProductKind productKind;

    @Column(name = "product_key", nullable = false, length = 64)
    private String productKey;

    @Column(name = "stripe_tax_code", length = 64)
    private String stripeTaxCode;

    /**
     * {@code stripeTaxCode} が null の組を一意制約で扱うための正規化生成列。Hibernate の
     * {@code ddl-auto=create}（test profile）でも実 DB（Flyway migration）でも同じ
     * {@code GENERATED ALWAYS AS (...) STORED} 定義を columnDefinition にそのまま渡すことで
     * 両方の schema 生成経路に同一の生成列を持たせる。アプリからは読み取り専用。
     */
    @Column(name = "stripe_tax_code_norm", insertable = false, updatable = false,
            columnDefinition = "VARCHAR(64) GENERATED ALWAYS AS (COALESCE(stripe_tax_code, '')) STORED")
    private String stripeTaxCodeNorm;

    @Column(name = "stripe_product_id", nullable = false, length = 255)
    private String stripeProductId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public BillingStripeProductEntity(
            BillingProductKind productKind, String productKey, String stripeTaxCode, String stripeProductId) {
        this.productKind = productKind;
        this.productKey = productKey;
        this.stripeTaxCode = stripeTaxCode;
        this.stripeProductId = stripeProductId;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
