package com.mannschaft.app.seal.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 押印検証レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class StampVerifyResponse {

    private final Long stampLogId;
    private final Boolean isValid;
    private final Boolean isRevoked;
    private final String message;
}
