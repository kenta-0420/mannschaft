package com.mannschaft.app.shift.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * シフト公開 Todo 自動作成サービス。F03.5 Phase 4-β。
 * ShiftPublishedEvent を受け取り、各スロットの割り当てユーザー分の Todo を一括作成する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftToTaskService {

    private final ShiftSlotRepository slotRepository;
    private final TodoRepository todoRepository;
    private final ObjectMapper objectMapper;

    /**
     * シフトスケジュール内の全スロットを元に Todo を自動作成する。
     * 同一スロット×ユーザーの重複は DB ユニーク制約でスキップする（冪等）。
     *
     * @param scheduleId        シフトスケジュールID
     * @param teamId            チームID（ログ用）
     * @param triggeredByUserId 操作ユーザーID
     */
    @Transactional
    public void createTodosForSchedule(Long scheduleId, Long teamId, Long triggeredByUserId) {
        List<ShiftSlotEntity> slots = slotRepository
                .findByScheduleIdOrderBySlotDateAscStartTimeAsc(scheduleId);

        List<TodoEntity> todos = new ArrayList<>();
        for (ShiftSlotEntity slot : slots) {
            List<Long> userIds = parseUserIds(slot.getAssignedUserIds());
            if (userIds.isEmpty()) continue;

            for (Long userId : userIds) {
                String title = "シフト勤務: " + slot.getSlotDate()
                        + " " + slot.getStartTime() + "〜" + slot.getEndTime();
                todos.add(TodoEntity.builder()
                        .scopeType(TodoScopeType.PERSONAL)
                        .scopeId(userId)
                        .title(title)
                        .dueDate(slot.getSlotDate())
                        .dueTime(slot.getStartTime())
                        .priority(TodoPriority.MEDIUM)
                        .status(TodoStatus.OPEN)
                        .linkedScheduleId(scheduleId)
                        .linkedShiftSlotId(slot.getId())
                        .createdBy(triggeredByUserId)
                        .build());
            }
        }

        if (todos.isEmpty()) {
            log.info("シフト公開 Todo 自動作成: 対象なし scheduleId={}, teamId={}", scheduleId, teamId);
            return;
        }

        int created = 0;
        int skipped = 0;
        for (TodoEntity todo : todos) {
            try {
                todoRepository.save(todo);
                created++;
            } catch (DataIntegrityViolationException e) {
                skipped++;
                log.debug("シフト Todo 重複スキップ: slotId={}, userId={}",
                        todo.getLinkedShiftSlotId(), todo.getScopeId());
            } catch (Exception e) {
                skipped++;
                log.warn("シフト Todo 作成失敗: slotId={}, userId={}",
                        todo.getLinkedShiftSlotId(), todo.getScopeId(), e);
            }
        }

        log.info("シフト公開 Todo 自動作成: scheduleId={}, teamId={}, 作成={}, スキップ={}",
                scheduleId, teamId, created, skipped);
    }

    private List<Long> parseUserIds(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("assignedUserIds JSON 解析失敗: json={}", json);
            return Collections.emptyList();
        }
    }
}
