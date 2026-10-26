package com.mannschaft.app.event.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ゲスト参加登録リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class GuestRegistrationRequest {

    @NotNull
    private final Long ticketTypeId;

    @NotBlank
    @Size(max = 100)
    private final String guestName;

    @NotBlank
    @Email
    @Size(max = 255)
    private final String guestEmail;

    @Size(max = 50)
    private final String guestPhone;

    @Min(1)
    private final Integer quantity;

    @Size(max = 500)
    private final String note;

    @NotBlank
    private final String inviteToken;
}
