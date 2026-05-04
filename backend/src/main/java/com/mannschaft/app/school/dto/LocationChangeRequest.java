package com.mannschaft.app.school.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.mannschaft.app.school.entity.AttendanceLocation;
import com.mannschaft.app.school.entity.AttendanceLocationChangeReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/** 学習場所変化記録リクエストDTO。 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LocationChangeRequest {

    /** 対象生徒のユーザーID。 */
    @NotNull
    private Long studentUserId;

    /** 出欠対象日（省略時は当日）。 */
    private java.time.LocalDate attendanceDate;

    /** 変化前の学習場所。 */
    @NotNull
    private AttendanceLocation fromLocation;

    /** 変化後の学習場所。 */
    @NotNull
    private AttendanceLocation toLocation;

    /** 変化が発生した時限番号（null 可）。 */
    private Integer changedAtPeriod;

    /** 変化が発生した時刻（null 可）。 */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime changedAtTime;

    /** 変化理由。 */
    @NotNull
    private AttendanceLocationChangeReason reason;

    /** 備考（最大500文字、null 可）。 */
    @Size(max = 500)
    private String note;
}
