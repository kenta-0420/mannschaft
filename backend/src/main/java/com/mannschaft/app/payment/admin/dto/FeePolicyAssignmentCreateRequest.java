package com.mannschaft.app.payment.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F22.1 市（Market）統一決済 R2: 手数料パターン割当（{@code fee_policy_assignments}）の作成リクエスト（設計書 02 §11）。
 *
 * <p>{@code sourceKind} は {@link com.mannschaft.app.payment.escrow.EscrowSourceKind} の名称（enum 妥当性は Service で検証）。
 * {@code subKey} は任意（null＝source_kind 既定割当）。{@code policyKey} は存在する有効パターンを指すこと（Service で検証）。
 * organization_id は R2 では常に NULL 運用（テナント別上書きは将来拡張・§3.5.3）。casing camelCase 1:1。</p>
 */
@Getter
@NoArgsConstructor
public class FeePolicyAssignmentCreateRequest {

    /** 解決キー（RECRUITMENT/MEMBERSHIP/TOURNAMENT/JOBMATCHING/FLEAMARKET）。enum 妥当性は Service で検証。 */
    @NotBlank
    @Size(max = 12)
    private String sourceKind;

    /** 任意の細分キー（助っ人＝recruitment_category 値 等・null＝source_kind 既定）。 */
    @Size(max = 40)
    private String subKey;

    /** 適用する手数料パターンの自然キー（存在・有効性は Service で検証）。 */
    @NotBlank
    @Size(min = 1, max = 40)
    @Pattern(regexp = "^[A-Z0-9_]+$", message = "policyKey は英大文字・数字・アンダースコアのみ使用できます")
    private String policyKey;
}
