package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F22.1 謝礼決済: 複式記帳台帳（追記専用）。
 *
 * <p>{@code escrow_transaction_id} は payment ドメイン内 FK（CASCADE）。
 * 追記専用のため UPDATE/DELETE せず、{@code created_at} のみ保持（updated_at なし）。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/01_data_model.md §3.3</p>
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class LedgerEntryEntity extends UuidV7Entity {

    @Column(name = "escrow_transaction_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID escrowTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 24)
    private LedgerEntryType entryType;

    @Enumerated(EnumType.STRING)
    @Column(name = "account", nullable = false, length = 16)
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 1)
    private LedgerDirection direction;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "running_balance", nullable = false)
    private Long runningBalance;

    @Column(name = "stripe_object_id", length = 48)
    private String stripeObjectId;

    /**
     * RECOVERY 仕訳の経路識別（§6.3・非 RECOVERY 行は null）。勘定の向きだけでは峻別できない
     * C1/C2 発生計上と A 回収実行/再計上を確実に分離し、自己返金時の回収金消失を防ぐ。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "recovery_kind", length = 16)
    private RecoveryKind recoveryKind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.currency == null) {
            this.currency = "JPY";
        }
    }
}
