package com.mannschaft.app.schedule.dto;

import java.util.List;

/** 予定の対象者表示。外部閲覧では targets を空にして人数だけを返す。 */
public record ScheduleTargetResponse(
        String targetMode,
        int targetCount,
        List<TargetMember> targets
) {
    public record TargetMember(Long userId, String displayName, String avatarUrl, String calendarColor) { }
}
