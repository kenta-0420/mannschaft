package com.mannschaft.app.succession.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 法的手続き起票リクエスト DTO（F09.15 S6-B）。
 *
 * <p>UC-C1（法的手続きレコード起票）で使用する。
 * 申立種別は 2 種類のみ許可される:
 * <ul>
 *   <li>{@code ABSENTEE_PROPERTY_MANAGER} — 不在者財産管理人選任申立</li>
 *   <li>{@code INHERITANCE_LIQUIDATOR} — 相続財産清算人選任申立</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateLegalFilingRequest {

    /** 居住者台帳 ID。 */
    @NotNull(message = "residentRegistryId は必須です")
    private Long residentRegistryId;

    /** 居室 ID。 */
    @NotNull(message = "dwellingUnitId は必須です")
    private Long dwellingUnitId;

    /**
     * 申立種別。
     * "ABSENTEE_PROPERTY_MANAGER" または "INHERITANCE_LIQUIDATOR" のみ許可。
     */
    @NotBlank(message = "filingType は必須です")
    @Pattern(regexp = "ABSENTEE_PROPERTY_MANAGER|INHERITANCE_LIQUIDATOR",
            message = "filingType は ABSENTEE_PROPERTY_MANAGER または INHERITANCE_LIQUIDATOR でなければなりません")
    private String filingType;

    /** 備考（任意・最大 1000 文字）。 */
    @Size(max = 1000, message = "備考は 1000 文字以内で入力してください")
    private String note;
}
