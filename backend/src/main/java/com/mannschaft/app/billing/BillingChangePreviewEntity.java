package com.mannschaft.app.billing;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * Billing Center PR6b-1: 一回消費の PLAN 変更 preview（{@code billing_change_previews}）。
 *
 * <p>V196 で新設。PLAN 変更確認画面で提示した見積り（税・金額のスナップショット）を、
 * Checkout 直前に再照合するために短時間だけ保持する表。{@code consumed_at} が非NULLに
 * なった時点で使用済み（同じ preview の使い回しは不可）。</p>
 *
 * <p>{@code chk_bcp_kind CHECK (product_kind = 'PLAN')} により product_kind は事実上 PLAN 固定
 * （ADDON の preview は現状存在しない・DDL 側の列自体は他の billing 表との統一のため VARCHAR(8) の
 * まま残している）。</p>
 *
 * <p>このフェーズでは Entity/Repo 骨格のみ（発行・消費 Service は別部隊）。</p>
 *
 * <p>設計書: docs/features/F20.1_entitlement_billing/05_billing_center.md §5</p>
 */
@Entity
@Table(name = "billing_change_previews")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BillingChangePreviewEntity extends UuidV7Entity {

    /** preview を要求したユーザー。 */
    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    /** 変更対象契約（{@code billing_contracts.id}・同一ドメイン内 FK）。 */
    @Column(name = "contract_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID contractId;

    /** 変更対象契約が属する Stripe Customer（{@code billing_customers.id}・同一ドメイン内 FK）。 */
    @Column(name = "billing_customer_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID billingCustomerId;

    /** テナント（organization_id NULL 許容。USER スコープ契約の preview は NULL）。 */
    @Column(name = "organization_id")
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, length = 8)
    private EntitlementScopeKind scopeKind;

    /** スコープ対象の ID（USER なら user_id、TEAM なら team_id、ORG なら organization_id と同値）。 */
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /**
     * {@code chk_bcp_kind CHECK (product_kind = 'PLAN')} により事実上 PLAN 固定
     * （DDL 既定値も {@code 'PLAN'}）。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "product_kind", nullable = false, length = 8)
    private BillingProductKind productKind;

    /** 変更後 PLAN の product_key。 */
    @Column(name = "product_key", nullable = false, length = 64)
    private String productKey;

    /** 変更前の価格band（{@code billing_price_band_versions.id}・同一ドメイン内 FK）。 */
    @Column(name = "from_price_band_version_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID fromPriceBandVersionId;

    /** 変更後の価格band（{@code billing_price_band_versions.id}・同一ドメイン内 FK）。 */
    @Column(name = "to_price_band_version_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID toPriceBandVersionId;

    /** 見積り時点の人数（ORG/TEAM の人数band課金でのみ使用。個人契約は NULL）。 */
    @Column(name = "member_count")
    private Integer memberCount;

    /** 見積り時点の税スナップショット（JSON・NOT NULL）。 */
    @Column(name = "tax_snapshot", nullable = false, columnDefinition = "JSON")
    private String taxSnapshot;

    /** 見積り時点の金額スナップショット（JSON・NOT NULL）。 */
    @Column(name = "amount_snapshot", nullable = false, columnDefinition = "JSON")
    private String amountSnapshot;

    /** 見積り対象期間の開始（NOT NULL）。 */
    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    /** 見積り対象期間の終了（NOT NULL）。 */
    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    /** 按分計算の基準日時（NOT NULL）。 */
    @Column(name = "proration_at", nullable = false)
    private Instant prorationAt;

    /** 見積り時点の契約 version（Checkout 直前の再照合で楽観ロック的に突き合わせる）。 */
    @Column(name = "contract_version", nullable = false)
    private Long contractVersion;

    /** 見積りリクエストの body ハッシュ（SHA-256 hex, 64桁。使い回し検出用）。 */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    /** preview の有効期限（NOT NULL・10分見積り）。 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 使用済みになった瞬間（NULL のうちは未消費）。 */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** CAS 用 version（Hibernate の暗黙 {@code @Version} にはしない。{@link BillingContractEntity} 前例）。 */
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * 起票した瞬間（{@code docs/architecture/datetime_policy_utc_instant_vs_wallclock.md} §1・§4）。
     * DB 列は {@code DATETIME(6)} で、格納基準は全プロファイル共通の
     * {@code hibernate.jdbc.time_zone=UTC}（番人 {@code TimeZoneStorageBasisGuardTest} が固定）。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 論理削除。削除した瞬間であり {@link Instant}。 */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.version == null) {
            this.version = 0L;
        }
        if (this.productKind == null) {
            this.productKind = BillingProductKind.PLAN;
        }
    }
}
