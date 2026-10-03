package com.mannschaft.app.school.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.school.dto.LocationChangeResponse;
import com.mannschaft.app.school.entity.AttendanceLocation;
import com.mannschaft.app.school.entity.AttendanceLocationChangeEntity;
import com.mannschaft.app.school.entity.AttendanceLocationChangeReason;
import com.mannschaft.app.school.error.SchoolErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 登校場所の認可ファサード（トランザクションの外）。
 *
 * <p>認可（{@link SchoolAttendanceAccessPolicy}）を業務トランザクションの外で済ませてから
 * {@link AttendanceLocationService}（TX）を呼ぶ。認可は他ドメイン（role・membership・family）の Repository へ
 * 到達するため、業務 TX の中に置かない（D-3T。先例: shift の ShiftRequestFacade）。
 * 本クラスは {@code @Transactional} を付けない。</p>
 */
@Component
@RequiredArgsConstructor
public class AttendanceLocationFacade {

    private final AttendanceLocationService locationService;
    private final SchoolAttendanceAccessPolicy policy;

    /** 登校場所変更を記録する。認可（AC-13）: 日次登録権（R）。対象生徒は当該クラスの在籍メンバーであること。 */
    public AttendanceLocationChangeEntity recordLocationChange(
            Long teamId, Long studentUserId, LocalDate attendanceDate,
            AttendanceLocation fromLocation, AttendanceLocation toLocation,
            Integer changedAtPeriod, LocalTime changedAtTime,
            AttendanceLocationChangeReason reason, String note, Long operatorUserId) {
        policy.checkCanRecordDaily(operatorUserId, teamId);
        if (!policy.isEnrolledStudent(teamId, studentUserId)) {
            throw new BusinessException(SchoolErrorCode.DAILY_RECORD_NOT_FOUND);
        }
        return locationService.recordLocationChange(
                teamId, studentUserId, attendanceDate, fromLocation, toLocation,
                changedAtPeriod, changedAtTime, reason, note, operatorUserId);
    }

    /** 個別生徒のタイムラインを取得する。認可（AC-4）: 本人・保護者は全クラス分、教職員は閲覧権のあるクラス分。 */
    public List<LocationChangeResponse> getTimeline(
            Long studentUserId, LocalDate attendanceDate, Long currentUserId) {
        Set<Long> viewableTeamIds = policy.resolveViewableTeamIds(studentUserId, currentUserId);
        return locationService.getTimeline(studentUserId, attendanceDate, viewableTeamIds);
    }

    /** クラス全体の最新ロケーションマップを取得する。認可: 閲覧権（V）。 */
    public Map<Long, AttendanceLocation> getTeamLocationMap(
            Long teamId, LocalDate attendanceDate, Long currentUserId) {
        policy.checkCanView(currentUserId, teamId);
        return locationService.getTeamLocationMap(teamId, attendanceDate);
    }
}
