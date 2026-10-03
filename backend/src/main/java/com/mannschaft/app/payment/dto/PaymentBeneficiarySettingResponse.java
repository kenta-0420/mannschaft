package com.mannschaft.app.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 会費受益者制限設定のレスポンス DTO。
 *
 * @param beneficiaryMemberOnly 受益者を会員(MEMBER)のみに限定するか（既定 true＝会員のみ・純 SUPPORTER 除外）
 */
@Schema(description = "会費受益者制限設定")
public record PaymentBeneficiarySettingResponse(
        @Schema(description = "受益者を会員のみに限定するか（true=会員のみ／false=応援者も可）", example = "true")
        boolean beneficiaryMemberOnly) {
}
