package com.mannschaft.app.receipt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 発行者設定更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateIssuerSettingsRequest {

    @NotBlank
    @Size(max = 200)
    private final String issuerName;

    @Size(max = 10)
    private final String postalCode;

    @Size(max = 500)
    private final String address;

    @Size(max = 20)
    private final String phone;

    @NotNull
    private final Boolean isQualifiedInvoicer;

    @Pattern(regexp = "^T\\d{13}$", message = "登録番号はT + 13桁の数字で入力してください")
    @Size(max = 14)
    private final String invoiceRegistrationNumber;

    private final Long defaultSealUserId;

    @Size(max = 20)
    private final String defaultSealVariant;

    private final String receiptNoteTemplate;

    @Size(max = 20)
    private final String receiptNumberPrefix;

    @Min(1)
    @Max(12)
    private final Integer fiscalYearStartMonth;

    private final Boolean autoResetNumber;

    @Size(max = 500)
    private final String customFooter;
}
