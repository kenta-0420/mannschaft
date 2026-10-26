package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableShareTargetEntity;

import java.time.LocalDateTime;

/**
 * F03.15 Phase 5 個人時間割の家族チーム共有先レスポンス。
 *
 * <p>共有先の家族チーム名は表示用に呼び出し側でルックアップして埋め込む。
 * 取得できなかった場合（チームが削除済み等）は {@code teamName} に null を入れる。</p>
 */
public record PersonalTimetableShareTargetResponse(
        Long id,
        @JsonProperty("personal_timetable_id") Long personalTimetableId,
        @JsonProperty("team_id") Long teamId,
        @JsonProperty("team_name") String teamName,
        @JsonProperty("created_at") LocalDateTime createdAt) {

    public static PersonalTimetableShareTargetResponse from(
            PersonalTimetableShareTargetEntity entity, String teamName) {
        return new PersonalTimetableShareTargetResponse(
                entity.getId(),
                entity.getPersonalTimetableId(),
                entity.getTeamId(),
                teamName,
                entity.getCreatedAt());
    }
}
