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
import com.mannschaft.app.school.event.DailyRollCallRecordedEvent;
import com.mannschaft.app.school.repository.DailyAttendanceRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 日次出欠サービス。
 *
 * <p>朝の点呼一括登録・出欠一覧取得・生徒履歴取得・個別修正の4操作を提供する。</p>
 *
 * <p>保護者への通知は業務トランザクションの<b>コミット後</b>に送る（Issue #2990 L6）。
 * 本サービスは登録した行の ID を載せた {@code DailyRollCallRecordedEvent} を publish するだけで、
 * {@code SchoolAttendanceNotificationService} の呼び出しは
 * {@code SchoolAttendanceNotificationListener}（{@code AFTER_COMMIT}）が行う。
 * 是正前は通知呼び出しが生徒ごとのループの内側にあり try も無かったため、生徒 1 人ぶんの
 * 通知失敗でその朝クラス全員ぶんの出欠記録が全件巻き戻っていた。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class DailyAttendanceService {

    private final DailyAttendanceRecordRepository dailyAttendanceRecordRepository;
    private final AccessControlService accessControlService;
    private final ApplicationEventPublisher eventPublisher;

    // ========================================
    // 朝の点呼一括登録
    // ========================================

    /**
     * 朝の点呼一括登録。
     *
     * <p>各生徒の DailyAttendanceRecordEntity を upsert する（既存なら更新、なければ新規作成）。
     * 保護者通知は業務コミット後に行うため、ここでは登録した行の ID を載せた
     * {@code DailyRollCallRecordedEvent} を publish するだけに留める（Issue #2990 L6）。</p>
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
        // 保護者通知の対象（Issue #2990 L6）。業務TX内では登録した行の ID を積むだけに留め、
        // 実配送は commit 後に SchoolAttendanceNotificationListener が行う。
        List<Long> recordedIds = new ArrayList<>();

        for (var entry : request.getEntries()) {
            var existing = dailyAttendanceRecordRepository
                    .findByTeamIdAndStudentUserIdAndAttendanceDate(
                            teamId, entry.getStudentUserId(), request.getAttendanceDate());

            DailyAttendanceRecordEntity record;
            if (existing.isPresent()) {
                // 既存レコードを更新
                // toBuilder().build() で作り直すと BaseEntity.id が引き継がれず INSERT 化する（行重複）。
                // managed entity を直接ミューテートし JPA dirty checking で UPDATE する。
                record = existing.get();
                record.applyRollCallUpdate(entry.getStatus(), entry.getAbsenceReason(),
                        entry.getArrivalTime(), entry.getLeaveTime(),
                        entry.getComment(), entry.getFamilyNoticeId(), operatorUserId);
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

            record = dailyAttendanceRecordRepository.save(record);
            recordedIds.add(record.getId());

            // 集計
            switch (entry.getStatus()) {
                case ATTENDING, PARTIAL -> presentCount++;
                case ABSENT -> absentCount++;
                case UNDECIDED -> undecidedCount++;
            }
        }

        // 保護者通知の配送要求（Issue #2990 L6）。業務TX内では publish だけに留める。
        // 生徒1人ぶんの通知失敗で、その朝クラス全員ぶんの出欠が巻き戻るのを防ぐ。
        if (!recordedIds.isEmpty()) {
            eventPublisher.publishEvent(new DailyRollCallRecordedEvent(teamId, List.copyOf(recordedIds)));
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

        // toBuilder().build() で作り直すと BaseEntity.id が引き継がれず INSERT 化する（行重複）。
        // managed entity を直接ミューテートし JPA dirty checking で UPDATE する。
        entity.applyUpdate(
                request.getStatus(), request.getAbsenceReason(),
                request.getArrivalTime(), request.getLeaveTime(),
                request.getComment(), operatorUserId);
        dailyAttendanceRecordRepository.save(entity);
        return DailyAttendanceResponse.from(entity);
    }
}
