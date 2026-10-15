package com.mannschaft.app.advertising.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * 広告主アカウント更新リクエスト。
 * <p>
 * 両フィールドとも nullable。少なくとも1つはService層で検証する。
 */
public record UpdateAdvertiserAccountRequest(

        @Size(max = 200)
        String companyName,

        @Email
        String contactEmail
) {
}
