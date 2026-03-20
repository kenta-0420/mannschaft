package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.CommentOption;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.dto.AttendanceRequest;
import com.mannschaft.app.schedule.dto.AttendanceResponse;
import com.mannschaft.app.schedule.dto.AttendanceStatsResponse;
import com.mannschaft.app.schedule.dto.AttendanceSummaryResponse;
import com.mannschaft.app.schedule.dto.BulkAttendanceRequest;
import com.mannschaft.app.schedule.dto.SurveyResponseRequest;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.event.AttendanceRespondedEvent;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 出欠管理サービス。出欠回答・集計・CSV出力・統計を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleAttendanceService {

    private static final String CSV_HEADER = "ユーザーID,ステータス,コメント,回答日時";

    private final ScheduleAttendanceRepository attendanceRepository;
    private final ScheduleService scheduleService;
    private final EventSurveyService eventSurveyService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 出欠回答を行う。期限チェック・コメント必須チェックを実施し、
     * アンケート回答がある場合は同時に保存する。
     *
     * @param scheduleId スケジュールID
     * @param userId     ユーザーID
     * @param req        出欠回答リクエスト
     * @return 出欠回答レスポンス
     */
    @Transactional
    public AttendanceResponse respondAttendance(Long scheduleId, Long userId, AttendanceRequest req) {
        ScheduleEntity schedule = scheduleService.getSchedule(scheduleId);
        validateAttendanceRequired(schedule);
        validateAttendanceDeadline(schedule);
        validateComment(schedule, req.getComment());

        AttendanceStatus newStatus = AttendanceStatus.valueOf(req.getStatus());

        ScheduleAttendanceEntity attendance = attendanceRepository
                .findByScheduleIdAndUserId(scheduleId, userId)
                .orElseThrow(() -> new BusinessException(ScheduleErrorCode.SCHEDULE_NOT_FOUND));

        attendance.respond(newStatus, req.getComment());
        attendance = attendanceRepository.save(attendance);

        // アンケート回答の同時保存
        if (req.getSurveyResponses() != null && !req.getSurveyResponses().isEmpty()) {
            for (SurveyResponseRequest surveyReq : req.getSurveyResponses()) {
                eventSurveyService.respondToSurvey(surveyReq.getSurveyId(), userId, surveyReq);
            }
        }

        // イベント発行（トランザクションコミット後に発行）
        eventPublisher.publishEvent(new AttendanceRespondedEvent(
                scheduleId, userId, newStatus.name()));

        log.info("出欠回答: scheduleId={}, userId={}, status={}", scheduleId, userId, newStatus);
        return toAttendanceResponse(attendance);
    }

    /**
     * スケジュールの出欠一覧を取得する。
     *
     * @param scheduleId スケジュールID
     * @return 出欠回答一覧
     */
    public List<AttendanceResponse> getAttendances(Long scheduleId) {
        scheduleService.getSchedule(scheduleId);
        return attendanceRepository.findByScheduleIdOrderByUserIdAsc(scheduleId).stream()
                .map(this::toAttendanceResponse)
                .toList();
    }

    /**
     * 出欠集計サマリーを取得する。ATTENDING/PARTIAL/ABSENT/UNDECIDED の各件数を返す。
     *
     * @param scheduleId スケジュールID
     * @return 出欠サマリー
     */
    public AttendanceSummaryResponse getAttendanceSummary(Long scheduleId) {
        scheduleService.getSchedule(scheduleId);

        Map<AttendanceStatus, Integer> countMap = new HashMap<>();
        for (AttendanceStatus status : AttendanceStatus.values()) {
            countMap.put(status, 0);
        }

        List<Object[]> results = attendanceRepository.countByScheduleIdGroupByStatus(scheduleId);
        for (Object[] row : results) {
            AttendanceStatus status = (AttendanceStatus) row[0];
            int count = ((Long) row[1]).intValue();
            countMap.put(status, count);
        }

        int total = countMap.values().stream().mapToInt(Integer::intValue).sum();

        return new AttendanceSummaryResponse(
                countMap.get(AttendanceStatus.ATTENDING),
                countMap.get(AttendanceStatus.PARTIAL),
                countMap.get(AttendanceStatus.ABSENT),
                countMap.get(AttendanceStatus.UNDECIDED),
                total);
    }

    /**
     * 管理者による出欠一括更新を行う。
     *
     * @param scheduleId スケジュールID
     * @param req        一括出欠リクエスト
     */
    @Transactional
    public void bulkUpdateAttendances(Long scheduleId, BulkAttendanceRequest req) {
        ScheduleEntity schedule = scheduleService.getSchedule(scheduleId);
        validateAttendanceRequired(schedule);

        for (BulkAttendanceRequest.BulkAttendanceItem item : req.getAttendances()) {
            ScheduleAttendanceEntity attendance = attendanceRepository
                    .findByScheduleIdAndUserId(scheduleId, item.userId())
                    .orElse(null);

            if (attendance != null) {
                AttendanceStatus newStatus = AttendanceStatus.valueOf(item.status());
                attendance.respond(newStatus, item.comment());
                attendanceRepository.save(attendance);
            }
        }

        log.info("出欠一括更新: scheduleId={}, 件数={}", scheduleId, req.getAttendances().size());
    }

    /**
     * 出欠一覧をCSV文字列として出力する。
     *
     * @param scheduleId スケジュールID
     * @return CSV文字列
     */
    public String exportAttendancesCsv(Long scheduleId) {
        scheduleService.getSchedule(scheduleId);
        List<ScheduleAttendanceEntity> attendances = attendanceRepository
                .findByScheduleIdOrderByUserIdAsc(scheduleId);

        StringJoiner csv = new StringJoiner("\n");
        csv.add(CSV_HEADER);

        for (ScheduleAttendanceEntity a : attendances) {
            String comment = a.getComment() != null ? "\"" + a.getComment().replace("\"", "\"\"") + "\"" : "";
            String respondedAt = a.getRespondedAt() != null ? a.getRespondedAt().toString() : "";
            csv.add(a.getUserId() + "," + a.getStatus().name() + "," + comment + "," + respondedAt);
        }

        return csv.toString();
    }

    /**
     * 対象メンバーの出欠レコードを一括生成する。スケジュール作成時にイベントリスナーから呼ばれる。
     *
     * @param scheduleId    スケジュールID
     * @param memberUserIds 対象メンバーのユーザーIDリスト
     */
    @Transactional
    public void generateAttendanceRecords(Long scheduleId, List<Long> memberUserIds) {
        for (Long userId : memberUserIds) {
            ScheduleAttendanceEntity attendance = ScheduleAttendanceEntity.builder()
                    .scheduleId(scheduleId)
                    .userId(userId)
                    .status(AttendanceStatus.UNDECIDED)
                    .build();
            attendanceRepository.save(attendance);
        }

        log.info("出欠レコード生成: scheduleId={}, 件数={}", scheduleId, memberUserIds.size());
    }

    /**
     * チームの出席率統計を取得する。
     *
     * @param teamId チームID
     * @param from   期間開始
     * @param to     期間終了
     * @return 出席率統計（ユーザー別）
     */
    public List<AttendanceStatsResponse> getTeamAttendanceStats(Long teamId,
                                                                  LocalDateTime from, LocalDateTime to) {
        // TODO: チームメンバー一覧取得 → 各メンバーの出欠集計
        // team_memberships と schedule_attendances をJOINして集計
        return List.of();
    }

    /**
     * 組織の出席率統計を取得する。
     *
     * @param orgId 組織ID
     * @param from  期間開始
     * @param to    期間終了
     * @return 出席率統計（ユーザー別）
     */
    public List<AttendanceStatsResponse> getOrgAttendanceStats(Long orgId,
                                                                 LocalDateTime from, LocalDateTime to) {
        // TODO: 組織メンバー一覧取得 → 各メンバーの出欠集計
        return List.of();
    }

    /**
     * 個人の出席率統計を取得する。
     *
     * @param userId ユーザーID
     * @param from   期間開始
     * @param to     期間終了
     * @return 出席率統計
     */
    public AttendanceStatsResponse getMyAttendanceStats(Long userId, LocalDateTime from, LocalDateTime to) {
        // TODO: ユーザーが所属するチーム・組織のスケジュール出欠を集計
        return new AttendanceStatsResponse(userId, 0, 0, 0, 0, 0.0);
    }

    // --- プライベートメソッド ---

    /**
     * 出欠管理が有効なスケジュールかどうかを検証する。
     */
    private void validateAttendanceRequired(ScheduleEntity schedule) {
        if (!Boolean.TRUE.equals(schedule.getAttendanceRequired())) {
            throw new BusinessException(ScheduleErrorCode.ATTENDANCE_NOT_REQUIRED);
        }
    }

    /**
     * 出欠回答期限を検証する。
     */
    private void validateAttendanceDeadline(ScheduleEntity schedule) {
        if (schedule.getAttendanceDeadline() != null
                && LocalDateTime.now().isAfter(schedule.getAttendanceDeadline())) {
            throw new BusinessException(ScheduleErrorCode.ATTENDANCE_DEADLINE_PASSED);
        }
    }

    /**
     * コメント必須チェックを行う。commentOption が REQUIRED の場合、コメントが空なら例外をスローする。
     */
    private void validateComment(ScheduleEntity schedule, String comment) {
        if (schedule.getCommentOption() == CommentOption.REQUIRED
                && (comment == null || comment.isBlank())) {
            throw new BusinessException(ScheduleErrorCode.COMMENT_REQUIRED);
        }
    }

    /**
     * エンティティを出欠回答レスポンスDTOに変換する。
     */
    private AttendanceResponse toAttendanceResponse(ScheduleAttendanceEntity entity) {
        return new AttendanceResponse(
                entity.getId(),
                entity.getUserId(),
                entity.getStatus().name(),
                entity.getComment(),
                entity.getRespondedAt());
    }
}
