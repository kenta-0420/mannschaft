package com.mannschaft.app.event.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 参加登録作成リクエストDTO（会員向け）。
 */
@Getter
@RequiredArgsConstructor
public class CreateRegistrationRequest {

    @NotNull
    private final Long ticketTypeId;

    @Min(1)
    private final Integer quantity;

    @Size(max = 500)
    private final String note;
}
