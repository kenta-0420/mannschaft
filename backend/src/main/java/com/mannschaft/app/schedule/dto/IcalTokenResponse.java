package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * iCalトークン情報レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class IcalTokenResponse {

    private final String token;
    private final String icalUrl;
    private final String subscribeUrl;
    private final String webcalUrl;
    private final List<ScopedUrlItem> scopedUrls;
    private final boolean isActive;
    private final LocalDateTime lastPolledAt;

    /**
     * スコープ別URL情報アイテム。
     */
    public record ScopedUrlItem(
            String scopeType,
            Long scopeId,
            String scopeName,
            String icalUrl,
            String subscribeUrl
    ) {}
}
