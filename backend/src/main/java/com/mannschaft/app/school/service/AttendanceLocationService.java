package com.mannschaft.app.school.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.school.entity.AttendanceLocation;
import com.mannschaft.app.school.entity.AttendanceLocationChangeEntity;
import com.mannschaft.app.school.entity.AttendanceLocationChangeReason;
import com.mannschaft.app.school.entity.DailyAttendanceRecordEntity;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.repository.AttendanceLocationChangeRepository;
import com.mannschaft.app.school.repository.DailyAttendanceRecordRepository;
import com.mannschaft.app.school.repository.PeriodAttendanceRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 登校場所管理サービス。
 *
 * <p>生徒の登校場所変更の記録・取得・チームマップ取得の3操作を提供する。
 * 場所変更時は daily/period 出欠レコードの attendance_location も同時更新する。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceLocationService {

    private final DailyAttendanceRecordRepository dailyAttendanceRecordRepository;
    private final PeriodAttendanceRecordRepository periodAttendanceRecordRepository;
    private final AttendanceLocationChangeRepository attendanceLocationChangeRepository;

    // ========================================
    // 場所変更記録
    // ========================================

    /**
     * 登校場所変更を記録し、daily/period レコードの attendance_location を更新する。
     *
     * <p>処理手順:
     * <ol>
     *   <li>attendance_location_changes にINSERT</li>
     *   <li>daily_attendance_records.attendance_location を toLocation に更新</li>
     *   <li>daily_attendance_records.location_changed_during_day = true に更新</li>
     *   <li>changedAtPeriod が非 null の場合、その時限以降の period_attendance_records も更新</li>
     * </ol>
     * </p>
     *
     * @param teamId           クラスチームID
     * @param studentUserId    生徒のユーザーID
     * @param attendanceDate   対象日
     * @param fromLocation     変更前の場所
     * @param toLocation       変更後の場所
     * @param changedAtPeriod  変更が発生した時限番号（任意）
     * @param changedAtTime    変更が発生した時刻（任意）
     * @param reason           変更理由
     * @param note             備考（任意）
     * @param operatorUserId   記録者のユーザーID
     * @return 保存された場所変更履歴エンティティ
     */
    @Transactional
    public AttendanceLocationChangeEntity recordLocationChange(
            Long teamId, Long studentUserId, LocalDate attendanceDate,
            AttendanceLocation fromLocation, AttendanceLocation toLocation,
            Integer changedAtPeriod, LocalTime changedAtTime,
            AttendanceLocationChangeReason reason, String note, Long operatorUserId) {

        // 1. 場所変更履歴をINSERT
        AttendanceLocationChangeEntity changeEntity = AttendanceLocationChangeEntity.builder()
                .teamId(teamId)
                .studentUserId(studentUserId)
                .attendanceDate(attendanceDate)
                .fromLocation(fromLocation)
                .toLocation(toLocation)
                .changedAtPeriod(changedAtPeriod)
                .changedAtTime(changedAtTime)
                .reason(reason)
                .note(note)
                .recordedBy(operatorUserId)
                .build();

        AttendanceLocationChangeEntity saved = attendanceLocationChangeRepository.save(changeEntity);

        // 2-3. 日次出欠レコードの attendanceLocation・locationChangedDuringDay を更新
        DailyAttendanceRecordEntity dailyRecord = dailyAttendanceRecordRepository
                .findByTeamIdAndStudentUserIdAndAttendanceDate(teamId, studentUserId, attendanceDate)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.DAILY_RECORD_NOT_FOUND));

        DailyAttendanceRecordEntity updatedDailyRecord = dailyRecord.toBuilder()
                .attendanceLocation(toLocation)
                .locationChangedDuringDay(true)
                .build();
        dailyAttendanceRecordRepository.save(updatedDailyRecord);

        // 4. changedAtPeriod が非 null の場合、その時限以降の時限別出欠レコードを更新
        if (changedAtPeriod != null) {
            var periodRecords = periodAttendanceRecordRepository
                    .findByStudentUserIdAndAttendanceDateOrderByPeriodNumberAsc(studentUserId, attendanceDate)
                    .stream()
                    .filter(p -> p.getPeriodNumber() >= changedAtPeriod)
                    .toList();

            for (var periodRecord : periodRecords) {
                var updatedPeriodRecord = periodRecord.toBuilder()
                        .attendanceLocation(toLocation)
                        .build();
                periodAttendanceRecordRepository.save(updatedPeriodRecord);
            }

            log.info("登校場所変更: studentUserId={}, date={}, {}→{}, 第{}時限以降 {} 件更新",
                    studentUserId, attendanceDate, fromLocation, toLocation,
                    changedAtPeriod, periodRecords.size());
        } else {
            log.info("登校場所変更: studentUserId={}, date={}, {}→{}",
                    studentUserId, attendanceDate, fromLocation, toLocation);
        }

        return saved;
    }

    // ========================================
    // 個別生徒タイムライン取得
    // ========================================

    /**
     * 指定日の個別生徒の場所変更履歴を取得する。
     *
     * @param studentUserId  生徒のユーザーID
     * @param attendanceDate 対象日
     * @return 場所変更履歴一覧（記録日時昇順）
     */
    @Transactional(readOnly = true)
    public List<AttendanceLocationChangeEntity> getTimeline(Long studentUserId, LocalDate attendanceDate) {
        return attendanceLocationChangeRepository
                .findByStudentUserIdAndAttendanceDateOrderByRecordedAtAsc(studentUserId, attendanceDate);
    }

    // ========================================
    // チーム全体の最新ロケーションマップ取得
    // ========================================

    /**
     * 指定日のチーム全体の最新ロケーション一覧を取得する。
     *
     * <p>各生徒の当日最後の to_location を返す。
     * 場所変更がない生徒は日次出欠レコードの attendanceLocation を採用する。</p>
     *
     * @param teamId         クラスチームID
     * @param attendanceDate 対象日
     * @return 生徒ユーザーID → 最新ロケーション のマップ
     */
    @Transactional(readOnly = true)
    public Map<Long, AttendanceLocation> getTeamLocationMap(Long teamId, LocalDate attendanceDate) {
        // 当日のチーム全日次出欠レコードをベースにマップを構築
        Map<Long, AttendanceLocation> locationMap = new LinkedHashMap<>();
        dailyAttendanceRecordRepository
                .findByTeamIdAndAttendanceDate(teamId, attendanceDate)
                .forEach(r -> locationMap.put(r.getStudentUserId(), r.getAttendanceLocation()));

        // 場所変更履歴から最新 to_location で上書き（生徒ID昇順 → 記録日時昇順なので最後が最新）
        attendanceLocationChangeRepository
                .findByTeamIdAndAttendanceDateOrderByStudentUserIdAsc(teamId, attendanceDate)
                .forEach(c -> locationMap.put(c.getStudentUserId(), c.getToLocation()));

        return locationMap;
    }
}
