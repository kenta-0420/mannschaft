package com.mannschaft.app.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * F22.1 市（Market）統一決済 R1: 手数料パターンのマスタ（{@code fee_policies}・率%＋固定額¥）。
 *
 * <p><b>主キーは自然キー {@code policy_key}</b>（UUIDv7 でない）。CLAUDE.md「マスタテーブル例外」に該当する
 * （全テナント共通の参照データ・書込はシスアド運用のみ・税率表と同型）ため（設計書 01 §3.6・原則6 例外）。</p>
 *
 * <p>{@code escrow_transactions.fee_policy_key} へ焼き付け（遡及防止）の参照先。料率改定は新規徴収のみ反映し
 * 既存取引は焼き付け値で固定する（設計書 01 §3.2 / §3.6）。FK は張らず論理参照とする（料率改定で過去取引が
 * 壊れない不変性優先）。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/01_data_model.md §3.6</p>
 */
@Entity
@Table(name = "fee_policies")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(of = "policyKey")
public class FeePolicyEntity {

    /** PK・自然キー（{@code DEFAULT} / {@code RECRUITMENT_HELPER} 等）。 */
    @Id
    @Column(name = "policy_key", nullable = false, length = 40)
    private String policyKey;

    /** 管理画面表示名（管理者向け・直接表示は管理 UI のみ）。 */
    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    /** 総手数料の率（{@code 0 ≤ percent_rate < 1}・例 0.0500＝5%）。 */
    @Column(name = "percent_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal percentRate;

    /** 総手数料の固定額（円・最小単位・0 で率のみ）。 */
    @Column(name = "flat_fee_minor", nullable = false)
    private Long flatFeeMinor;

    /** 無効化フラグ（無効パターンは新規割当・解決から除外。既存焼き付け取引には影響しない）。 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    /** 補足説明（運用メモ）。 */
    @Column(name = "description", length = 500)
    private String description;

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
        if (this.enabled == null) {
            this.enabled = Boolean.TRUE;
        }
        if (this.flatFeeMinor == null) {
            this.flatFeeMinor = 0L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 本マスタ行を計算用の {@link FeePolicy} 値オブジェクトへ変換する。
     *
     * @return 率%＋固定額¥ の値オブジェクト
     */
    public FeePolicy toFeePolicy() {
        return new FeePolicy(policyKey, percentRate, flatFeeMinor != null ? flatFeeMinor : 0L);
    }
}
