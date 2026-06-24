package com.mannschaft.app.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 会費受益者制限設定の更新リクエスト DTO。
 *
 * @param beneficiaryMemberOnly 受益者を会員(MEMBER)のみに限定するか（true=会員のみ／false=応援者も可）
 */
@Schema(description = "会費受益者制限設定の更新リクエスト")
public record UpdatePaymentBeneficiarySettingRequest(
        @Schema(description = "受益者を会員のみに限定するか（true=会員のみ／false=応援者も可）", example = "true")
        @NotNull
        Boolean beneficiaryMemberOnly) {
}
