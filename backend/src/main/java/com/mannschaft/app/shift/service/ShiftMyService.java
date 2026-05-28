package com.mannschaft.app.shift.service;

import com.mannschaft.app.shift.ShiftAssignmentStatus;
import com.mannschaft.app.shift.dto.MyConfirmedSlotResponse;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ログインユーザー自身のシフト情報取得サービス。
 *
 * <p>GET /api/v1/shifts/my/** に対応するユーザー向けシフト照会機能を提供する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftMyService {

    private final ShiftAssignmentRepository assignmentRepository;
    private final ShiftSlotRepository slotRepository;
    private final ShiftScheduleRepository scheduleRepository;
    private final ShiftPositionRepository positionRepository;
    /** クロスドメインFK禁止の原則に従い、teamId は Long で保持し TeamRepository 経由で名前を取得する */
    private final TeamRepository teamRepository;

    /**
     * ログインユーザーの確定シフト枠一覧を取得する。
     *
     * <p>ShiftAssignment.status = CONFIRMED のみを対象とする。
     * N+1 クエリを防ぐため、slot・schedule・position・team を一括取得してマップ化する。</p>
     *
     * @param userId ログインユーザーID
     * @return 確定シフト枠レスポンスのリスト（日付昇順・開始時刻昇順）
     */
    public List<MyConfirmedSlotResponse> getMyConfirmedSlots(Long userId) {
        // 1. ユーザーの確定割当を全件取得
        List<ShiftAssignmentEntity> assignments = assignmentRepository
                .findAllByUserIdAndStatus(userId, ShiftAssignmentStatus.CONFIRMED);

        if (assignments.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. slotId 一覧から ShiftSlot を一括取得
        Set<Long> slotIds = assignments.stream()
                .map(ShiftAssignmentEntity::getSlotId)
                .collect(Collectors.toSet());
        Map<Long, ShiftSlotEntity> slotMap = slotRepository.findAllByIdIn(slotIds).stream()
                .collect(Collectors.toMap(s -> s.getId(), s -> s));

        // 3. scheduleId 一覧から ShiftSchedule を一括取得
        Set<Long> scheduleIds = slotMap.values().stream()
                .map(ShiftSlotEntity::getScheduleId)
                .collect(Collectors.toSet());
        Map<Long, ShiftScheduleEntity> scheduleMap = scheduleRepository.findAllById(scheduleIds).stream()
                .collect(Collectors.toMap(s -> s.getId(), s -> s));

        // 4. teamId 一覧から Team を一括取得（クロスドメイン: teamId のみ保持、FK制約なし）
        Set<Long> teamIds = scheduleMap.values().stream()
                .map(ShiftScheduleEntity::getTeamId)
                .collect(Collectors.toSet());
        Map<Long, String> teamNameMap = teamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(t -> t.getId(), TeamEntity::getName));

        // 5. positionId 一覧から ShiftPosition を一括取得
        Set<Long> positionIds = slotMap.values().stream()
                .filter(s -> s.getPositionId() != null)
                .map(ShiftSlotEntity::getPositionId)
                .collect(Collectors.toSet());
        Map<Long, String> positionNameMap = positionIds.isEmpty()
                ? Collections.emptyMap()
                : positionRepository.findAllById(positionIds).stream()
                        .collect(Collectors.toMap(p -> p.getId(), ShiftPositionEntity::getName));

        // 6. 結果を DTO に詰めて日付・開始時刻順にソートして返す
        return assignments.stream()
                .filter(a -> slotMap.containsKey(a.getSlotId()))
                .map(a -> {
                    ShiftSlotEntity slot = slotMap.get(a.getSlotId());
                    ShiftScheduleEntity schedule = scheduleMap.get(slot.getScheduleId());
                    Long teamId = schedule != null ? schedule.getTeamId() : null;
                    String positionName = slot.getPositionId() != null
                            ? positionNameMap.get(slot.getPositionId())
                            : null;
                    return MyConfirmedSlotResponse.builder()
                            .slotId(slot.getId())
                            .slotDate(slot.getSlotDate())
                            .startTime(slot.getStartTime())
                            .endTime(slot.getEndTime())
                            .teamId(teamId)
                            .teamName(teamId != null ? teamNameMap.get(teamId) : null)
                            .scheduleId(slot.getScheduleId())
                            .scheduleName(schedule != null ? schedule.getTitle() : null)
                            .positionName(positionName)
                            .build();
                })
                .sorted(java.util.Comparator
                        .comparing(MyConfirmedSlotResponse::getSlotDate,
                                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparing(MyConfirmedSlotResponse::getStartTime,
                                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }
}
