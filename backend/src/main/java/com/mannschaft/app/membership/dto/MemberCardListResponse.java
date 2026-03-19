package com.mannschaft.app.membership.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * チーム/組織の会員証一覧レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MemberCardListResponse {

    private final Long id;
    private final Long userId;
    private final String cardNumber;
    private final String displayName;
    private final String status;
    private final LocalDateTime issuedAt;
    private final LocalDateTime lastCheckinAt;
    private final int checkinCount;
}
