package com.mannschaft.app.todo.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * TODO↔スケジュール連携サービス。
 *
 * <p>ScheduleEntityはteamId/organizationId/userId列方式、
 * TodoEntityはscopeType+scopeId方式でスコープを管理するため、
 * 相互変換ロジックを本サービスで一元管理する。
 * 双方向リンクの更新は1トランザクション内で完了させる。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TodoScheduleLinkService {

    private final TodoRepository todoRepository;
    private final ScheduleRepository scheduleRepository;

    /**
     * 既存スケジュールを既存TODOに連携する（双方向リンク設定）。
     *
     * <p>スコープ整合性チェックを行い、1トランザクション内でTODOとスケジュール双方を更新する。</p>
     *
     * @param scheduleId    連携するスケジュールID
     * @param todoId        連携先TODO ID
     * @param parentId      子TODOとして配置する場合の親TODO ID（nullable）
     * @param currentUserId 操作ユーザーID
     */
    public void linkScheduleToTodo(Long scheduleId, Long todoId, Long parentId, Long currentUserId) {
        // スケジュールを取得
        ScheduleEntity schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ScheduleErrorCode.SCHEDULE_NOT_FOUND));

        // TODOを取得
        TodoEntity todo = todoRepository.findByIdAndDeletedAtIsNull(todoId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));

        // スコープ整合性チェック
        TodoScopeType scheduleScopeType = resolveScheduleScopeType(schedule);
        Long scheduleScopeId = resolveScheduleScopeId(schedule);

        if (scheduleScopeType != todo.getScopeType() || !scheduleScopeId.equals(todo.getScopeId())) {
            throw new BusinessException(TodoErrorCode.SCHEDULE_SCOPE_MISMATCH);
        }

        // スケジュール側: 既に別のTODOと連携されていないか確認
        if (schedule.getLinkedTodoId() != null && !schedule.getLinkedTodoId().equals(todoId)) {
            throw new BusinessException(ScheduleErrorCode.TODO_ALREADY_LINKED);
        }

        // TODO側: 既に別のスケジュールと連携されていないか確認
        if (todo.getLinkedScheduleId() != null && !todo.getLinkedScheduleId().equals(scheduleId)) {
            throw new BusinessException(TodoErrorCode.TODO_ALREADY_LINKED);
        }

        // TODO側更新（双方向: linked_schedule_id設定、必要なら親TODO変更）
        TodoEntity.TodoEntityBuilder todoBuilder = todo.toBuilder()
                .linkedScheduleId(scheduleId);
        if (parentId != null) {
            todoBuilder.parentId(parentId);
        }
        todoRepository.save(todoBuilder.build());

        // スケジュール側更新（linked_todo_id設定）
        scheduleRepository.save(schedule.toBuilder()
                .linkedTodoId(todoId)
                .build());

        log.info("スケジュール連携: todoId={}, scheduleId={}", todoId, scheduleId);
    }

    /**
     * TODOの内容から新規スケジュールを作成して連携する。
     *
     * <p>TODOのtitle（必須）・startDate・dueDateからスケジュールを生成する。
     * 日付はstartAt/endAtに変換する（時刻は00:00:00 / 23:59:59）。</p>
     *
     * @param todo          連携元TODO
     * @param currentUserId 操作ユーザーID
     * @return 作成されたスケジュールエンティティ
     */
    public ScheduleEntity createScheduleFromTodo(TodoEntity todo, Long currentUserId) {
        // すでにスケジュールと連携されている場合はエラー
        if (todo.getLinkedScheduleId() != null) {
            throw new BusinessException(TodoErrorCode.TODO_ALREADY_LINKED);
        }

        // スケジュールのスコープを設定
        ScheduleEntity.ScheduleEntityBuilder builder = ScheduleEntity.builder()
                .title(todo.getTitle())
                .description(todo.getDescription())
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .createdBy(currentUserId)
                .linkedTodoId(todo.getId());

        // 日付設定（startDate/dueDateから変換）
        LocalDateTime startAt = todo.getStartDate() != null
                ? todo.getStartDate().atStartOfDay()
                : (todo.getDueDate() != null ? todo.getDueDate().atStartOfDay() : LocalDateTime.now());
        LocalDateTime endAt = todo.getDueDate() != null
                ? todo.getDueDate().atTime(23, 59, 59)
                : startAt.plusHours(1);

        builder.startAt(startAt).endAt(endAt);

        // スコープ設定
        switch (todo.getScopeType()) {
            case TEAM -> builder.teamId(todo.getScopeId());
            case ORGANIZATION -> builder.organizationId(todo.getScopeId());
            case PERSONAL -> builder.userId(todo.getScopeId());
        }

        ScheduleEntity schedule = scheduleRepository.save(builder.build());

        // TODO側にlinked_schedule_idを設定（双方向リンク完成）
        todoRepository.save(todo.toBuilder()
                .linkedScheduleId(schedule.getId())
                .build());

        log.info("TODOからスケジュール作成: todoId={}, scheduleId={}", todo.getId(), schedule.getId());
        return schedule;
    }

    /**
     * TODOとスケジュールの連携を解除する（双方向でNULL化）。
     *
     * @param todoId        連携解除対象TODO ID
     * @param currentUserId 操作ユーザーID
     */
    public void unlinkScheduleFromTodo(Long todoId, Long currentUserId) {
        TodoEntity todo = todoRepository.findByIdAndDeletedAtIsNull(todoId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));

        Long linkedScheduleId = todo.getLinkedScheduleId();
        if (linkedScheduleId == null) {
            // 連携されていない場合は何もしない
            return;
        }

        // スケジュール側をNULL化
        scheduleRepository.findById(linkedScheduleId).ifPresent(schedule ->
                scheduleRepository.save(schedule.toBuilder()
                        .linkedTodoId(null)
                        .build())
        );

        // TODO側をNULL化
        todoRepository.save(todo.toBuilder()
                .linkedScheduleId(null)
                .build());

        log.info("スケジュール連携解除: todoId={}, scheduleId={}", todoId, linkedScheduleId);
    }

    /**
     * ScheduleEntityのスコープをTodoScopeTypeに変換する。
     * teamId → TEAM、organizationId → ORGANIZATION、それ以外 → PERSONAL。
     */
    TodoScopeType resolveScheduleScopeType(ScheduleEntity schedule) {
        if (schedule.getTeamId() != null) {
            return TodoScopeType.TEAM;
        }
        if (schedule.getOrganizationId() != null) {
            return TodoScopeType.ORGANIZATION;
        }
        return TodoScopeType.PERSONAL;
    }

    /**
     * ScheduleEntityのスコープIDを取得する。
     * teamId → teamId、organizationId → organizationId、それ以外 → userId。
     */
    Long resolveScheduleScopeId(ScheduleEntity schedule) {
        if (schedule.getTeamId() != null) {
            return schedule.getTeamId();
        }
        if (schedule.getOrganizationId() != null) {
            return schedule.getOrganizationId();
        }
        return schedule.getUserId();
    }
}
