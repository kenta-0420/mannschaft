package com.mannschaft.app.directmail.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ダイレクトメール受信者レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class DirectMailRecipientResponse {

    private final Long id;
    private final Long userId;
    private final String email;
    private final String status;
    private final LocalDateTime openedAt;
    private final LocalDateTime bouncedAt;
    private final String bounceType;
    private final LocalDateTime createdAt;
}
