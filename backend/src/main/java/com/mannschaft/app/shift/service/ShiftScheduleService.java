package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.CreateShiftScheduleRequest;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.UpdateShiftScheduleRequest;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * シフトスケジュールサービス。シフトスケジュールのCRUD・ステータス遷移を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftScheduleService {

    private final ShiftScheduleRepository scheduleRepository;
    private final ShiftMapper shiftMapper;

    /**
     * チームのシフトスケジュール一覧を取得する。
     *
     * @param teamId チームID
     * @return シフトスケジュール一覧
     */
    public List<ShiftScheduleResponse> listSchedules(Long teamId) {
        List<ShiftScheduleEntity> entities = scheduleRepository.findByTeamIdOrderByStartDateDesc(teamId);
        return shiftMapper.toScheduleResponseList(entities);
    }

    /**
     * チームのシフトスケジュール一覧を期間指定で取得する。
     *
     * @param teamId チームID
     * @param from   期間開始
     * @param to     期間終了
     * @return シフトスケジュール一覧
     */
    public List<ShiftScheduleResponse> listSchedulesByPeriod(Long teamId, LocalDate from, LocalDate to) {
        List<ShiftScheduleEntity> entities = scheduleRepository
                .findByTeamIdAndStartDateBetweenOrderByStartDateDesc(teamId, from, to);
        return shiftMapper.toScheduleResponseList(entities);
    }

    /**
     * シフトスケジュールを単体取得する。
     *
     * @param id スケジュールID
     * @return シフトスケジュール
     */
    public ShiftScheduleResponse getSchedule(Long id) {
        ShiftScheduleEntity entity = findScheduleOrThrow(id);
        return shiftMapper.toScheduleResponse(entity);
    }

    /**
     * シフトスケジュールを作成する。
     *
     * @param teamId チームID
     * @param req    作成リクエスト
     * @param userId 作成者ID
     * @return 作成されたシフトスケジュール
     */
    @Transactional
    public ShiftScheduleResponse createSchedule(Long teamId, CreateShiftScheduleRequest req, Long userId) {
        validateDateRange(req.getStartDate(), req.getEndDate());

        ShiftScheduleEntity entity = ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title(req.getTitle())
                .periodType(req.getPeriodType() != null
                        ? ShiftPeriodType.valueOf(req.getPeriodType()) : ShiftPeriodType.WEEKLY)
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .requestDeadline(req.getRequestDeadline())
                .note(req.getNote())
                .createdBy(userId)
                .build();

        entity = scheduleRepository.save(entity);

        log.info("シフトスケジュール作成: id={}, teamId={}, title={}", entity.getId(), teamId, entity.getTitle());
        return shiftMapper.toScheduleResponse(entity);
    }

    /**
     * シフトスケジュールを更新する。
     *
     * @param id  スケジュールID
     * @param req 更新リクエスト
     * @return 更新されたシフトスケジュール
     */
    @Transactional
    public ShiftScheduleResponse updateSchedule(Long id, UpdateShiftScheduleRequest req) {
        ShiftScheduleEntity entity = findScheduleOrThrow(id);

        ShiftScheduleEntity.ShiftScheduleEntityBuilder builder = entity.toBuilder();

        if (req.getTitle() != null) builder.title(req.getTitle());
        if (req.getPeriodType() != null) builder.periodType(ShiftPeriodType.valueOf(req.getPeriodType()));
        if (req.getStartDate() != null) builder.startDate(req.getStartDate());
        if (req.getEndDate() != null) builder.endDate(req.getEndDate());
        if (req.getRequestDeadline() != null) builder.requestDeadline(req.getRequestDeadline());
        if (req.getNote() != null) builder.note(req.getNote());

        LocalDate startDate = req.getStartDate() != null ? req.getStartDate() : entity.getStartDate();
        LocalDate endDate = req.getEndDate() != null ? req.getEndDate() : entity.getEndDate();
        validateDateRange(startDate, endDate);

        entity = scheduleRepository.save(builder.build());

        log.info("シフトスケジュール更新: id={}", id);
        return shiftMapper.toScheduleResponse(entity);
    }

    /**
     * シフトスケジュールを論理削除する。
     *
     * @param id スケジュールID
     */
    @Transactional
    public void deleteSchedule(Long id) {
        ShiftScheduleEntity entity = findScheduleOrThrow(id);
        entity.softDelete();
        scheduleRepository.save(entity);
        log.info("シフトスケジュール削除: id={}", id);
    }

    /**
     * シフトスケジュールのステータスを遷移する。
     *
     * @param id     スケジュールID
     * @param status 遷移先ステータス
     * @param userId 操作者ID
     * @return 更新されたシフトスケジュール
     */
    @Transactional
    public ShiftScheduleResponse transitionStatus(Long id, String status, Long userId) {
        ShiftScheduleEntity entity = findScheduleOrThrow(id);
        ShiftScheduleStatus targetStatus = ShiftScheduleStatus.valueOf(status);

        switch (targetStatus) {
            case COLLECTING -> entity.startCollecting();
            case ADJUSTING -> entity.startAdjusting();
            case PUBLISHED -> entity.publish(userId);
            case ARCHIVED -> entity.archive();
            default -> throw new BusinessException(ShiftErrorCode.INVALID_SCHEDULE_STATUS);
        }

        entity = scheduleRepository.save(entity);
        log.info("シフトスケジュールステータス遷移: id={}, status={}", id, targetStatus);
        return shiftMapper.toScheduleResponse(entity);
    }

    /**
     * シフトスケジュールを複製する。
     *
     * @param id     複製元ID
     * @param userId 作成者ID
     * @return 複製されたシフトスケジュール
     */
    @Transactional
    public ShiftScheduleResponse duplicateSchedule(Long id, Long userId) {
        ShiftScheduleEntity source = findScheduleOrThrow(id);

        ShiftScheduleEntity duplicate = source.toBuilder()
                .status(ShiftScheduleStatus.DRAFT)
                .createdBy(userId)
                .publishedAt(null)
                .publishedBy(null)
                .isReminderSent(false)
                .isLowSubmissionAlerted(false)
                .lastAutoTransitionAt(null)
                .deletedAt(null)
                .build();

        duplicate = scheduleRepository.save(duplicate);
        log.info("シフトスケジュール複製: sourceId={}, newId={}", id, duplicate.getId());
        return shiftMapper.toScheduleResponse(duplicate);
    }

    /**
     * シフトスケジュールを取得する。存在しない場合は例外をスローする。
     */
    ShiftScheduleEntity findScheduleOrThrow(Long id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
    }

    /**
     * 開始日と終了日の整合性を検証する。
     */
    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessException(ShiftErrorCode.INVALID_DATE_RANGE);
        }
    }
}
