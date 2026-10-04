package com.mannschaft.app.gamification.dto;

import java.time.LocalDate;
import java.util.Objects;

/** 置物化のための不変取得記録。組織名、自由入力の名称、素材URLを含めない。 */
public record LegacyBadgeAward(Long awardRowId, String badgeId, String periodLabel,
                               LocalDate earnedOn, boolean sourceBadgeAvailable) {
    public LegacyBadgeAward {
        if (awardRowId == null || awardRowId <= 0 || badgeId == null
                || !badgeId.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("バッジ取得identityが不正です");
        }
        periodLabel = periodLabel == null ? "" : periodLabel;
        Objects.requireNonNull(earnedOn, "earnedOn");
    }
}
