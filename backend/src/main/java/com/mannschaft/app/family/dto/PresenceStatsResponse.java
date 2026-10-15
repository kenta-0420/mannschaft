package com.mannschaft.app.family.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * プレゼンス統計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PresenceStatsResponse {

    private final String period;
    private final int totalEvents;
    private final int totalHomeEvents;
    private final int totalGoingOutEvents;
    private final int overdueCount;
    private final List<MemberStats> memberStats;

    /**
     * メンバー別統計。
     */
    @Getter
    @RequiredArgsConstructor
    public static class MemberStats {
        private final Long userId;
        private final int homeCount;
        private final int goingOutCount;
        private final int overdueCount;
    }
}
