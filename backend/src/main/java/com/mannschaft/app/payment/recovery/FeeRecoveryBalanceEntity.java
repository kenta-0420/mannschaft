package com.mannschaft.app.payment.recovery;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F22.1 謝礼決済 §6.3: ModeB 返金で Mannschaft が一時負担した Stripe 実手数料の未回収残高。
 *
 * <p>ModeB 返金（02_api_design.md §6.1 / §6.3）では、Mannschaft が支払者へ grossRefund を満額返金し
 * {@code refund_application_fee:true} で application_fee を返金するため、Stripe 実手数料
 * {@code (grossRefund − R)} を Mannschaft が一時負担する。この未回収額を payee（受領者の
 * Stripe Connect アカウント）×通貨ごとに 1 行で積み上げ、後続の謝礼決済 fee と相殺して
 * 自動回収（{@code LedgerEntryType.RECOVERY}）する。</p>
 *
 * <p>設計原則:</p>
 * <ul>
 *   <li>原則1: {@code connect_account_id} は論理参照（FK なし）。整合性はアプリ層で保証。</li>
 *   <li>原則6: 主キーは UUIDv7（{@link UuidV7Entity} 継承）。</li>
 *   <li>原則7: {@code organization_id} を持つテナントスコープ
 *       （{@code AbstractTenantAwareRepository} 適用）。</li>
 *   <li>{@code deletedAt}: 残高表は payee×currency で物理 1 行だが、TenantAware 基底メソッドが
 *       {@code deletedAt} を要求するため列を保持する（連結口座切離し時の残高リセット余地も兼ねる）。</li>
 * </ul>
 *
 * <p>設計書: docs/features/F22.1_market/payment/02_api_design.md §6.1-6.3</p>
 */
@Entity
@Table(name = "fee_recovery_balances")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class FeeRecoveryBalanceEntity extends UuidV7Entity {

    /** connect_accounts.id（論理参照・FK 制約なし）。 */
    @Column(name = "connect_account_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID connectAccountId;

    /** テナント絞り込み用（シャードキー候補）。NULL 許容。 */
    @Column(name = "organization_id")
    private Long organizationId;

    /**
     * 未回収残高（minor 単位）。
     * 通常は非負だが将来の符号反転（過回収/調整）に備え符号付き {@code Long}（BIGINT）。
     */
    @Column(name = "outstanding_amount", nullable = false)
    @Builder.Default
    private Long outstandingAmount = 0L;

    /** 通貨（minor 単位の母数）。既定 {@code jpy}。 */
    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "jpy";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 論理削除（連結口座切離し時の残高リセット用）。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.outstandingAmount == null) {
            this.outstandingAmount = 0L;
        }
        if (this.currency == null) {
            this.currency = "jpy";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
