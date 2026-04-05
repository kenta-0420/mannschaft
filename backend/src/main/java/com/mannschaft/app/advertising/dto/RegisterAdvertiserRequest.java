package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.BillingMethod;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 広告主アカウント登録リクエスト。
 */
public record RegisterAdvertiserRequest(

        @NotBlank
        @Size(max = 200)
        String companyName,

        @NotBlank
        @Email
        String contactEmail,

        @NotNull
        BillingMethod billingMethod
) {
}
