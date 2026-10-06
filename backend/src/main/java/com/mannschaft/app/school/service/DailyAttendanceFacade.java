package com.mannschaft.app.school.service;

import com.mannschaft.app.school.dto.DailyAttendanceListResponse;
import com.mannschaft.app.school.dto.DailyAttendanceResponse;
import com.mannschaft.app.school.dto.DailyAttendanceUpdateRequest;
import com.mannschaft.app.school.dto.DailyRollCallRequest;
import com.mannschaft.app.school.dto.DailyRollCallSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.stream.Collectors;

/**
 * 日次出欠の認可ファサード（トランザクションの外）。
 *
 * <p>認可と入力の在籍確認を業務トランザクションの外で済ませてから {@link DailyAttendanceService}（TX）を呼ぶ
 * （D-3T。先例: shift の ShiftRequestFacade）。認可を通る前には行も通知イベントも作らない。
 * 本クラスは {@code @Transactional} を付けない。</p>
 */
@Component
@RequiredArgsConstructor
public class DailyAttendanceFacade {

    private final DailyAttendanceService dailyAttendanceService;
    private final SchoolAttendanceAccessPolicy policy;

    /**
     * 朝の点呼を一括登録する。認可（AC-13）: 日次登録権（R）。登録 entries の生徒は全員このクラスの在籍メンバーであること
     * （1 人でも不正なら 400 で全件登録しない）。
     */
    public DailyRollCallSummary submitDailyRollCall(Long teamId, DailyRollCallRequest request, Long operatorUserId) {
        policy.checkCanRecordDaily(operatorUserId, teamId);
        policy.requireNoDuplicateStudents(
                request.getEntries().stream().map(e -> e.getStudentUserId()).toList());
        policy.requireEnrolledStudents(teamId,
                request.getEntries().stream().map(e -> e.getStudentUserId()).collect(Collectors.toSet()));
        return dailyAttendanceService.submitDailyRollCall(teamId, request, operatorUserId);
    }

    /** クラスの日次出欠一覧を取得する。認可: 閲覧権（V）。 */
    public DailyAttendanceListResponse getDailyAttendance(Long teamId, LocalDate date, Long currentUserId) {
        policy.checkCanView(currentUserId, teamId);
        return dailyAttendanceService.getDailyAttendance(teamId, date);
    }

    /** 日次出欠を個別修正する。認可（AC-13）: 日次登録権（R）。 */
    public DailyAttendanceResponse updateDailyRecord(
            Long teamId, Long recordId, DailyAttendanceUpdateRequest request, Long operatorUserId) {
        policy.checkCanRecordDaily(operatorUserId, teamId);
        return dailyAttendanceService.updateDailyRecord(teamId, recordId, request, operatorUserId);
    }
}
