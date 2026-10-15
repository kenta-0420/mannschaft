package com.mannschaft.app.seal.dto;

import com.mannschaft.app.seal.SealVariant;
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
    /**
     * 印鑑字体種別。印鑑が削除済みの場合は null。
     */
    private final SealVariant variant;
    private final String targetType;
    private final Long targetId;
    private final String stampDocumentHash;
    private final Boolean isRevoked;
    private final LocalDateTime revokedAt;
    private final LocalDateTime stampedAt;
    private final LocalDateTime createdAt;
}
