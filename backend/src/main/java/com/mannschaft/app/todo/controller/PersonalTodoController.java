package com.mannschaft.app.todo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.dto.CreateTodoRequest;
import com.mannschaft.app.todo.dto.GanttTodoResponse;
import com.mannschaft.app.todo.dto.LinkScheduleRequest;
import com.mannschaft.app.todo.dto.PatchTodoRequest;
import com.mannschaft.app.todo.dto.UpdateTodoRequest;
import com.mannschaft.app.todo.dto.PersonalMemoRequest;
import com.mannschaft.app.todo.dto.PersonalMemoResponse;
import com.mannschaft.app.todo.dto.ProgressModeRequest;
import com.mannschaft.app.todo.dto.ProgressRateRequest;
import com.mannschaft.app.todo.dto.TodoResponse;
import com.mannschaft.app.todo.dto.TodoStatusChangeRequest;
import com.mannschaft.app.todo.dto.TodoStatusChangeResponse;
import com.mannschaft.app.todo.security.TodoAccessGuard;
import com.mannschaft.app.todo.service.TodoGanttService;
import com.mannschaft.app.todo.service.TodoPersonalMemoService;
import com.mannschaft.app.todo.service.TodoScheduleLinkService;
import com.mannschaft.app.todo.service.TodoService;
import com.mannschaft.app.todo.service.TodoStatusService;
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
 *
 * <p><b>認可の所在</b>（認可根治戦役 第1波・個人領域）:</p>
 * <ul>
 *   <li><b>TODO ID を受け取る EP</b>（詳細・更新・PATCH・削除・復元・子一覧・ステータス・トグル・
 *       進捗率・進捗モード）: {@link TodoAccessGuard} を<b>入口で</b>呼び、対象 TODO の担当者本人
 *       （もしくは自分の個人スコープに属すること）を照合する。担当外・不存在・他スコープはいずれも
 *       404（{@code TODO_010}）にまとめ、TODO ID の存在有無を漏らさない。ガードを共有 Service ではなく
 *       Controller に置くのは、同 Service を使うバッチ・他ドメイン連携を巻き添えにしないため。</li>
 *   <li><b>スコープ級 EP</b>（作成・自分のTODO一覧・ガント）: スコープは常に
 *       {@code SecurityUtils.getCurrentUserId()} で確定した認証主体の ID を用い、リクエストからは
 *       指定できない（自己スコープ）。作成時に指定されたプロジェクト・親TODO・マイルストーンは
 *       {@code TodoService#createTodo} が「自分の個人スコープに属すること」を照合し、
 *       他人のプロジェクト配下への作成を拒む。</li>
 * </ul>
 *
 * <p>スコープ級 3 EP は認可番人の呼び出しグラフ判定では拾えないが、構造的に自己スコープで閉じている。
 * 実体を伴わない {@code @PreAuthorize("isAuthenticated()")} を看板として貼ることはせず、
 * 契約テスト {@code TodoPersonalScopeContractIT} で「他ユーザーのデータに到達できないこと」を固定する。</p>
 */
@RestController
@RequestMapping("/api/v1/todos")
@Tag(name = "TODO（個人）", description = "F02.3 個人TODO管理")
@RequiredArgsConstructor
public class PersonalTodoController {

    private final TodoService todoService;
    private final TodoStatusService todoStatusService;
    private final TodoGanttService ganttService;
    private final TodoScheduleLinkService scheduleLinkService;
    private final TodoPersonalMemoService personalMemoService;
    private final TodoAccessGuard todoAccessGuard;


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
     * 個人TODO詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "個人TODO詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TodoResponse>> getPersonalTodo(@PathVariable Long id) {
        // 認可（Wave6）: 削除・復元・PATCH と同一の担当者照合（担当外は404秘匿）。
        // getTodo は Team/Org Controller と共有のため、ガードは共有Serviceではなく public 入口に置く。
        Long userId = SecurityUtils.getCurrentUserId();
        todoAccessGuard.verifyPersonalAssignee(id, userId);
        return ResponseEntity.ok(todoService.getTodo(id));
    }

    /**
     * 個人TODOステータスを変更する。
     */
    @PatchMapping("/{id}/status")
    @Operation(summary = "個人TODO ステータス変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<ApiResponse<TodoStatusChangeResponse>> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody TodoStatusChangeRequest request) {
        // 認可（Wave6）: Team/Org Controller と同様、status 変更も入口で担当者照合を行う（担当外は404秘匿）。
        // changeStatus に渡す userId は監査項目・イベント発行用であり、認可判定はこのガードが担う。
        Long userId = SecurityUtils.getCurrentUserId();
        todoAccessGuard.verifyPersonalAssignee(id, userId);
        return ResponseEntity.ok(todoStatusService.changeStatus(id, request, userId));
    }

    /**
     * ダッシュボードウィジェット用: TODO を完了/未完了トグル。
     * completed=true → COMPLETED、false → OPEN に切り替える。
     */
    @PatchMapping("/{id}/toggle")
    @Operation(summary = "個人TODO 完了トグル（ダッシュボード用）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<ApiResponse<TodoStatusChangeResponse>> toggleTodo(
            @PathVariable Long id,
            @RequestBody ToggleTodoRequest request) {
        // 認可（Wave6）: status EP の姉妹EP。同一の担当者照合を揃えて敷く（担当外は404秘匿）。
        Long userId = SecurityUtils.getCurrentUserId();
        todoAccessGuard.verifyPersonalAssignee(id, userId);
        String newStatus = request.completed() ? "COMPLETED" : "OPEN";
        return ResponseEntity.ok(todoStatusService.changeStatus(id, new TodoStatusChangeRequest(newStatus), userId));
    }

    record ToggleTodoRequest(boolean completed) {}

    /**
     * 個人TODOを更新する（タイトル・説明・優先度・開始日・期限）。
     */
    @PutMapping("/{id}")
    @Operation(summary = "個人TODO更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TodoResponse>> updatePersonalTodo(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTodoRequest request) {
        // 認可（Wave6）: PATCH（patchTodo）と同一の担当者照合に揃える（担当外は404秘匿）。
        // updateTodo は Team/Org Controller と共有のため、ガードは共有Serviceではなく public 入口に置く。
        Long userId = SecurityUtils.getCurrentUserId();
        todoAccessGuard.verifyPersonalAssignee(id, userId);
        return ResponseEntity.ok(todoService.updateTodo(id, request));
    }

    /**
     * 個人TODOを削除する（論理削除）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "個人TODO削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePersonalTodo(@PathVariable Long id) {
        // 認可: 詳細・更新・PATCH と同一の担当者照合を入口で行う（担当外・不存在はいずれも404秘匿）。
        // deletePersonalTodo は Service 側でも同一の担当者照合を行うが、認可の所在を
        // public 入口に明示するためガードを入口に置く（共有Serviceに置くとバッチ等が巻き添えになる）。
        Long userId = SecurityUtils.getCurrentUserId();
        todoAccessGuard.verifyPersonalAssignee(id, userId);
        todoService.deletePersonalTodo(id, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 論理削除済みの個人TODOを復元する。
     */
    @PostMapping("/{id}/restore")
    @Operation(summary = "個人TODO復元")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "復元成功")
    public ResponseEntity<ApiResponse<TodoResponse>> restorePersonalTodo(@PathVariable Long id) {
        // 認可: 削除EPと同一の担当者照合を入口で行う（担当外・不存在はいずれも404秘匿）。
        // 担当者行は論理削除では消えないため、削除済みTODOに対しても同じ照合が成立する。
        Long userId = SecurityUtils.getCurrentUserId();
        todoAccessGuard.verifyPersonalAssignee(id, userId);
        todoService.restorePersonalTodo(id, userId);
        return ResponseEntity.ok(todoService.getTodo(id));
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
        // 認可: 更新（PUT）EP と同一の担当者照合を入口で行う（担当外・不存在はいずれも404秘匿）。
        Long userId = SecurityUtils.getCurrentUserId();
        todoAccessGuard.verifyPersonalAssignee(id, userId);
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
        // 認可: 親TODOが自分の個人スコープに属することを入口で束縛する（他スコープ・他ユーザーの
        // TODO ID は 404 秘匿）。進捗率・進捗モード EP と同一のガードに揃える。
        Long userId = SecurityUtils.getCurrentUserId();
        todoAccessGuard.verifyScopeAndMembership(id, TodoScopeType.PERSONAL, userId, userId);
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
        // 認可根治（Wave5 todo硬化B）: PERSONAL は所有権（scopeId=userId）で scope 束縛＋認可（Service 署名拡張）。
        Long userId = SecurityUtils.getCurrentUserId();
        scheduleLinkService.linkScheduleToTodo(
                request.getScheduleId(), id, TodoScopeType.PERSONAL, userId, request.getParentId(), userId);
        return ResponseEntity.ok().build();
    }

    /**
     * 個人TODOのスケジュール連携を解除する。
     */
    @DeleteMapping("/{id}/link-schedule")
    @Operation(summary = "個人TODO スケジュール連携解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "解除成功")
    public ResponseEntity<Void> unlinkSchedule(@PathVariable Long id) {
        // 認可根治（Wave5 todo硬化B）: PERSONAL は所有権（scopeId=userId）で scope 束縛＋認可（Service 署名拡張）。
        Long userId = SecurityUtils.getCurrentUserId();
        scheduleLinkService.unlinkScheduleFromTodo(id, TodoScopeType.PERSONAL, userId, userId);
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
        // 認可根治（Wave5 早馬）: PERSONAL は所有権（scopeId=userId）で scope 束縛＋認可（404 秘匿）。
        // setProgressRate は ActionMemoService からも呼ばれる共有メソッドのため、
        // ガードは共有メソッドではなく public 入口（本 Controller）で敷く。
        Long userId = SecurityUtils.getCurrentUserId();
        todoAccessGuard.verifyScopeAndMembership(id, TodoScopeType.PERSONAL, userId, userId);
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
        // 認可根治（Wave5 早馬）: PERSONAL は所有権（scopeId=userId）で scope 束縛＋認可（404 秘匿）。
        Long userId = SecurityUtils.getCurrentUserId();
        todoAccessGuard.verifyScopeAndMembership(id, TodoScopeType.PERSONAL, userId, userId);
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
        // 認可根治（Wave5 todo硬化B）: PERSONAL は所有権（scopeId=userId）で scope 束縛＋認可（Service 署名拡張）。
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(personalMemoService.getPersonalMemo(
                id, TodoScopeType.PERSONAL, userId, userId));
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
        // 認可根治（Wave5 todo硬化B）: PERSONAL は所有権（scopeId=userId）で scope 束縛＋認可（Service 署名拡張）。
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(personalMemoService.upsertPersonalMemo(
                id, TodoScopeType.PERSONAL, userId, userId, request));
    }

    /**
     * 個人メモを削除する（物理削除）。
     */
    @DeleteMapping("/{id}/memo")
    @Operation(summary = "個人TODO 個人メモ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePersonalMemo(@PathVariable Long id) {
        // 認可根治（Wave5 todo硬化B）: PERSONAL は所有権（scopeId=userId）で scope 束縛＋認可（Service 署名拡張）。
        Long userId = SecurityUtils.getCurrentUserId();
        personalMemoService.deletePersonalMemo(id, TodoScopeType.PERSONAL, userId, userId);
        return ResponseEntity.noContent().build();
    }
}
