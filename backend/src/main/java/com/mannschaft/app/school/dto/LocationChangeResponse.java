package com.mannschaft.app.school.dto;

import com.mannschaft.app.school.entity.AttendanceLocation;
import com.mannschaft.app.school.entity.AttendanceLocationChangeEntity;
import com.mannschaft.app.school.entity.AttendanceLocationChangeReason;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 学習場所変化記録レスポンスDTO。 */
@Getter
@Builder
public class LocationChangeResponse {

    /** レコードID。 */
    private Long id;

    /** クラスチームID。 */
    private Long teamId;

    /** 生徒ユーザーID。 */
    private Long studentUserId;

    /** 出欠対象日。 */
    private LocalDate attendanceDate;

    /** 変化前の学習場所。 */
    private AttendanceLocation fromLocation;

    /** 変化後の学習場所。 */
    private AttendanceLocation toLocation;

    /** 変化が発生した時限番号（null 可）。 */
    private Integer changedAtPeriod;

    /** 変化が発生した時刻（null 可）。 */
    private LocalTime changedAtTime;

    /** 変化理由。 */
    private AttendanceLocationChangeReason reason;

    /** 備考（null 可）。 */
    private String note;

    /** 記録者ユーザーID。 */
    private Long recordedBy;

    /** 記録日時。 */
    private LocalDateTime recordedAt;

    /**
     * エンティティから LocationChangeResponse を生成するファクトリメソッド。
     *
     * @param entity 学習場所変化記録エンティティ
     * @return LocationChangeResponse
     */
    public static LocationChangeResponse from(AttendanceLocationChangeEntity entity) {
        return LocationChangeResponse.builder()
                .id(entity.getId())
                .teamId(entity.getTeamId())
                .studentUserId(entity.getStudentUserId())
                .attendanceDate(entity.getAttendanceDate())
                .fromLocation(entity.getFromLocation())
                .toLocation(entity.getToLocation())
                .changedAtPeriod(entity.getChangedAtPeriod())
                .changedAtTime(entity.getChangedAtTime())
                .reason(entity.getReason())
                .note(entity.getNote())
                .recordedBy(entity.getRecordedBy())
                .recordedAt(entity.getRecordedAt())
                .build();
    }
}
