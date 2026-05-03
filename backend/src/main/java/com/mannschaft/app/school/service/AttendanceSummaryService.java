package com.mannschaft.app.school.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.dto.ClassSummaryListResponse;
import com.mannschaft.app.school.dto.RecalculateSummaryRequest;
import com.mannschaft.app.school.dto.RecalculateSummaryResponse;
import com.mannschaft.app.school.dto.StudentSummaryResponse;
import com.mannschaft.app.school.entity.AttendanceLocation;
import com.mannschaft.app.school.entity.DailyAttendanceRecordEntity;
import com.mannschaft.app.school.entity.PeriodAttendanceRecordEntity;
import com.mannschaft.app.school.entity.StudentAttendanceSummaryEntity;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.repository.DailyAttendanceRecordRepository;
import com.mannschaft.app.school.repository.PeriodAttendanceRecordRepository;
import com.mannschaft.app.school.repository.StudentAttendanceSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 出席集計サービス。
 *
 * <p>生徒・クラスの出席集計取得および再計算（upsert）を提供する。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AttendanceSummaryService {

    private final StudentAttendanceSummaryRepository summaryRepository;
    private final DailyAttendanceRecordRepository dailyRepository;
    private final PeriodAttendanceRecordRepository periodRepository;

    // ========================================
    // 集計取得
    // ========================================

    /**
     * 生徒の出席集計を取得する。
     *
     * @param studentUserId 生徒ユーザーID
     * @param teamId        チームID
     * @param academicYear  学年度
     * @param termId        学期ID（null なら年度通算）
     * @return 出席集計レスポンス
     * @throws BusinessException 集計が存在しない場合
     */
    public StudentSummaryResponse getStudentSummary(
            Long studentUserId, Long teamId, short academicYear, Long termId) {
        StudentAttendanceSummaryEntity entity = summaryRepository
                .findByStudentUserIdAndTeamIdAndAcademicYearAndTermId(
                        studentUserId, teamId, academicYear, termId)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.SUMMARY_NOT_FOUND));
        return StudentSummaryResponse.from(entity);
    }

    /**
     * クラス全員の年度/学期別出席集計一覧を取得する。
     *
     * @param teamId       チームID
     * @param academicYear 学年度
     * @param termId       学期ID（null なら年度通算）
     * @return クラス出席集計一覧レスポンス
     */
    public ClassSummaryListResponse getClassSummaries(
            Long teamId, short academicYear, Long termId) {
        List<StudentAttendanceSummaryEntity> entities;
        if (termId == null) {
            entities = summaryRepository.findClassSummaries(teamId, academicYear);
        } else {
            entities = summaryRepository.findByTeamIdAndAcademicYearAndTermIdOrderByStudentUserIdAsc(
                    teamId, academicYear, termId);
        }
        List<StudentSummaryResponse> summaries = entities.stream()
                .map(StudentSummaryResponse::from)
                .collect(Collectors.toList());
        return ClassSummaryListResponse.builder()
                .teamId(teamId)
                .academicYear(academicYear)
                .termId(termId)
                .total(summaries.size())
                .summaries(summaries)
                .build();
    }

    // ========================================
    // 集計再計算（upsert）
    // ========================================

    /**
     * 指定生徒・期間の出席集計を再計算して保存する（upsert）。
     *
     * <p>日次出欠レコードを集計期間で取得し、ステータス・場所別に集計する。
     * 既存レコードがあれば {@code toBuilder()} で更新、なければ新規作成する。</p>
     *
     * @param studentUserId 生徒ユーザーID
     * @param req           再計算リクエスト
     * @return 再計算結果レスポンス
     */
    @Transactional
    public RecalculateSummaryResponse recalculate(Long studentUserId, RecalculateSummaryRequest req) {
        LocalDate from = LocalDate.parse(req.getPeriodFrom());
        LocalDate to = LocalDate.parse(req.getPeriodTo());

        // 日次出欠レコード取得
        List<DailyAttendanceRecordEntity> dailyRecords =
                dailyRepository.findByStudentUserIdAndTeamIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                        studentUserId, req.getTeamId(), from, to);

        // 日次集計
        short totalSchoolDays = (short) dailyRecords.size();
        short presentDays = 0;
        short absentDays = 0;
        short lateCount = 0;
        short earlyLeaveCount = 0;
        short sickBayDays = 0;
        short separateRoomDays = 0;
        short onlineDays = 0;
        short homeLearningDays = 0;

        for (DailyAttendanceRecordEntity record : dailyRecords) {
            AttendanceStatus status = record.getStatus();
            if (AttendanceStatus.ATTENDING.equals(status)) {
                presentDays++;
            } else if (AttendanceStatus.ABSENT.equals(status)) {
                absentDays++;
            } else if (AttendanceStatus.PARTIAL.equals(status)) {
                // PARTIAL は遅刻 or 早退を表す
                if (record.getArrivalTime() != null) {
                    lateCount++;
                }
                if (record.getLeaveTime() != null) {
                    earlyLeaveCount++;
                }
                // PARTIAL も出席扱い
                presentDays++;
            }

            // 場所別集計
            AttendanceLocation location = record.getAttendanceLocation();
            if (location != null) {
                switch (location) {
                    case SICK_BAY -> sickBayDays++;
                    case SEPARATE_ROOM -> separateRoomDays++;
                    case ONLINE -> onlineDays++;
                    case HOME_LEARNING -> homeLearningDays++;
                    default -> { /* CLASSROOM / LIBRARY / OUT_OF_SCHOOL / NOT_APPLICABLE は加算なし */ }
                }
            }
        }

        // 出席率計算（小数点2桁）
        BigDecimal attendanceRate = totalSchoolDays == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(presentDays)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalSchoolDays), 2, RoundingMode.HALF_UP);

        // 時限別出欠レコード取得
        List<PeriodAttendanceRecordEntity> periodRecords =
                periodRepository.findByStudentUserIdAndAttendanceDateBetweenOrderByAttendanceDateAscPeriodNumberAsc(
                        studentUserId, from, to);

        short totalPeriods = (short) periodRecords.size();
        short presentPeriods = 0;
        for (PeriodAttendanceRecordEntity pr : periodRecords) {
            if (AttendanceStatus.ATTENDING.equals(pr.getStatus())
                    || AttendanceStatus.PARTIAL.equals(pr.getStatus())) {
                presentPeriods++;
            }
        }

        BigDecimal periodAttendanceRate = totalPeriods == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(presentPeriods)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalPeriods), 2, RoundingMode.HALF_UP);

        LocalDateTime now = LocalDateTime.now();

        // 既存集計レコードを検索して upsert
        Optional<StudentAttendanceSummaryEntity> existing = summaryRepository
                .findByStudentUserIdAndTeamIdAndAcademicYearAndTermId(
                        studentUserId, req.getTeamId(), req.getAcademicYear(), req.getTermId());

        StudentAttendanceSummaryEntity entity;
        if (existing.isPresent()) {
            log.debug("既存集計レコードを更新: studentUserId={}, teamId={}, academicYear={}, termId={}",
                    studentUserId, req.getTeamId(), req.getAcademicYear(), req.getTermId());
            entity = existing.get().toBuilder()
                    .periodFrom(from)
                    .periodTo(to)
                    .totalSchoolDays(totalSchoolDays)
                    .presentDays(presentDays)
                    .absentDays(absentDays)
                    .lateCount(lateCount)
                    .earlyLeaveCount(earlyLeaveCount)
                    .sickBayDays(sickBayDays)
                    .separateRoomDays(separateRoomDays)
                    .onlineDays(onlineDays)
                    .homeLearningDays(homeLearningDays)
                    .attendanceRate(attendanceRate)
                    .totalPeriods(totalPeriods)
                    .presentPeriods(presentPeriods)
                    .periodAttendanceRate(periodAttendanceRate)
                    .lastRecalculatedAt(now)
                    .build();
        } else {
            log.debug("新規集計レコードを作成: studentUserId={}, teamId={}, academicYear={}, termId={}",
                    studentUserId, req.getTeamId(), req.getAcademicYear(), req.getTermId());
            entity = StudentAttendanceSummaryEntity.builder()
                    .teamId(req.getTeamId())
                    .studentUserId(studentUserId)
                    .termId(req.getTermId())
                    .academicYear(req.getAcademicYear())
                    .periodFrom(from)
                    .periodTo(to)
                    .totalSchoolDays(totalSchoolDays)
                    .presentDays(presentDays)
                    .absentDays(absentDays)
                    .lateCount(lateCount)
                    .earlyLeaveCount(earlyLeaveCount)
                    .sickBayDays(sickBayDays)
                    .separateRoomDays(separateRoomDays)
                    .onlineDays(onlineDays)
                    .homeLearningDays(homeLearningDays)
                    .attendanceRate(attendanceRate)
                    .totalPeriods(totalPeriods)
                    .presentPeriods(presentPeriods)
                    .periodAttendanceRate(periodAttendanceRate)
                    .lastRecalculatedAt(now)
                    .build();
        }

        StudentAttendanceSummaryEntity saved = summaryRepository.save(entity);

        return RecalculateSummaryResponse.builder()
                .studentUserId(studentUserId)
                .teamId(req.getTeamId())
                .academicYear(req.getAcademicYear())
                .termId(req.getTermId())
                .recalculatedAt(now)
                .summary(StudentSummaryResponse.from(saved))
                .build();
    }
}
