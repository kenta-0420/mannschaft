package com.mannschaft.app.membership.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * セルフチェックインレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SelfCheckinResponse {

    private final boolean checkedIn;
    private final String reason;
    private final String message;
    private final CheckinInfo checkin;
    private final MemberCardInfo memberCard;

    /**
     * チェックイン成功レスポンスを生成する。
     */
    public static SelfCheckinResponse success(CheckinInfo checkin, MemberCardInfo memberCard, String message) {
        return new SelfCheckinResponse(true, null, message, checkin, memberCard);
    }

    /**
     * チェックイン失敗レスポンスを生成する。
     */
    public static SelfCheckinResponse failure(String reason, String message) {
        return new SelfCheckinResponse(false, reason, message, null, null);
    }

    /**
     * チェックイン情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class CheckinInfo {
        private final Long id;
        private final LocalDateTime checkedInAt;
        private final String locationName;
    }

    /**
     * 会員証情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class MemberCardInfo {
        private final Long id;
        private final String cardNumber;
        private final String scopeType;
        private final String scopeName;
    }
}
