package com.mannschaft.app.school.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.school.dto.ClassSummaryListResponse;
import com.mannschaft.app.school.dto.RecalculateSummaryRequest;
import com.mannschaft.app.school.dto.RecalculateSummaryResponse;
import com.mannschaft.app.school.dto.StudentSummaryResponse;
import com.mannschaft.app.school.error.SchoolErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 出席集計の認可ファサード（トランザクションの外）。
 *
 * <p>認可を業務トランザクションの外で済ませてから {@link AttendanceSummaryService}（TX）を呼ぶ
 * （D-3T。先例: shift の ShiftRequestFacade）。本クラスは {@code @Transactional} を付けない。</p>
 */
@Component
@RequiredArgsConstructor
public class AttendanceSummaryFacade {

    private final AttendanceSummaryService summaryService;
    private final SchoolAttendanceAccessPolicy policy;

    /** 生徒の出席集計を取得する。認可（AC-4）: 本人・保護者、または対象クラスの閲覧権（V）を持つ教職員。 */
    public StudentSummaryResponse getStudentSummary(
            Long studentUserId, Long teamId, short academicYear, Long termId, Long currentUserId) {
        if (!policy.isSelfOrGuardian(studentUserId, currentUserId)) {
            policy.checkCanView(currentUserId, teamId);
        }
        return summaryService.getStudentSummary(studentUserId, teamId, academicYear, termId);
    }

    /** クラス全員の出席集計一覧を取得する。認可: 閲覧権（V）。 */
    public ClassSummaryListResponse getClassSummaries(
            Long teamId, short academicYear, Long termId, Long currentUserId) {
        policy.checkCanView(currentUserId, teamId);
        return summaryService.getClassSummaries(teamId, academicYear, termId);
    }

    /** 出席集計を再計算する。認可（AC-13）: 日次登録権（R）。対象生徒は当該クラスの在籍メンバーであること。 */
    public RecalculateSummaryResponse recalculate(
            Long studentUserId, RecalculateSummaryRequest req, Long currentUserId) {
        policy.checkCanRecordDaily(currentUserId, req.getTeamId());
        if (!policy.isEnrolledStudent(req.getTeamId(), studentUserId)) {
            throw new BusinessException(SchoolErrorCode.SUMMARY_NOT_FOUND);
        }
        return summaryService.recalculate(studentUserId, req);
    }
}
