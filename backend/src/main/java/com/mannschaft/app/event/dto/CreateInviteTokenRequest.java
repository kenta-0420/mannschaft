package com.mannschaft.app.event.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 招待トークン作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateInviteTokenRequest {

    @Size(max = 100)
    private final String label;

    private final Integer maxUses;

    private final LocalDateTime expiresAt;
}
