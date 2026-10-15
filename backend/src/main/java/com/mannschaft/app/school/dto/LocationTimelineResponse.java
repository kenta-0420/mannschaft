package com.mannschaft.app.school.dto;

import com.mannschaft.app.school.entity.AttendanceLocation;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/** 生徒の1日分学習場所タイムラインレスポンスDTO。 */
@Getter
@Builder
public class LocationTimelineResponse {

    /** 生徒ユーザーID。 */
    private Long studentUserId;

    /** 出欠対象日。 */
    private LocalDate attendanceDate;

    /** 学習場所変化履歴（時系列昇順）。 */
    private List<LocationChangeResponse> changes;

    /**
     * 現在の学習場所。
     * 当日に変化記録がある場合は最後の toLocation、ない場合は CLASSROOM。
     */
    private AttendanceLocation currentLocation;
}
