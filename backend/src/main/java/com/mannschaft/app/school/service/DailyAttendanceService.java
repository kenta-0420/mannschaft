package com.mannschaft.app.school.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.dto.AttendanceHistoryItem;
import com.mannschaft.app.school.dto.DailyAttendanceListResponse;
import com.mannschaft.app.school.dto.DailyAttendanceResponse;
import com.mannschaft.app.school.dto.DailyAttendanceUpdateRequest;
import com.mannschaft.app.school.dto.DailyRollCallRequest;
import com.mannschaft.app.school.dto.DailyRollCallSummary;
import com.mannschaft.app.school.entity.DailyAttendanceRecordEntity;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.repository.DailyAttendanceRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 日次出欠サービス。
 *
 * <p>朝の点呼一括登録・出欠一覧取得・生徒履歴取得・個別修正の4操作を提供する。
 * 登録後、SchoolAttendanceNotificationService 経由で保護者への通知を送信する。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class DailyAttendanceService {

    private final DailyAttendanceRecordRepository dailyAttendanceRecordRepository;
    private final AccessControlService accessControlService;
    private final SchoolAttendanceNotificationService notificationService;

    // ========================================
    // 朝の点呼一括登録
    // ========================================

    /**
     * 朝の点呼一括登録。
     *
     * <p>各生徒の DailyAttendanceRecordEntity を upsert する（既存なら更新、なければ新規作成）。
     * 登録後、各生徒の status に応じて SchoolAttendanceNotificationService.notifyDailyAttendance() を呼び出す。</p>
     *
     * @param teamId          クラスチームID
     * @param request         点呼一括登録リクエスト
     * @param operatorUserId  操作者（担任）のユーザーID
     * @return 点呼登録結果サマリ
     */
    public DailyRollCallSummary submitDailyRollCall(Long teamId, DailyRollCallRequest request, Long operatorUserId) {
        accessControlService.checkMembership(operatorUserId, teamId, "TEAM");

        int presentCount = 0;
        int absentCount = 0;
        int undecidedCount = 0;

        for (var entry : request.getEntries()) {
            var existing = dailyAttendanceRecordRepository
                    .findByTeamIdAndStudentUserIdAndAttendanceDate(
                            teamId, entry.getStudentUserId(), request.getAttendanceDate());

            DailyAttendanceRecordEntity record;
            if (existing.isPresent()) {
                // 既存レコードを更新
                record = existing.get().toBuilder()
                        .status(entry.getStatus())
                        .absenceReason(entry.getAbsenceReason())
                        .arrivalTime(entry.getArrivalTime())
                        .leaveTime(entry.getLeaveTime())
                        .comment(entry.getComment())
                        .familyNoticeId(entry.getFamilyNoticeId())
                        .recordedBy(operatorUserId)
                        .build();
            } else {
                // 新規レコードを作成
                record = DailyAttendanceRecordEntity.builder()
                        .teamId(teamId)
                        .studentUserId(entry.getStudentUserId())
                        .attendanceDate(request.getAttendanceDate())
                        .status(entry.getStatus())
                        .absenceReason(entry.getAbsenceReason())
                        .arrivalTime(entry.getArrivalTime())
                        .leaveTime(entry.getLeaveTime())
                        .comment(entry.getComment())
                        .familyNoticeId(entry.getFamilyNoticeId())
                        .recordedBy(operatorUserId)
                        .build();
            }

            dailyAttendanceRecordRepository.save(record);

            // 通知送信
            notificationService.notifyDailyAttendance(
                    entry.getStudentUserId(), request.getAttendanceDate(), entry.getStatus());

            // 集計
            switch (entry.getStatus()) {
                case ATTENDING, PARTIAL -> presentCount++;
                case ABSENT -> absentCount++;
                case UNDECIDED -> undecidedCount++;
            }
        }

        return DailyRollCallSummary.builder()
                .attendanceDate(request.getAttendanceDate())
                .teamId(teamId)
                .totalCount(request.getEntries().size())
                .presentCount(presentCount)
                .absentCount(absentCount)
                .undecidedCount(undecidedCount)
                .recordedAt(LocalDateTime.now())
                .build();
    }

    // ========================================
    // 出欠一覧取得
    // ========================================

    /**
     * 特定日のクラス日次出欠一覧取得。
     *
     * @param teamId        クラスチームID
     * @param date          対象日
     * @param currentUserId 現在のユーザーID
     * @return 日次出欠一覧レスポンス
     */
    @Transactional(readOnly = true)
    public DailyAttendanceListResponse getDailyAttendance(Long teamId, LocalDate date, Long currentUserId) {
        accessControlService.checkMembership(currentUserId, teamId, "TEAM");

        List<DailyAttendanceRecordEntity> records =
                dailyAttendanceRecordRepository.findByTeamIdAndAttendanceDate(teamId, date);

        List<DailyAttendanceResponse> responseList = records.stream()
                .map(DailyAttendanceResponse::from)
                .toList();

        int presentCount = (int) records.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.ATTENDING
                        || r.getStatus() == AttendanceStatus.PARTIAL)
                .count();
        int absentCount = (int) records.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.ABSENT)
                .count();
        int undecidedCount = (int) records.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.UNDECIDED)
                .count();

        return DailyAttendanceListResponse.builder()
                .attendanceDate(date)
                .teamId(teamId)
                .records(responseList)
                .totalCount(records.size())
                .presentCount(presentCount)
                .absentCount(absentCount)
                .undecidedCount(undecidedCount)
                .build();
    }

    // ========================================
    // 生徒の出欠履歴取得
    // ========================================

    /**
     * 生徒の日次出欠履歴取得（/me/attendance/daily）。
     *
     * <p>本 Phase では currentUserId == studentUserId のみをチェックする。
     * Phase 3 以降で保護者・担任によるアクセスも許可する予定。</p>
     *
     * @param studentUserId  対象生徒のユーザーID
     * @param from           開始日
     * @param to             終了日
     * @param currentUserId  現在のユーザーID
     * @return 出欠履歴アイテム一覧
     */
    @Transactional(readOnly = true)
    public List<AttendanceHistoryItem> getStudentHistory(
            Long studentUserId, LocalDate from, LocalDate to, Long currentUserId) {
        // 本 Phase では本人確認のみ（Phase 3 で保護者・担任アクセスを拡張）
        if (!currentUserId.equals(studentUserId)) {
            throw new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002);
        }

        return dailyAttendanceRecordRepository
                .findByStudentUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(studentUserId, from, to)
                .stream()
                .map(AttendanceHistoryItem::from)
                .toList();
    }

    // ========================================
    // 日次出欠個別修正
    // ========================================

    /**
     * 日次出欠個別修正。
     *
     * <p>null フィールドは変更しない（部分更新）。</p>
     *
     * @param teamId          クラスチームID
     * @param recordId        対象レコードID
     * @param request         修正リクエスト
     * @param operatorUserId  操作者（担任）のユーザーID
     * @return 更新後の日次出欠レスポンス
     */
    public DailyAttendanceResponse updateDailyRecord(
            Long teamId, Long recordId, DailyAttendanceUpdateRequest request, Long operatorUserId) {
        accessControlService.checkMembership(operatorUserId, teamId, "TEAM");

        DailyAttendanceRecordEntity entity = dailyAttendanceRecordRepository.findById(recordId)
                .filter(r -> r.getTeamId().equals(teamId))
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.DAILY_RECORD_NOT_FOUND));

        DailyAttendanceRecordEntity updated = entity.toBuilder()
                .status(request.getStatus() != null ? request.getStatus() : entity.getStatus())
                .absenceReason(request.getAbsenceReason() != null ? request.getAbsenceReason() : entity.getAbsenceReason())
                .arrivalTime(request.getArrivalTime() != null ? request.getArrivalTime() : entity.getArrivalTime())
                .leaveTime(request.getLeaveTime() != null ? request.getLeaveTime() : entity.getLeaveTime())
                .comment(request.getComment() != null ? request.getComment() : entity.getComment())
                .recordedBy(operatorUserId)
                .build();

        DailyAttendanceRecordEntity saved = dailyAttendanceRecordRepository.save(updated);
        return DailyAttendanceResponse.from(saved);
    }
}
