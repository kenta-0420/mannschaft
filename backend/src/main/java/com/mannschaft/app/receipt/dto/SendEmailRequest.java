package com.mannschaft.app.receipt.dto;

import jakarta.validation.constraints.Email;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 領収書メール送信リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SendEmailRequest {

    @Email
    private final String email;
}
