package com.mannschaft.app.advertising.dto;

import java.time.LocalDateTime;

/**
 * 広告クリック記録レスポンス。
 *
 * <p>F09.7 クリック計測 API {@code POST /api/v1/ads/{adId}/click} のレスポンス。</p>
 */
public record AdClickResponse(
        Long id,
        LocalDateTime occurredAt
) {
}
