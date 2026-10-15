package com.mannschaft.app.analytics.dto;

import com.mannschaft.app.analytics.Granularity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * ユーザートレンドレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class UserTrendResponse {

    private final Granularity granularity;
    private final List<UserTrendPoint> points;

    /**
     * ユーザートレンドの各ポイント。
     */
    @Getter
    @RequiredArgsConstructor
    public static class UserTrendPoint {
        private final String period;
        private final int newUsers;
        private final int activeUsers;
        private final int payingUsers;
        private final int churnedUsers;
        private final int reactivatedUsers;
        private final int totalUsers;
    }
}
