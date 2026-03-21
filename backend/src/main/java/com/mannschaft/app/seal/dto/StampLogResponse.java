package com.mannschaft.app.seal.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 押印ログレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class StampLogResponse {

    private final Long id;
    private final Long userId;
    private final Long sealId;
    private final String sealHashAtStamp;
    private final String targetType;
    private final Long targetId;
    private final String stampDocumentHash;
    private final Boolean isRevoked;
    private final LocalDateTime revokedAt;
    private final LocalDateTime stampedAt;
    private final LocalDateTime createdAt;
}
