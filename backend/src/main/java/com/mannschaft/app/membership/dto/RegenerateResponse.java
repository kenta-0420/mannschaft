package com.mannschaft.app.membership.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * QRコード再生成レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class RegenerateResponse {

    private final Long memberCardId;
    private final String message;
    private final LocalDateTime regeneratedAt;
}
