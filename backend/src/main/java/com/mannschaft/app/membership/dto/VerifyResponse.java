package com.mannschaft.app.membership.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * QRスキャン認証レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class VerifyResponse {

    private final boolean verified;
    private final String reason;
    private final String message;
    private final MemberCardInfo memberCard;
    private final CheckinInfo checkin;
    private final LocalDateTime memberSince;

    /**
     * 認証成功レスポンスを生成する。
     */
    public static VerifyResponse success(MemberCardInfo memberCard, CheckinInfo checkin, LocalDateTime memberSince) {
        return new VerifyResponse(true, null, null, memberCard, checkin, memberSince);
    }

    /**
     * 認証失敗レスポンスを生成する。
     */
    public static VerifyResponse failure(String reason, String message) {
        return new VerifyResponse(false, reason, message, null, null, null);
    }

    /**
     * 認証成功時の会員証情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class MemberCardInfo {
        private final Long id;
        private final String cardNumber;
        private final String displayName;
        private final String status;
        private final String scopeType;
        private final String scopeName;
        private final int checkinCount;
        private final LocalDateTime lastCheckinAt;
    }

    /**
     * チェックイン情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class CheckinInfo {
        private final Long id;
        private final LocalDateTime checkedInAt;
        private final String location;
    }
}
