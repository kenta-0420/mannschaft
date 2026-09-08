package com.mannschaft.app.shift.service;

import com.mannschaft.app.shift.dto.MyConfirmedSlotResponse;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
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

    private final ShiftSlotRepository slotRepository;
    private final ShiftScheduleRepository scheduleRepository;
    private final ShiftPositionRepository positionRepository;
    /** クロスドメインFK禁止の原則に従い、teamId は Long で保持し TeamRepository 経由で名前を取得する */
    private final TeamRepository teamRepository;

    /**
     * ログインユーザーの確定シフト枠一覧を取得する。
     *
     * <p><b>参照元（CMP-260908-2117）</b>: 現在の割当状態の正本である
     * {@code shift_slots.assigned_user_ids} を引く。従来は {@code shift_assignments} の
     * {@code status = CONFIRMED} を引いていたが、同表に書き込むのは自動割当の確定だけで、
     * 手動割当（{@code PATCH /shifts/slots/{id}/assignments}・枠更新）は JSON 列にしか書かないため、
     * <b>手動で割り当てられた本人にシフトが一切表示されなかった</b>。</p>
     *
     * <p><b>未公開シフト表の遮断</b>: 旧実装では {@code status = CONFIRMED} が偶然の公開ガードとして
     * 働いていた面があるため、JSON 参照に移した後も {@link ShiftScheduleVisibilityPolicy} による
     * {@code FULL} 判定を必ず通す（下の filter）。MASKED（COLLECTING / ADJUSTING）も返さない。</p>
     *
     * <p>N+1 クエリを防ぐため、schedule・position・team を一括取得してマップ化する。</p>
     *
     * @param userId ログインユーザーID
     * @return 確定シフト枠レスポンスのリスト（日付昇順・開始時刻昇順）
     */
    public List<MyConfirmedSlotResponse> getMyConfirmedSlots(Long userId) {
        // 1. ユーザーが割り当てられている枠を全件取得（割当の正本 = JSON 列）
        List<ShiftSlotEntity> slots = slotRepository.findAllAssignedToUser(userId);

        if (slots.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, ShiftSlotEntity> slotMap = slots.stream()
                .collect(Collectors.toMap(s -> s.getId(), s -> s, (a, b) -> a));

        // 2. scheduleId 一覧から ShiftSchedule を一括取得
        Set<Long> scheduleIds = slotMap.values().stream()
                .map(ShiftSlotEntity::getScheduleId)
                .collect(Collectors.toSet());
        Map<Long, ShiftScheduleEntity> scheduleMap = scheduleRepository.findAllById(scheduleIds).stream()
                .collect(Collectors.toMap(s -> s.getId(), s -> s));

        // 3. teamId 一覧から Team を一括取得（クロスドメイン: teamId のみ保持、FK制約なし）
        Set<Long> teamIds = scheduleMap.values().stream()
                .map(ShiftScheduleEntity::getTeamId)
                .collect(Collectors.toSet());
        Map<Long, String> teamNameMap = teamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(t -> t.getId(), TeamEntity::getName));

        // 4. positionId 一覧から ShiftPosition を一括取得
        Set<Long> positionIds = slotMap.values().stream()
                .filter(s -> s.getPositionId() != null)
                .map(ShiftSlotEntity::getPositionId)
                .collect(Collectors.toSet());
        Map<Long, String> positionNameMap = positionIds.isEmpty()
                ? Collections.emptyMap()
                : positionRepository.findAllById(positionIds).stream()
                        .collect(Collectors.toMap(p -> p.getId(), ShiftPositionEntity::getName));

        // 5. 結果を DTO に詰めて日付・開始時刻順にソートして返す。
        //    schedule が引けない枠は fail-closed で除外する（可視性を判定できないため）。
        return slotMap.values().stream()
                .filter(slot -> {
                    ShiftScheduleEntity schedule = scheduleMap.get(slot.getScheduleId());
                    return schedule != null
                            && ShiftScheduleVisibilityPolicy.classify(schedule.getStatus(), schedule.getPublishedAt())
                            == ShiftScheduleVisibilityPolicy.Visibility.FULL;
                })
                .map(slot -> {
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
