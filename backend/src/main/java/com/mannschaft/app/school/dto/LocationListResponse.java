package com.mannschaft.app.school.dto;

import com.mannschaft.app.school.entity.AttendanceLocation;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/** クラス全体の学習場所一覧レスポンスDTO。 */
@Getter
@Builder
public class LocationListResponse {

    /** クラスチームID。 */
    private Long teamId;

    /** 出欠対象日。 */
    private LocalDate attendanceDate;

    /** 生徒ごとの学習場所情報リスト。 */
    private List<LocationListItem> items;

    /** クラス内の1生徒分の学習場所情報。 */
    @Getter
    @Builder
    public static class LocationListItem {

        /** 生徒ユーザーID。 */
        private Long studentUserId;

        /** 現在の学習場所。 */
        private AttendanceLocation currentLocation;

        /** 当日中に学習場所の変化があった場合 true。 */
        private boolean locationChangedDuringDay;
    }
}
