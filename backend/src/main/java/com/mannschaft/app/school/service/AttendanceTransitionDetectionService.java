package com.mannschaft.app.school.service;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.entity.AttendanceTransitionAlertEntity;
import com.mannschaft.app.school.entity.PeriodAttendanceRecordEntity;
import com.mannschaft.app.school.entity.TransitionAlertLevel;
import com.mannschaft.app.school.repository.AttendanceTransitionAlertRepository;
import com.mannschaft.app.school.repository.PeriodAttendanceRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 「前にいたのに今いない」移動検知サービス。
 *
 * <p>設計書 §4.2 に基づき、時限出欠登録後に以下のロジックで移動アラートを生成する:</p>
 * <ul>
 *   <li>ABSENT 登録の場合のみ検知対象</li>
 *   <li>直前時限が ATTENDING または PARTIAL の場合にアラート生成</li>
 *   <li>既に未解決アラートが存在する場合は重複生成しない</li>
 *   <li>2時限連続 ABSENT の場合は URGENT アラートを生成</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AttendanceTransitionDetectionService {

    private static final Set<AttendanceStatus> PRESENT_STATUSES = Set.of(
            AttendanceStatus.ATTENDING, AttendanceStatus.PARTIAL);

    /** 通知済みユーザー未設定時の初期値。Phase 3/4 で実通知処理を実装する。 */
    private static final String EMPTY_NOTIFIED_USERS = "[]";

    private final PeriodAttendanceRecordRepository periodAttendanceRecordRepository;
    private final AttendanceTransitionAlertRepository attendanceTransitionAlertRepository;

    /**
     * 時限出欠登録後に移動検知を実行し、必要ならアラートを生成する。
     *
     * <p>ABSENT でなければ即座に empty を返す。直前時限に出席記録があり、
     * かつ未解決アラートが存在しない場合のみアラートを生成する。</p>
     *
     * @param teamId         チームID
     * @param studentUserId  生徒のユーザーID
     * @param date           出欠対象日
     * @param currentPeriod  現在の時限番号
     * @param currentStatus  現在の出欠ステータス
     * @return 新規生成されたアラートエンティティ（検知なしの場合は empty）
     */
    public Optional<AttendanceTransitionAlertEntity> detectTransition(
            Long teamId, Long studentUserId, LocalDate date,
            Integer currentPeriod, AttendanceStatus currentStatus) {

        // ABSENT 以外は検知不要
        if (currentStatus != AttendanceStatus.ABSENT) {
            return Optional.empty();
        }

        // 直前時限を取得（当日・同チーム・currentPeriod より小さい最大時限）
        List<PeriodAttendanceRecordEntity> previousRecords =
                periodAttendanceRecordRepository
                        .findByTeamIdAndStudentUserIdAndAttendanceDateAndPeriodNumberLessThanOrderByPeriodNumberDesc(
                                teamId, studentUserId, date, currentPeriod);

        if (previousRecords.isEmpty()) {
            return Optional.empty();
        }

        PeriodAttendanceRecordEntity previousRecord = previousRecords.get(0);

        // 直前時限が出席系ステータスでなければ検知不要
        if (!PRESENT_STATUSES.contains(previousRecord.getStatus())) {
            return Optional.empty();
        }

        // 既に未解決アラートが存在する場合は重複生成しない
        if (attendanceTransitionAlertRepository
                .existsByTeamIdAndStudentUserIdAndAttendanceDateAndResolvedAtIsNull(
                        teamId, studentUserId, date)) {
            log.debug("移動検知アラートスキップ（重複防止）: teamId={}, studentUserId={}, date={}, period={}",
                    teamId, studentUserId, date, currentPeriod);
            return Optional.empty();
        }

        // alert_level 判定: 2時限前も ABSENT なら URGENT
        TransitionAlertLevel alertLevel = determineAlertLevel(teamId, studentUserId, date, previousRecord);

        AttendanceTransitionAlertEntity alert = AttendanceTransitionAlertEntity.builder()
                .teamId(teamId)
                .studentUserId(studentUserId)
                .attendanceDate(date)
                .previousPeriodNumber(previousRecord.getPeriodNumber())
                .currentPeriodNumber(currentPeriod)
                .previousPeriodStatus(previousRecord.getStatus())
                .currentPeriodStatus(currentStatus)
                .alertLevel(alertLevel)
                .notifiedUsers(EMPTY_NOTIFIED_USERS)
                .build();

        AttendanceTransitionAlertEntity saved = attendanceTransitionAlertRepository.save(alert);

        log.info("移動検知アラート生成: teamId={}, studentUserId={}, date={}, previousPeriod={}, currentPeriod={}, alertLevel={}",
                teamId, studentUserId, date,
                previousRecord.getPeriodNumber(), currentPeriod, alertLevel);

        // TODO: Phase 3/4 で通知処理を実装する
        // notificationService.notifyTransitionAlert(saved);

        return Optional.of(saved);
    }

    /**
     * アラートレベルを判定する。
     *
     * <p>直前時限の1つ前の時限も ABSENT であれば 2時限連続 ABSENT として URGENT を返す。</p>
     */
    private TransitionAlertLevel determineAlertLevel(
            Long teamId, Long studentUserId, LocalDate date,
            PeriodAttendanceRecordEntity previousRecord) {

        // 直前時限より前の時限も確認する（2時限連続 ABSENT 判定）
        if (previousRecord.getPeriodNumber() <= 1) {
            return TransitionAlertLevel.NORMAL;
        }

        List<PeriodAttendanceRecordEntity> beforePreviousRecords =
                periodAttendanceRecordRepository
                        .findByTeamIdAndStudentUserIdAndAttendanceDateAndPeriodNumberLessThanOrderByPeriodNumberDesc(
                                teamId, studentUserId, date, previousRecord.getPeriodNumber());

        if (!beforePreviousRecords.isEmpty()
                && beforePreviousRecords.get(0).getStatus() == AttendanceStatus.ABSENT) {
            return TransitionAlertLevel.URGENT;
        }

        return TransitionAlertLevel.NORMAL;
    }
}
