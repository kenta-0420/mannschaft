package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.ShiftPreference;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.CreateShiftRequestRequest;
import com.mannschaft.app.shift.dto.ShiftRequestResponse;
import com.mannschaft.app.shift.dto.ShiftRequestSummaryResponse;
import com.mannschaft.app.shift.dto.UpdateShiftRequestRequest;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * シフト希望サービス。メンバーのシフト希望提出・更新・集計を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftRequestService {

    private final ShiftRequestRepository requestRepository;
    private final ShiftScheduleService scheduleService;
    private final ShiftMapper shiftMapper;

    /**
     * スケジュールのシフト希望一覧を取得する。
     *
     * @param scheduleId スケジュールID
     * @return シフト希望一覧
     */
    public List<ShiftRequestResponse> listRequests(Long scheduleId) {
        List<ShiftRequestEntity> entities = requestRepository.findByScheduleIdOrderBySlotDateAsc(scheduleId);
        return shiftMapper.toRequestResponseList(entities);
    }

    /**
     * 自分のシフト希望一覧を取得する。
     *
     * @param userId ユーザーID
     * @return シフト希望一覧
     */
    public List<ShiftRequestResponse> listMyRequests(Long userId) {
        List<ShiftRequestEntity> entities = requestRepository.findByUserIdOrderBySlotDateDesc(userId);
        return shiftMapper.toRequestResponseList(entities);
    }

    /**
     * シフト希望を提出する。
     *
     * @param req    提出リクエスト
     * @param userId ユーザーID
     * @return 提出されたシフト希望
     */
    @Transactional
    public ShiftRequestResponse submitRequest(CreateShiftRequestRequest req, Long userId) {
        ShiftScheduleEntity schedule = scheduleService.findScheduleOrThrow(req.getScheduleId());
        validateCollectingStatus(schedule);
        validateRequestDeadline(schedule);

        // 重複チェック
        requestRepository.findByScheduleIdAndUserIdAndSlotDate(req.getScheduleId(), userId, req.getSlotDate())
                .ifPresent(existing -> {
                    throw new BusinessException(ShiftErrorCode.REQUEST_ALREADY_EXISTS);
                });

        ShiftRequestEntity entity = ShiftRequestEntity.builder()
                .scheduleId(req.getScheduleId())
                .userId(userId)
                .slotId(req.getSlotId())
                .slotDate(req.getSlotDate())
                .preference(ShiftPreference.valueOf(req.getPreference()))
                .note(req.getNote())
                .build();

        entity = requestRepository.save(entity);
        log.info("シフト希望提出: id={}, scheduleId={}, userId={}", entity.getId(), req.getScheduleId(), userId);
        return shiftMapper.toRequestResponse(entity);
    }

    /**
     * シフト希望を更新する。
     *
     * @param requestId リクエストID
     * @param req       更新リクエスト
     * @param userId    ユーザーID
     * @return 更新されたシフト希望
     */
    @Transactional
    public ShiftRequestResponse updateRequest(Long requestId, UpdateShiftRequestRequest req, Long userId) {
        ShiftRequestEntity entity = findRequestOrThrow(requestId);

        ShiftScheduleEntity schedule = scheduleService.findScheduleOrThrow(entity.getScheduleId());
        validateCollectingStatus(schedule);
        validateRequestDeadline(schedule);

        entity.updatePreference(ShiftPreference.valueOf(req.getPreference()), req.getNote());
        entity = requestRepository.save(entity);

        log.info("シフト希望更新: id={}", requestId);
        return shiftMapper.toRequestResponse(entity);
    }

    /**
     * シフト希望を削除する。
     *
     * @param requestId リクエストID
     */
    @Transactional
    public void deleteRequest(Long requestId) {
        ShiftRequestEntity entity = findRequestOrThrow(requestId);
        requestRepository.delete(entity);
        log.info("シフト希望削除: id={}", requestId);
    }

    /**
     * シフト希望提出サマリーを取得する。
     *
     * @param scheduleId スケジュールID
     * @return 提出サマリー
     */
    public ShiftRequestSummaryResponse getRequestSummary(Long scheduleId) {
        long submittedCount = requestRepository.countDistinctUserIdByScheduleId(scheduleId);
        // TODO: チームメンバー数はチーム管理Serviceから取得する
        long totalMembers = 0;
        long pendingCount = totalMembers - submittedCount;

        return new ShiftRequestSummaryResponse(scheduleId, totalMembers, submittedCount, pendingCount);
    }

    /**
     * シフト希望を取得する。存在しない場合は例外をスローする。
     */
    private ShiftRequestEntity findRequestOrThrow(Long id) {
        return requestRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND));
    }

    /**
     * スケジュールが希望収集中であることを検証する。
     */
    private void validateCollectingStatus(ShiftScheduleEntity schedule) {
        if (schedule.getStatus() != ShiftScheduleStatus.COLLECTING) {
            throw new BusinessException(ShiftErrorCode.INVALID_SCHEDULE_STATUS);
        }
    }

    /**
     * 希望提出期限を過ぎていないことを検証する。
     */
    private void validateRequestDeadline(ShiftScheduleEntity schedule) {
        if (schedule.getRequestDeadline() != null
                && LocalDateTime.now().isAfter(schedule.getRequestDeadline())) {
            throw new BusinessException(ShiftErrorCode.REQUEST_DEADLINE_PASSED);
        }
    }
}
