package com.mannschaft.app.todo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.dto.AddAssigneeRequest;
import com.mannschaft.app.todo.dto.AssigneeResponse;
import com.mannschaft.app.todo.dto.BulkStatusChangeRequest;
import com.mannschaft.app.todo.dto.CommentResponse;
import com.mannschaft.app.todo.dto.CreateCommentRequest;
import com.mannschaft.app.todo.dto.CreateTodoRequest;
import com.mannschaft.app.todo.dto.GanttTodoResponse;
import com.mannschaft.app.todo.dto.LinkScheduleRequest;
import com.mannschaft.app.todo.dto.PatchTodoRequest;
import com.mannschaft.app.todo.dto.PersonalMemoRequest;
import com.mannschaft.app.todo.dto.PersonalMemoResponse;
import com.mannschaft.app.todo.dto.ProgressModeRequest;
import com.mannschaft.app.todo.dto.ProgressRateRequest;
import com.mannschaft.app.todo.dto.SharedMemoEntryRequest;
import com.mannschaft.app.todo.dto.SharedMemoEntryResponse;
import com.mannschaft.app.todo.dto.TodoResponse;
import com.mannschaft.app.todo.dto.TodoStatusChangeRequest;
import com.mannschaft.app.todo.dto.TodoStatusChangeResponse;
import com.mannschaft.app.todo.dto.UpdateCommentRequest;
import com.mannschaft.app.todo.dto.UpdateTodoRequest;
import com.mannschaft.app.todo.service.TodoAssigneeService;
import com.mannschaft.app.todo.service.TodoCommentService;
import com.mannschaft.app.todo.service.TodoGanttService;
import com.mannschaft.app.todo.service.TodoPersonalMemoService;
import com.mannschaft.app.todo.service.TodoScheduleLinkService;
import com.mannschaft.app.todo.service.TodoService;
import com.mannschaft.app.todo.service.TodoSharedMemoService;
import com.mannschaft.app.todo.service.TodoStatusService;
import com.mannschaft.app.todo.security.TodoAccessGuard;
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
import com.mannschaft.app.common.SecurityUtils;

