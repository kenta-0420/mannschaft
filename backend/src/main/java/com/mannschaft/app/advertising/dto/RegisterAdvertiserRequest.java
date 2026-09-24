package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.BillingMethod;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 広告主アカウント登録リクエスト。
 *
 * <p>{@code billingMethod} は F08.12 §5.0 の後払い廃止に伴い省略可能にした。省略時は
 * {@code AdvertiserAccountService.register} が既定値 {@code STRIPE} で作成する。
 * フィールド自体は API 互換のため残すが、{@code INVOICE} を指定した登録はサービス層で拒否する。</p>
 */
public record RegisterAdvertiserRequest(

        @NotBlank
        @Size(max = 200)
        String companyName,

        @NotBlank
        @Email
        String contactEmail,

        BillingMethod billingMethod
) {
}
