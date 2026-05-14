package com.mannschaft.app.residencestatus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 年次更新回答送信リクエスト（F09.16）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitAnnualResponseRequest {

    /** 対象居室 ID（F09.1 dwelling_units.id、クロスドメイン弱参照） */
    @NotNull
    private Long dwellingUnitId;

    /** 居住者台帳 ID（F09.1 resident_registry.id、クロスドメイン弱参照・UPSERT キー） */
    @NotNull
    private Long residentRegistryId;

    /** 居住状態: OWNER_RESIDING / RENTED_OUT / LONG_ABSENCE / VACANT / OTHER */
    @NotBlank
    private String residenceState;

    private Boolean contactPhoneVerified;
    private Boolean contactEmailVerified;
    private Boolean emergencyContactVerified;

    @Size(max = 1000)
    private String note;
}
