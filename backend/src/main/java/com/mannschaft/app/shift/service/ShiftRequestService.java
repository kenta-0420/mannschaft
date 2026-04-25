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
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
    private final UserRoleRepository userRoleRepository;

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
     * <p>v2 拡張: 5 段階 preference 別カウント（PREFERRED / AVAILABLE / WEAK_REST /
     * STRONG_REST / ABSOLUTE_REST）を 1 クエリで集計して返却する。</p>
     *
     * @param scheduleId スケジュールID
     * @return 提出サマリー
     */
    public ShiftRequestSummaryResponse getRequestSummary(Long scheduleId) {
        long submittedCount = requestRepository.countDistinctUserIdByScheduleId(scheduleId);
        ShiftScheduleEntity schedule = scheduleService.findScheduleOrThrow(scheduleId);
        long totalMembers = userRoleRepository.countByTeamId(schedule.getTeamId());
        long pendingCount = Math.max(0, totalMembers - submittedCount);

        Map<ShiftPreference, Long> preferenceCounts = aggregatePreferenceCounts(scheduleId);

        return new ShiftRequestSummaryResponse(
                scheduleId,
                totalMembers,
                submittedCount,
                pendingCount,
                preferenceCounts.getOrDefault(ShiftPreference.PREFERRED, 0L),
                preferenceCounts.getOrDefault(ShiftPreference.AVAILABLE, 0L),
                preferenceCounts.getOrDefault(ShiftPreference.WEAK_REST, 0L),
                preferenceCounts.getOrDefault(ShiftPreference.STRONG_REST, 0L),
                preferenceCounts.getOrDefault(ShiftPreference.ABSOLUTE_REST, 0L));
    }

    /**
     * スケジュール単位の 5 段階 preference 別件数を集計する。
     *
     * <p>DB への 1 クエリで取得した結果を {@link EnumMap} に詰め替えて返却。
     * 集計対象が存在しない preference は Map に含まれず、呼び出し側で {@code 0} として扱う。</p>
     */
    private Map<ShiftPreference, Long> aggregatePreferenceCounts(Long scheduleId) {
        Map<ShiftPreference, Long> counts = new EnumMap<>(ShiftPreference.class);
        List<Object[]> rows = requestRepository.countByPreferenceForSchedule(scheduleId);
        for (Object[] row : rows) {
            ShiftPreference preference = (ShiftPreference) row[0];
            Long count = (Long) row[1];
            if (preference != null && count != null) {
                counts.put(preference, count);
            }
        }
        return counts;
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
