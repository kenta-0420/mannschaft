package com.mannschaft.app.school.service;

import com.mannschaft.app.school.dto.PeriodAttendanceEntry;
import com.mannschaft.app.school.dto.PeriodAttendanceListResponse;
import com.mannschaft.app.school.dto.PeriodAttendanceRequest;
import com.mannschaft.app.school.dto.PeriodAttendanceResponse;
import com.mannschaft.app.school.dto.PeriodAttendanceSummary;
import com.mannschaft.app.school.dto.PeriodAttendanceUpdateRequest;
import com.mannschaft.app.school.dto.PeriodCandidatesResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.stream.Collectors;

/**
 * 時限別出欠の認可ファサード（トランザクションの外）。
 *
 * <p>認可と入力の在籍確認を業務トランザクションの外で済ませてから {@link PeriodAttendanceService}（TX）を呼ぶ
 * （D-3T。先例: shift の ShiftRequestFacade）。認可を通る前には行も移動検知も走らせない。
 * 本クラスは {@code @Transactional} を付けない。</p>
 */
@Component
@RequiredArgsConstructor
public class PeriodAttendanceFacade {

    private final PeriodAttendanceService periodAttendanceService;
    private final SchoolAttendanceAccessPolicy policy;

    /**
     * 時限出欠を一括登録する。認可: 時限の登録権（P）。時限 POST は upsert なので PATCH と同じ条件で既存レコードの書換も拒否される。
     * 登録 entries の生徒は全員このクラスの在籍メンバーであること（1 人でも不正なら 400 で全件登録しない）。
     */
    public PeriodAttendanceSummary submitPeriodAttendance(
            Long teamId, Integer periodNumber, PeriodAttendanceRequest request, Long operatorUserId) {
        policy.checkCanRecordPeriod(operatorUserId, teamId);
        policy.requireNoDuplicateStudents(
                request.getEntries().stream().map(PeriodAttendanceEntry::getStudentUserId).toList());
        policy.requireEnrolledStudents(teamId,
                request.getEntries().stream().map(PeriodAttendanceEntry::getStudentUserId)
                        .collect(Collectors.toSet()));
        return periodAttendanceService.submitPeriodAttendance(teamId, periodNumber, request, operatorUserId);
    }

    /** 特定日・時限の出欠一覧を取得する。認可: 閲覧権（V）。 */
    public PeriodAttendanceListResponse getPeriodAttendance(
            Long teamId, LocalDate date, Integer periodNumber, Long currentUserId) {
        policy.checkCanView(currentUserId, teamId);
        return periodAttendanceService.getPeriodAttendance(teamId, date, periodNumber);
    }

    /** 時限の対象生徒候補を取得する。認可: 閲覧権（V）。 */
    public PeriodCandidatesResponse getPeriodCandidates(
            Long teamId, LocalDate date, Integer periodNumber, Long currentUserId) {
        policy.checkCanView(currentUserId, teamId);
        return periodAttendanceService.getPeriodCandidates(teamId, date, periodNumber);
    }

    /** 時限出欠を個別修正する。認可: 時限の登録権（P）。 */
    public PeriodAttendanceResponse updatePeriodRecord(
            Long teamId, Long recordId, PeriodAttendanceUpdateRequest request, Long operatorUserId) {
        policy.checkCanRecordPeriod(operatorUserId, teamId);
        return periodAttendanceService.updatePeriodRecord(teamId, recordId, request, operatorUserId);
    }
}
