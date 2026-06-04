package com.mannschaft.app.payment.admin.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * F22.1 市（Market）統一決済 R2: 手数料パターン（{@code fee_policies}）の作成/更新リクエスト（設計書 02 §11）。
 *
 * <p>POST（新規）/ PUT（更新）で共用する upsert ペイロード。{@code policyKey} は POST 時のみ用い、PUT ではパス変数を正とする。
 * バリデーション（率 ∈ [0,1)・固定額 ≥ 0・キー形式・表示名必須）は Bean Validation で前段検証し、
 * 「率・固定額がともに 0（手数料ゼロ禁止）」「DEFAULT 保護」などの業務制約は Service 層で
 * {@code ConnectPaymentErrorCode} 系として検証する（§11）。casing camelCase 1:1・金額 long。</p>
 */
@Getter
@NoArgsConstructor
public class FeePolicyUpsertRequest {

    /** 自然キー（英大文字・数字・アンダースコアのみ・1〜40 文字）。POST 時のみ使用（PUT はパス変数が正）。 */
    @NotBlank
    @Size(min = 1, max = 40)
    @Pattern(regexp = "^[A-Z0-9_]+$", message = "policyKey は英大文字・数字・アンダースコアのみ使用できます")
    private String policyKey;

    /** 管理画面表示名（必須・1〜80 文字）。 */
    @NotBlank
    @Size(min = 1, max = 80)
    private String displayName;

    /** 総手数料の率（{@code 0 ≤ percentRate < 1}）。 */
    @NotNull
    @DecimalMin(value = "0.0", inclusive = true, message = "percentRate は 0 以上である必要があります")
    @DecimalMax(value = "1.0", inclusive = false, message = "percentRate は 1 未満である必要があります")
    private BigDecimal percentRate;

    /** 総手数料の固定額（円・最小単位・0 以上）。 */
    @NotNull
    @PositiveOrZero(message = "flatFeeMinor は 0 以上である必要があります")
    private Long flatFeeMinor;

    /** 有効フラグ（null の場合 Service 既定で TRUE 扱い）。 */
    private Boolean enabled;

    /** 補足説明（運用メモ・任意・最大 500 文字）。 */
    @Size(max = 500)
    private String description;
}
