package com.mannschaft.app.membership.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会員証レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MemberCardResponse {

    private final Long id;
    private final String scopeType;
    private final ScopeInfo scope;
    private final String cardNumber;
    private final String displayName;
    private final String status;
    private final LocalDateTime issuedAt;
    private final LocalDateTime lastCheckinAt;
    private final int checkinCount;

    /**
     * スコープ情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ScopeInfo {
        private final Long id;
        private final String name;
    }
}