/**
 * 組織TODOコントローラー。組織スコープのTODO・担当者・コメントAPIを提供する。
 *
 * <p>Phase 0 (F02.3.1 前提): {@link TeamTodoController} を雛形として `/api/v1/organizations/{orgId}/todos`
 * 配下のエンドポイントを一式提供する。スコープ判定以外のロジックは {@link TodoService} 等に委譲し、
 * チーム版と完全同一のレスポンス・バリデーションを持つ。
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/todos")
@Tag(name = "TODO（組織）", description = "F02.3 組織TODO管理")
@RequiredArgsConstructor
public class OrgTodoController {

    private final TodoService todoService;
    private final TodoStatusService todoStatusService;
    private final TodoAssigneeService todoAssigneeService;
    private final TodoCommentService commentService;
    private final TodoGanttService ganttService;
    private final TodoScheduleLinkService scheduleLinkService;
    private final TodoSharedMemoService sharedMemoService;
    private final TodoPersonalMemoService personalMemoService;
    private final TodoAccessGuard todoAccessGuard;


    /**
     * TODO一覧を取得する。
     *
     * @param sort ソート種別。"RECENT"（既定・作成新着順）または "PRIORITY"（優先度降順）。
     */
    @GetMapping
    @Operation(summary = "組織TODO一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<TodoResponse>> listTodos(
            @PathVariable Long orgId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "RECENT") String sort) {
        // 認可根治（Wave5 todo硬化A）: scope 級 membership 検証（非メンバー 403）。
        todoAccessGuard.requireScopeMember(TodoScopeType.ORGANIZATION, orgId, SecurityUtils.getCurrentUserId());
        TodoStatus todoStatus = status != null ? TodoStatus.valueOf(status) : null;
        return ResponseEntity.ok(todoService.listTodos(
                TodoScopeType.ORGANIZATION, orgId, todoStatus, page, size, sort));
    }

    /**
     * TODOを作成する。
     */
    @PostMapping
    @Operation(summary = "組織TODO作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TodoResponse>> createTodo(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateTodoRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        // 認可根治（Wave5 todo硬化A）: scope 級 membership 検証（非メンバー 403）。
        todoAccessGuard.requireScopeMember(TodoScopeType.ORGANIZATION, orgId, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(todoService.createTodo(TodoScopeType.ORGANIZATION, orgId, request, userId));
    }

    /**
     * TODO詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "組織TODO詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TodoResponse>> getTodo(
            @PathVariable Long orgId,
            @PathVariable Long id) {
        // 認可根治（Wave5 todo硬化A）: scope 束縛（404 秘匿）＋ membership 検証（非メンバー 403）。
        todoAccessGuard.verifyScopeAndMembership(id, TodoScopeType.ORGANIZATION, orgId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(todoService.getTodo(id));
    }

    /**
     * 組織TODOの直接の子TODO一覧を取得する。
     */
    @GetMapping("/{id}/children")
    @Operation(summary = "組織TODO子一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TodoResponse>>> getChildTodos(
            @PathVariable Long orgId,
            @PathVariable Long id) {
        // 認可根治（Wave5 todo硬化A）: scope 束縛（404 秘匿）＋ membership 検証（非メンバー 403）。
        todoAccessGuard.verifyScopeAndMembership(id, TodoScopeType.ORGANIZATION, orgId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(todoService.getChildTodos(TodoScopeType.ORGANIZATION, orgId, id));
    }

    /**
     * TODOを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "組織TODO更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TodoResponse>> updateTodo(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateTodoRequest request) {
        // 認可根治（Wave5 todo硬化A）: scope 束縛（404 秘匿）＋ membership 検証（非メンバー 403）。
        todoAccessGuard.verifyScopeAndMembership(id, TodoScopeType.ORGANIZATION, orgId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(todoService.updateTodo(id, request));
    }

    /**
     * TODOを部分更新する（dueDate等）。
     */
    @PatchMapping("/{id}")
    @Operation(summary = "組織TODO部分更新（PATCH）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TodoResponse>> patchTodo(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @Valid @RequestBody PatchTodoRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        // 認可根治（Wave5 todo硬化A）: scope 束縛（404 秘匿）＋ membership 検証（非メンバー 403）。
        // 担当者限定の 404（patchTodo 内 existsByTodoIdAndUserId）は現行維持。
        todoAccessGuard.verifyScopeAndMembership(id, TodoScopeType.ORGANIZATION, orgId, userId);
        return ResponseEntity.ok(todoService.patchTodo(id, userId, request));
    }

    /**
     * TODOを削除する（論理削除）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "組織TODO削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTodo(
            @PathVariable Long orgId,
            @PathVariable Long id) {
        // 認可根治（Wave5 todo硬化A）: scope 束縛（404 秘匿）＋ 作成者 or ADMIN 検証（非該当 403）。
        todoAccessGuard.verifyScopeAndOwnerOrAdmin(id, TodoScopeType.ORGANIZATION, orgId, SecurityUtils.getCurrentUserId());
        todoService.deleteTodo(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 論理削除済みTODOを復元する。
     */
    @PostMapping("/{id}/restore")
    @Operation(summary = "組織TODO復元")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "復元成功")
    public ResponseEntity<ApiResponse<TodoResponse>> restoreTodo(
            @PathVariable Long orgId,
            @PathVariable Long id) {
        // 認可根治（Wave5 todo硬化A）: scope 束縛（404 秘匿）＋ 作成者 or ADMIN 検証（非該当 403）。
        todoAccessGuard.verifyScopeAndOwnerOrAdmin(id, TodoScopeType.ORGANIZATION, orgId, SecurityUtils.getCurrentUserId());
        todoService.restoreTodo(id);
        return ResponseEntity.ok(todoService.getTodo(id));
    }

    /**
     * TODOステータスを変更する。
     */
    @PatchMapping("/{id}/status")
    @Operation(summary = "組織TODOステータス変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<ApiResponse<TodoStatusChangeResponse>> changeStatus(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @Valid @RequestBody TodoStatusChangeRequest request) {
        // 認可根治（Wave5 todo硬化A）: scope 束縛（404 秘匿）＋ membership 検証（非メンバー 403）。
        todoAccessGuard.verifyScopeAndMembership(id, TodoScopeType.ORGANIZATION, orgId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(todoStatusService.changeStatus(id, request, SecurityUtils.getCurrentUserId()));
    }

    /**
     * TODO一括ステータス変更。
     */
    @PatchMapping("/bulk-status")
    @Operation(summary = "組織TODO一括ステータス変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<ApiResponse<List<TodoStatusChangeResponse>>> bulkChangeStatus(
            @PathVariable Long orgId,
            @Valid @RequestBody BulkStatusChangeRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        // 認可根治（Wave5 todo硬化A）: scope 級 membership 検証（非メンバー 403）。
        // 越境 id 混入は Service 側の scope 絞りで対象外にする（BOLA 根治）。
        todoAccessGuard.requireScopeMember(TodoScopeType.ORGANIZATION, orgId, userId);
        return ResponseEntity.ok(todoStatusService.bulkChangeStatus(
                TodoScopeType.ORGANIZATION, orgId, request, userId));
    }

    // --- 担当者 ---

    /**
     * 担当者を追加する。
     */
    @PostMapping("/{id}/assignees")
    @Operation(summary = "組織TODO担当者追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<AssigneeResponse>> addAssignee(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @Valid @RequestBody AddAssigneeRequest request) {
        // 認可根治（Wave5 todo硬化A）: scope 束縛（404 秘匿）＋ membership 検証（非メンバー 403）。
        todoAccessGuard.verifyScopeAndMembership(id, TodoScopeType.ORGANIZATION, orgId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(todoAssigneeService.addAssignee(id, request, SecurityUtils.getCurrentUserId()));
    }

    /**
     * 担当者を削除する。
     */
    @DeleteMapping("/{id}/assignees/{userId}")
    @Operation(summary = "組織TODO担当者削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> removeAssignee(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @PathVariable Long userId) {
        // 認可根治（Wave5 todo硬化A）: scope 束縛（404 秘匿）＋ membership 検証（非メンバー 403）。
        todoAccessGuard.verifyScopeAndMembership(id, TodoScopeType.ORGANIZATION, orgId, SecurityUtils.getCurrentUserId());
        todoAssigneeService.removeAssignee(id, userId);
        return ResponseEntity.noContent().build();
    }

    // --- コメント ---

    /**
     * コメント一覧を取得する。
     */
    @GetMapping("/{id}/comments")
    @Operation(summary = "組織TODOコメント一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<CommentResponse>> listComments(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 認可根治（早馬 BOLA 閉塞）: path scope 束縛＋membership 検証は Service で実施する。
        return ResponseEntity.ok(commentService.listComments(
                id, TodoScopeType.ORGANIZATION, orgId, SecurityUtils.getCurrentUserId(), page, size));
    }

    /**
     * コメントを追加する。
     */
    @PostMapping("/{id}/comments")
    @Operation(summary = "組織TODOコメント追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<CommentResponse>> addComment(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @Valid @RequestBody CreateCommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commentService.addComment(
                        id, TodoScopeType.ORGANIZATION, orgId, request, SecurityUtils.getCurrentUserId()));
    }

    /**
     * コメントを編集する（本人のみ）。
     */
    @PutMapping("/{id}/comments/{commentId}")
    @Operation(summary = "組織TODOコメント編集")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<CommentResponse>> updateComment(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @PathVariable Long commentId,
            @Valid @RequestBody UpdateCommentRequest request) {
        return ResponseEntity.ok(commentService.updateComment(
                id, TodoScopeType.ORGANIZATION, orgId, commentId, request, SecurityUtils.getCurrentUserId()));
    }

    /**
     * コメントを削除する（本人またはADMIN）。
     */
    @DeleteMapping("/{id}/comments/{commentId}")
    @Operation(summary = "組織TODOコメント削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteComment(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @PathVariable Long commentId) {
        commentService.deleteComment(id, commentId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // --- スケジュール連携 ---

    /**
     * 既存スケジュールとTODOを連携する。
     */
    @PostMapping("/{id}/link-schedule")
    @Operation(summary = "組織TODOスケジュール連携")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "連携成功")
    public ResponseEntity<Void> linkSchedule(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @Valid @RequestBody LinkScheduleRequest request) {
        scheduleLinkService.linkScheduleToTodo(
                request.getScheduleId(), id, request.getParentId(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok().build();
    }

    /**
     * スケジュール連携を解除する。
     */
    @DeleteMapping("/{id}/link-schedule")
    @Operation(summary = "組織TODOスケジュール連携解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "解除成功")
    public ResponseEntity<Void> unlinkSchedule(
            @PathVariable Long orgId,
            @PathVariable Long id) {
        scheduleLinkService.unlinkScheduleFromTodo(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // --- ガントバー ---

    /**
     * ガントバー用TODO一覧を取得する。
     * fromDate・toDate は必須（from > to の場合は400）。
     */
    @GetMapping("/gantt")
    @Operation(summary = "組織TODOガントバー一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<GanttTodoResponse>>> getGanttTodos(
            @PathVariable Long orgId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        // 認可根治（Wave5 todo硬化A）: scope 級 membership 検証（非メンバー 403）。
        todoAccessGuard.requireScopeMember(TodoScopeType.ORGANIZATION, orgId, SecurityUtils.getCurrentUserId());
        if (from.isAfter(to)) {
            return ResponseEntity.badRequest().build();
        }
        List<GanttTodoResponse> ganttTodos = ganttService.getGanttTodos(TodoScopeType.ORGANIZATION, orgId, from, to);
        return ResponseEntity.ok(ApiResponse.of(ganttTodos));
    }

    // --- 進捗率管理 ---

    /**
     * 進捗率を手動設定する（手動モード必須）。
     */
    @PatchMapping("/{id}/progress")
    @Operation(summary = "組織TODO進捗率更新（手動モード必須）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TodoResponse>> setProgressRate(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @Valid @RequestBody ProgressRateRequest request) {
        return ResponseEntity.ok(todoService.setProgressRate(id, request.getProgressRate()));
    }

    /**
     * 進捗モードを切り替える（手動 ↔ 自動）。
     */
    @PatchMapping("/{id}/progress-mode")
    @Operation(summary = "組織TODO進捗モード切替（手動/自動）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "切替成功")
    public ResponseEntity<ApiResponse<TodoResponse>> setProgressMode(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @Valid @RequestBody ProgressModeRequest request) {
        return ResponseEntity.ok(todoService.setProgressMode(id, request.getProgressManual()));
    }

    // --- 共有メモ ---

    /**
     * 共有メモ一覧を取得する（時系列昇順）。
     */
    @GetMapping("/{id}/memos")
    @Operation(summary = "組織TODO共有メモ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<SharedMemoEntryResponse>> listSharedMemos(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(sharedMemoService.getSharedMemos(id, page, size,
                SecurityUtils.getCurrentUserId()));
    }

    /**
     * 共有メモを追加する。
     */
    @PostMapping("/{id}/memos")
    @Operation(summary = "組織TODO共有メモ追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<SharedMemoEntryResponse>> addSharedMemo(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @Valid @RequestBody SharedMemoEntryRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sharedMemoService.addSharedMemo(id, currentUserId, request, currentUserId));
    }

    /**
     * 共有メモを編集する（投稿者のみ）。
     */
    @PutMapping("/{id}/memos/{memoId}")
    @Operation(summary = "組織TODO共有メモ編集")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<SharedMemoEntryResponse>> updateSharedMemo(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @PathVariable Long memoId,
            @Valid @RequestBody SharedMemoEntryRequest request) {
        return ResponseEntity.ok(sharedMemoService.updateSharedMemo(id, memoId, SecurityUtils.getCurrentUserId(), request));
    }

    /**
     * 共有メモを論理削除する（投稿者またはADMIN）。
     */
    @DeleteMapping("/{id}/memos/{memoId}")
    @Operation(summary = "組織TODO共有メモ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteSharedMemo(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @PathVariable Long memoId) {
        sharedMemoService.deleteSharedMemo(id, memoId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // --- 個人メモ（組織TODO用） ---

    /**
     * 個人メモを取得する（本人のみ）。
     */
    @GetMapping("/{id}/my-memo")
    @Operation(summary = "組織TODO個人メモ取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<PersonalMemoResponse>> getPersonalMemo(
            @PathVariable Long orgId,
            @PathVariable Long id) {
        return ResponseEntity.ok(personalMemoService.getPersonalMemo(id, SecurityUtils.getCurrentUserId()));
    }

    /**
     * 個人メモをUPSERTする（存在すれば更新、なければ作成）。
     */
    @PutMapping("/{id}/my-memo")
    @Operation(summary = "組織TODO個人メモUPSERT")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "保存成功")
    public ResponseEntity<ApiResponse<PersonalMemoResponse>> upsertPersonalMemo(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @Valid @RequestBody PersonalMemoRequest request) {
        return ResponseEntity.ok(personalMemoService.upsertPersonalMemo(id, SecurityUtils.getCurrentUserId(), request));
    }

    /**
     * 個人メモを削除する（物理削除）。
     */
    @DeleteMapping("/{id}/my-memo")
    @Operation(summary = "組織TODO個人メモ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePersonalMemo(
            @PathVariable Long orgId,
            @PathVariable Long id) {
        personalMemoService.deletePersonalMemo(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
