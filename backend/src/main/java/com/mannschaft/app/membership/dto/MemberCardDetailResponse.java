package com.mannschaft.app.membership.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会員証詳細レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MemberCardDetailResponse {

    private final Long id;
    private final String scopeType;
    private final MemberCardResponse.ScopeInfo scope;
    private final String cardNumber;
    private final String displayName;
    private final String status;
    private final LocalDateTime issuedAt;
    private final LocalDateTime lastCheckinAt;
    private final int checkinCount;
    private final int qrRegeneratedCount;
}
