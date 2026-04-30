package com.mannschaft.app.school.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.dto.PeriodAttendanceEntry;
import com.mannschaft.app.school.dto.PeriodAttendanceListResponse;
import com.mannschaft.app.school.dto.PeriodAttendanceRequest;
import com.mannschaft.app.school.dto.PeriodAttendanceResponse;
import com.mannschaft.app.school.dto.PeriodAttendanceSummary;
import com.mannschaft.app.school.dto.PeriodAttendanceUpdateRequest;
import com.mannschaft.app.school.dto.PeriodCandidatesResponse;
import com.mannschaft.app.school.dto.StudentTimelineResponse;
import com.mannschaft.app.school.entity.AttendanceTransitionAlertEntity;
import com.mannschaft.app.school.entity.PeriodAttendanceRecordEntity;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.repository.PeriodAttendanceRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 時限別出欠管理サービス。
 *
 * <p>設計書 §4.1・§5.2 に基づき、以下の機能を提供する:</p>
 * <ul>
 *   <li>教科担任による時限出欠の一括登録（upsert）</li>
 *   <li>特定日・時限の出欠一覧取得</li>
 *   <li>時限対象生徒一覧＋直前時限ステータス取得（candidates）</li>
 *   <li>個別レコードの修正</li>
 *   <li>生徒の1日タイムライン取得</li>
 * </ul>
 *
 * <p>ABSENT 登録時は {@link AttendanceTransitionDetectionService} を呼び出して
 * 「前にいたのに今いない」検知を行う。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PeriodAttendanceService {

    private static final String SUBJECT_NAME_DEFAULT = "未登録";
    private static final Set<AttendanceStatus> PRESENT_STATUSES = Set.of(
            AttendanceStatus.ATTENDING, AttendanceStatus.PARTIAL);

    private final PeriodAttendanceRecordRepository periodAttendanceRecordRepository;
    private final AttendanceTransitionDetectionService attendanceTransitionDetectionService;
    private final AccessControlService accessControlService;

    /**
     * 時限出欠を一括登録（教科担任用）。
     *
     * <p>既存レコードが存在する場合は更新（upsert）、なければ新規作成する。
     * ABSENT エントリについては {@link AttendanceTransitionDetectionService#detectTransition} を
     * 呼び出して移動検知を行い、結果を PeriodAttendanceSummary の alertCount に反映する。</p>
     *
     * @param teamId         チームID
     * @param periodNumber   時限番号
     * @param request        一括登録リクエスト
     * @param operatorUserId 操作者ユーザーID（教科担任）
     * @return 登録結果サマリ
     */
    @Transactional
    public PeriodAttendanceSummary submitPeriodAttendance(
            Long teamId, Integer periodNumber, PeriodAttendanceRequest request, Long operatorUserId) {

        accessControlService.checkMembership(operatorUserId, teamId, "TEAM");

        int presentCount = 0;
        int absentCount = 0;
        int alertCount = 0;

        for (PeriodAttendanceEntry entry : request.getEntries()) {
            PeriodAttendanceRecordEntity record = upsertRecord(
                    teamId, periodNumber, request.getAttendanceDate(), entry, operatorUserId);

            if (PRESENT_STATUSES.contains(record.getStatus())) {
                presentCount++;
            } else if (record.getStatus() == AttendanceStatus.ABSENT) {
                absentCount++;

                Optional<AttendanceTransitionAlertEntity> alert =
                        attendanceTransitionDetectionService.detectTransition(
                                teamId, entry.getStudentUserId(), request.getAttendanceDate(),
                                periodNumber, entry.getStatus());

                if (alert.isPresent()) {
                    alertCount++;
                }
            }
        }

        log.info("時限出欠一括登録完了: teamId={}, periodNumber={}, date={}, total={}, present={}, absent={}, alerts={}",
                teamId, periodNumber, request.getAttendanceDate(),
                request.getEntries().size(), presentCount, absentCount, alertCount);

        return PeriodAttendanceSummary.builder()
                .attendanceDate(request.getAttendanceDate())
                .teamId(teamId)
                .periodNumber(periodNumber)
                .totalCount(request.getEntries().size())
                .presentCount(presentCount)
                .absentCount(absentCount)
                .alertCount(alertCount)
                .recordedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 特定日・時限の出欠一覧を取得する。
     *
     * @param teamId        チームID
     * @param date          出欠対象日
     * @param periodNumber  時限番号
     * @param currentUserId 操作者ユーザーID
     * @return 出欠一覧レスポンス
     */
    public PeriodAttendanceListResponse getPeriodAttendance(
            Long teamId, LocalDate date, Integer periodNumber, Long currentUserId) {

        accessControlService.checkMembership(currentUserId, teamId, "TEAM");

        List<PeriodAttendanceRecordEntity> records =
                periodAttendanceRecordRepository.findByTeamIdAndAttendanceDateAndPeriodNumber(
                        teamId, date, periodNumber);

        List<PeriodAttendanceResponse> responses = records.stream()
                .map(PeriodAttendanceResponse::from)
                .toList();

        int presentCount = (int) records.stream()
                .filter(r -> PRESENT_STATUSES.contains(r.getStatus()))
                .count();
        int absentCount = (int) records.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.ABSENT)
                .count();

        return PeriodAttendanceListResponse.builder()
                .attendanceDate(date)
                .teamId(teamId)
                .periodNumber(periodNumber)
                .records(responses)
                .presentCount(presentCount)
                .absentCount(absentCount)
                .build();
    }

    /**
     * 時限の対象生徒一覧と直前時限ステータスを取得する（candidates エンドポイント用）。
     *
     * <p>チームメンバー取得の Repository が別途存在しない場合は、
     * 登録済みレコードから生徒一覧を逆引きする簡易実装を使用する。
     * Phase 3 でチームメンバー Repository との正式連携に置き換える予定。</p>
     *
     * @param teamId        チームID
     * @param date          出欠対象日
     * @param periodNumber  時限番号
     * @param currentUserId 操作者ユーザーID
     * @return 候補生徒一覧レスポンス
     */
    public PeriodCandidatesResponse getPeriodCandidates(
            Long teamId, LocalDate date, Integer periodNumber, Long currentUserId) {

        accessControlService.checkMembership(currentUserId, teamId, "TEAM");

        // 当該時限の既存レコードから生徒一覧を組み立てる（簡易実装）
        // TODO: Phase 3 でチームメンバーリポジトリと正式連携
        List<PeriodAttendanceRecordEntity> currentPeriodRecords =
                periodAttendanceRecordRepository.findByTeamIdAndAttendanceDateAndPeriodNumber(
                        teamId, date, periodNumber);

        Set<Long> registeredStudentIds = currentPeriodRecords.stream()
                .map(PeriodAttendanceRecordEntity::getStudentUserId)
                .collect(Collectors.toSet());

        // 直前時限（periodNumber - 1）の出欠を確認
        Integer previousPeriodNumber = periodNumber > 1 ? periodNumber - 1 : null;

        List<PeriodCandidatesResponse.CandidateItem> candidates = new ArrayList<>();

        for (Long studentUserId : registeredStudentIds) {
            AttendanceStatus previousStatus = null;

            if (previousPeriodNumber != null) {
                Optional<PeriodAttendanceRecordEntity> previousRecord =
                        periodAttendanceRecordRepository
                                .findByTeamIdAndStudentUserIdAndAttendanceDateAndPeriodNumber(
                                        teamId, studentUserId, date, previousPeriodNumber);
                previousStatus = previousRecord.map(PeriodAttendanceRecordEntity::getStatus).orElse(null);
            }

            candidates.add(PeriodCandidatesResponse.CandidateItem.builder()
                    .studentUserId(studentUserId)
                    .previousPeriodStatus(previousStatus)
                    .build());
        }

        return PeriodCandidatesResponse.builder()
                .attendanceDate(date)
                .teamId(teamId)
                .periodNumber(periodNumber)
                .candidates(candidates)
                .build();
    }

    /**
     * 時限出欠レコードを個別修正する。
     *
     * @param teamId         チームID
     * @param recordId       修正対象レコードID
     * @param request        修正リクエスト
     * @param operatorUserId 操作者ユーザーID
     * @return 修正後のレコードレスポンス
     * @throws BusinessException レコードが見つからない場合 PERIOD_RECORD_NOT_FOUND
     */
    @Transactional
    public PeriodAttendanceResponse updatePeriodRecord(
            Long teamId, Long recordId, PeriodAttendanceUpdateRequest request, Long operatorUserId) {

        accessControlService.checkMembership(operatorUserId, teamId, "TEAM");

        PeriodAttendanceRecordEntity existing = periodAttendanceRecordRepository.findById(recordId)
                .filter(r -> r.getTeamId().equals(teamId))
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.PERIOD_RECORD_NOT_FOUND));

        PeriodAttendanceRecordEntity updated = existing.toBuilder()
                .status(request.getStatus() != null ? request.getStatus() : existing.getStatus())
                .lateMinutes(request.getLateMinutes() != null ? request.getLateMinutes() : existing.getLateMinutes())
                .comment(request.getComment() != null ? request.getComment() : existing.getComment())
                .build();

        PeriodAttendanceRecordEntity saved = periodAttendanceRecordRepository.save(updated);

        log.info("時限出欠レコード修正: teamId={}, recordId={}, operatorUserId={}",
                teamId, recordId, operatorUserId);

        return PeriodAttendanceResponse.from(saved);
    }

    /**
     * 生徒の1日タイムラインを取得する。
     *
     * <p>暫定実装として currentUserId == studentUserId の場合のみ許可する。
     * Phase 3 で保護者・担任への権限拡張を予定。</p>
     *
     * @param studentUserId 生徒のユーザーID
     * @param date          対象日
     * @param currentUserId 操作者ユーザーID
     * @return タイムラインレスポンス
     */
    public StudentTimelineResponse getStudentDailyTimeline(
            Long studentUserId, LocalDate date, Long currentUserId) {

        // 暫定: 本人のみアクセス可
        // TODO: Phase 3 で保護者・担任への権限拡張
        if (!currentUserId.equals(studentUserId)) {
            throw new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002);
        }

        List<PeriodAttendanceRecordEntity> records =
                periodAttendanceRecordRepository
                        .findByStudentUserIdAndAttendanceDateOrderByPeriodNumberAsc(
                                studentUserId, date);

        List<PeriodAttendanceResponse> periods = records.stream()
                .map(PeriodAttendanceResponse::from)
                .toList();

        return StudentTimelineResponse.builder()
                .studentUserId(studentUserId)
                .attendanceDate(date)
                .periods(periods)
                .build();
    }

    // ========================================
    // 内部ユーティリティ
    // ========================================

    /**
     * 時限出欠レコードを upsert する。
     * 既存レコードがあれば更新、なければ新規作成する。
     */
    private PeriodAttendanceRecordEntity upsertRecord(
            Long teamId, Integer periodNumber, LocalDate attendanceDate,
            PeriodAttendanceEntry entry, Long operatorUserId) {

        Optional<PeriodAttendanceRecordEntity> existing =
                periodAttendanceRecordRepository.findByTeamIdAndStudentUserIdAndAttendanceDateAndPeriodNumber(
                        teamId, entry.getStudentUserId(), attendanceDate, periodNumber);

        PeriodAttendanceRecordEntity record;
        if (existing.isPresent()) {
            record = existing.get().toBuilder()
                    .status(entry.getStatus())
                    .lateMinutes(entry.getLateMinutes())
                    .comment(entry.getComment())
                    .recordedBy(operatorUserId)
                    .build();
        } else {
            record = PeriodAttendanceRecordEntity.builder()
                    .teamId(teamId)
                    .studentUserId(entry.getStudentUserId())
                    .attendanceDate(attendanceDate)
                    .periodNumber(periodNumber)
                    // Phase 3 で timetable_slots と連携予定
                    .subjectName(SUBJECT_NAME_DEFAULT)
                    .teacherUserId(operatorUserId)
                    .status(entry.getStatus())
                    .lateMinutes(entry.getLateMinutes())
                    .comment(entry.getComment())
                    .recordedBy(operatorUserId)
                    .build();
        }

        return periodAttendanceRecordRepository.save(record);
    }
}
