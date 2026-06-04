package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.payment.connect.ScopeKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
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
 * F22.1 謝礼決済: エスクロー取引（PaymentIntent 1:1）。
 *
 * <p>与信→capture→払出/返金の状態を管理する。{@code payee_connect_account_id} は
 * {@code connect_accounts.id} への論理参照（FKなし＝台帳不変性優先・設計書 §3.2 注記）。
 * {@code source_id}/{@code payer_scope_id} 等はクロスドメイン論理参照（FKなし）。</p>
 *
 * <p>監査証跡として物理保持するため論理削除カラム（deleted_at）を持たない（設計書 §3.2）。
 * このため本リポジトリは {@code AbstractTenantAwareRepository}（deleted_at 前提）を継承できず、
 * テナント絞り込みは {@code organization_id} ベースの derived finder で個別実装する。</p>
 *
 * <p>このフェーズでは Entity/Repo 骨格のみ（Service は次陣）。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/01_data_model.md §3.2</p>
 */
@Entity
@Table(name = "escrow_transactions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class EscrowTransactionEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 12)
    private EscrowSourceKind sourceKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_mode", nullable = false, length = 10)
    private EscrowCaptureMode captureMode;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "source_participant_id")
    private Long sourceParticipantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payer_scope_kind", nullable = false, length = 8)
    private ScopeKind payerScopeKind;

    @Column(name = "payer_scope_id", nullable = false)
    private Long payerScopeId;

    @Column(name = "payer_stripe_customer_id", length = 32)
    private String payerStripeCustomerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payee_kind", nullable = false, length = 8)
    private ScopeKind payeeKind;

    @Column(name = "payee_connect_account_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID payeeConnectAccountId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "stripe_payment_intent_id", length = 32)
    private String stripePaymentIntentId;

    /** 額面（受取側が設定した謝礼/会費の元値・円整数）。amount = faceAmount + round(faceAmount × 0.025)。 */
    @Column(name = "face_amount", nullable = false)
    private Long faceAmount;

    /** 課金額（支払者への実請求額＝額面+2.5%上乗せ・円整数）。Stripe へ渡す金額。 */
    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "application_fee_amount", nullable = false)
    private Long applicationFeeAmount;

    /**
     * 適用した手数料パターンの自然キー（{@code fee_policies.policy_key} 論理参照・遡及防止の焼き付け）。
     * charge/与信時に {@code FeePolicyResolver} で解決した値を記録し、以後 {@code fee_policies} を改定しても
     * 本取引の料率は固定する（R1・設計書 01 §3.2 / §3.6・README §3.4.2）。既定 {@code DEFAULT}（率5%＋固定0・後方互換）。
     */
    @Column(name = "fee_policy_key", nullable = false, length = 40)
    private String feePolicyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EscrowStatus status;

    @Column(name = "authorized_at")
    private LocalDateTime authorizedAt;

    @Column(name = "captured_at")
    private LocalDateTime capturedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "hold_expires_at")
    private LocalDateTime holdExpiresAt;

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
        if (this.currency == null) {
            this.currency = "JPY";
        }
        if (this.applicationFeeAmount == null) {
            this.applicationFeeAmount = 0L;
        }
        if (this.feePolicyKey == null) {
            this.feePolicyKey = "DEFAULT";
        }
        if (this.status == null) {
            this.status = EscrowStatus.AUTHORIZED;
        }
        if (this.captureMode == null) {
            this.captureMode = EscrowCaptureMode.MANUAL;
        }
    }
}
