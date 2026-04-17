package com.mannschaft.app.todo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.dto.CreateTodoRequest;
import com.mannschaft.app.todo.dto.GanttTodoResponse;
import com.mannschaft.app.todo.dto.LinkScheduleRequest;
import com.mannschaft.app.todo.dto.PatchTodoRequest;
import com.mannschaft.app.todo.dto.PersonalMemoRequest;
import com.mannschaft.app.todo.dto.PersonalMemoResponse;
import com.mannschaft.app.todo.dto.ProgressModeRequest;
import com.mannschaft.app.todo.dto.ProgressRateRequest;
import com.mannschaft.app.todo.dto.TodoResponse;
import com.mannschaft.app.todo.service.TodoGanttService;
import com.mannschaft.app.todo.service.TodoPersonalMemoService;
import com.mannschaft.app.todo.service.TodoScheduleLinkService;
import com.mannschaft.app.todo.service.TodoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 個人TODOコントローラー。全スコープ横断の自分のTODO一覧を提供する。
 */
@RestController
@RequestMapping("/api/v1/todos")
@Tag(name = "TODO（個人）", description = "F02.3 個人TODO管理")
@RequiredArgsConstructor
public class PersonalTodoController {

    private final TodoService todoService;
    private final TodoGanttService ganttService;
    private final TodoScheduleLinkService scheduleLinkService;
    private final TodoPersonalMemoService personalMemoService;


    /**
     * 個人TODOを作成する。
     */
    @PostMapping
    @Operation(summary = "個人TODO作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TodoResponse>> createPersonalTodo(
            @Valid @RequestBody CreateTodoRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        // 個人TODOは作成者を担当者として自動追加（findMyTodosはassignee経由で取得するため）
        List<Long> assigneeIds = new ArrayList<>();
        if (request.getAssigneeIds() != null) {
            assigneeIds.addAll(request.getAssigneeIds());
        }
        if (!assigneeIds.contains(userId)) {
            assigneeIds.add(userId);
        }
        CreateTodoRequest enriched = new CreateTodoRequest(
                request.getTitle(), request.getDescription(), request.getProjectId(),
                request.getMilestoneId(), request.getPriority(), request.getDueDate(),
                request.getDueTime(), request.getSortOrder(), assigneeIds,
                request.getParentId(),
                request.getStartDate(), request.getLinkedScheduleId(),
                request.getProgressRate(), request.getCreateLinkedSchedule());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(todoService.createTodo(TodoScopeType.PERSONAL, userId, enriched, userId));
    }

    /**
     * 個人TODOを部分更新する（dueDate等）。
     */
    @PatchMapping("/{id}")
    @Operation(summary = "個人TODO部分更新（PATCH）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TodoResponse>> patchTodo(
            @PathVariable Long id,
            @Valid @RequestBody PatchTodoRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(todoService.patchTodo(id, userId, request));
    }

    /**
     * 自分に割り当てられた全TODOを取得する（全スコープ横断）。
     */
    @GetMapping("/my")
    @Operation(summary = "自分のTODO一覧（全スコープ横断）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TodoResponse>>> getMyTodos() {
        return ResponseEntity.ok(todoService.getMyTodos(SecurityUtils.getCurrentUserId()));
    }

    /**
     * 個人TODOの直接の子TODO一覧を取得する。
     */
    @GetMapping("/{id}/children")
    @Operation(summary = "個人TODO子一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TodoResponse>>> getChildTodos(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(todoService.getChildTodos(TodoScopeType.PERSONAL, userId, id));
    }

    // --- Phase 2: スケジュール連携 ---

    /**
     * 既存スケジュールと個人TODOを連携する。
     */
    @PostMapping("/{id}/link-schedule")
    @Operation(summary = "個人TODO スケジュール連携")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "連携成功")
    public ResponseEntity<Void> linkSchedule(
            @PathVariable Long id,
            @Valid @RequestBody LinkScheduleRequest request) {
        scheduleLinkService.linkScheduleToTodo(
                request.getScheduleId(), id, request.getParentId(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok().build();
    }

    /**
     * 個人TODOのスケジュール連携を解除する。
     */
    @DeleteMapping("/{id}/link-schedule")
    @Operation(summary = "個人TODO スケジュール連携解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "解除成功")
    public ResponseEntity<Void> unlinkSchedule(@PathVariable Long id) {
        scheduleLinkService.unlinkScheduleFromTodo(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // --- Phase 2: ガントバー ---

    /**
     * 個人ガントバー用TODO一覧を取得する。
     */
    @GetMapping("/gantt")
    @Operation(summary = "個人ガントバー用TODO一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<GanttTodoResponse>>> getGanttTodos(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from.isAfter(to)) {
            return ResponseEntity.badRequest().build();
        }
        Long userId = SecurityUtils.getCurrentUserId();
        List<GanttTodoResponse> ganttTodos = ganttService.getGanttTodos(TodoScopeType.PERSONAL, userId, from, to);
        return ResponseEntity.ok(ApiResponse.of(ganttTodos));
    }

    // --- Phase 2: 進捗率管理 ---

    /**
     * 個人TODOの進捗率を手動設定する（手動モード必須）。
     */
    @PatchMapping("/{id}/progress")
    @Operation(summary = "個人TODO 進捗率更新（手動モード必須）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TodoResponse>> setProgressRate(
            @PathVariable Long id,
            @Valid @RequestBody ProgressRateRequest request) {
        return ResponseEntity.ok(todoService.setProgressRate(id, request.getProgressRate()));
    }

    /**
     * 個人TODOの進捗モードを切り替える（手動 ↔ 自動）。
     */
    @PatchMapping("/{id}/progress-mode")
    @Operation(summary = "個人TODO 進捗モード切替（手動/自動）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "切替成功")
    public ResponseEntity<ApiResponse<TodoResponse>> setProgressMode(
            @PathVariable Long id,
            @Valid @RequestBody ProgressModeRequest request) {
        return ResponseEntity.ok(todoService.setProgressMode(id, request.getProgressManual()));
    }

    // --- Phase 2: 個人メモ ---

    /**
     * 個人メモを取得する（本人のみ）。
     */
    @GetMapping("/{id}/memo")
    @Operation(summary = "個人TODO 個人メモ取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<PersonalMemoResponse>> getPersonalMemo(@PathVariable Long id) {
        return ResponseEntity.ok(personalMemoService.getPersonalMemo(id, SecurityUtils.getCurrentUserId()));
    }

    /**
     * 個人メモをUPSERTする（存在すれば更新、なければ作成）。
     */
    @PutMapping("/{id}/memo")
    @Operation(summary = "個人TODO 個人メモUPSERT")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "保存成功")
    public ResponseEntity<ApiResponse<PersonalMemoResponse>> upsertPersonalMemo(
            @PathVariable Long id,
            @Valid @RequestBody PersonalMemoRequest request) {
        return ResponseEntity.ok(personalMemoService.upsertPersonalMemo(id, SecurityUtils.getCurrentUserId(), request));
    }

    /**
     * 個人メモを削除する（物理削除）。
     */
    @DeleteMapping("/{id}/memo")
    @Operation(summary = "個人TODO 個人メモ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePersonalMemo(@PathVariable Long id) {
        personalMemoService.deletePersonalMemo(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
