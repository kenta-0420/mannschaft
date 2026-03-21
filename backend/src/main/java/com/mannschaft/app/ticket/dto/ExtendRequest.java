package com.mannschaft.app.ticket.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 有効期限延長リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ExtendRequest {

    @NotNull
    private final LocalDateTime newExpiresAt;

    @Size(max = 500)
    private final String note;
}
