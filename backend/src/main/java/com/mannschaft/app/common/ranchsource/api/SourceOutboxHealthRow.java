package com.mannschaft.app.common.ranchsource.api;

import com.mannschaft.app.ranch.reward.RanchRewardSourceType;

/** pendingはPENDING/RETRY、deadはDEADの件数。待機0件のoldestAgeSecondsはnull。 */
public record SourceOutboxHealthRow(RanchRewardSourceType sourceType, String pendingCount,
        String deadCount, Long oldestAgeSeconds) {
    public SourceOutboxHealthRow {
        if (sourceType == null || !counter(pendingCount) || !counter(deadCount)
                || (oldestAgeSeconds != null && oldestAgeSeconds < 0)
                || ("0".equals(pendingCount) != (oldestAgeSeconds == null))) {
            throw new IllegalArgumentException("源配送の集計行が不正です");
        }
    }
    private static boolean counter(String value) {
        return value != null && value.matches("0|[1-9][0-9]{0,19}");
    }
}
