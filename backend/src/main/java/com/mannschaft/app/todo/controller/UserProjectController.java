package com.mannschaft.app.todo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.todo.ProjectStatus;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.dto.CreateMilestoneRequest;
import com.mannschaft.app.todo.dto.CreateProjectRequest;
import com.mannschaft.app.todo.dto.MilestoneResponse;
import com.mannschaft.app.todo.dto.ProjectDetailResponse;
import com.mannschaft.app.todo.dto.ProjectResponse;
import com.mannschaft.app.todo.dto.TodoResponse;
import com.mannschaft.app.todo.dto.UpdateMilestoneRequest;
import com.mannschaft.app.todo.dto.UpdateProjectRequest;
import com.mannschaft.app.todo.security.ProjectAccessGuard;
import com.mannschaft.app.todo.service.ProjectService;
import com.mannschaft.app.todo.service.TodoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

import java.util.List;

/**
 * 個人プロジェクトコントローラー（F02.3 個人スコープ）。
 *
 * <p>{@code /api/v1/users/me/projects} 配下の個人スコーププロジェクト・マイルストーン API を提供する。
 * フロントエンド（{@code /my/projects} ページ・{@code useProjectApi}）が呼び出す全 EP をマッピングする。</p>
 *
 * <p><b>認可</b>: {@code /{id}} 系（詳細・更新・削除・完了・再開・マイルストーン CRUD・todos）は
 * 各 EP の先頭で {@link ProjectAccessGuard#validatePersonalProjectAccess(Long, Long)} を呼び、
 * <b>プロジェクト所有者本人に限定</b>する（他ユーザーのプロジェクト ID は 404 で存在を秘匿）。</p>
 *
 * <p>一覧／作成はスコープ ID を常に {@code SecurityUtils.getCurrentUserId()} で確定した認証主体の ID に
 * 固定しており、リクエストからは指定できない（自己スコープ）。対象リソースが未特定のため
 * ガード呼び出しは持たず、契約テスト {@code TodoPersonalScopeContractIT} で
 * 「他ユーザーのプロジェクトが混入しないこと」を固定する。</p>
 */
@RestController
@RequestMapping("/api/v1/users/me/projects")
@Tag(name = "プロジェクト（個人）", description = "F02.3 個人プロジェクト管理")
@RequiredArgsConstructor
public class UserProjectController {

    private final ProjectService projectService;
    private final TodoService todoService;
    private final ProjectAccessGuard projectAccessGuard;

    // === Projects ===

    /**
     * 個人プロジェクト一覧を取得する。
     */
    @SelfScopedEndpoint("ProjectService#listProjects が"
            + " TodoScopeType.PERSONAL + userId=SecurityUtils.getCurrentUserId() のみを検索条件に使う"
            + "（TodoPersonalScopeContractIT の 6. で固定）")
    @GetMapping
    @Operation(summary = "プロジェクト一覧（個人）")
    public ResponseEntity<PagedResponse<ProjectResponse>> listProjects(
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(projectService.listProjects(
                TodoScopeType.PERSONAL, userId, ProjectStatus.valueOf(status), page, size));
    }

    /**
     * 個人プロジェクトを作成する。
     */
    @SelfScopedEndpoint("ProjectService#createProject が"
            + " TodoScopeType.PERSONAL + userId=SecurityUtils.getCurrentUserId() を所有者として登録する"
            + "（TodoPersonalScopeContractIT の 6. で固定）")
    @PostMapping
    @Operation(summary = "プロジェクト作成（個人）")
    public ResponseEntity<ApiResponse<ProjectResponse>> createProject(
            @Valid @RequestBody CreateProjectRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.createProject(TodoScopeType.PERSONAL, userId, request, userId));
    }

    /**
     * 個人プロジェクト詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "プロジェクト詳細（個人）")
    public ResponseEntity<ApiResponse<ProjectDetailResponse>> getProject(@PathVariable Long id) {
        projectAccessGuard.validatePersonalProjectAccess(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(projectService.getProject(id));
    }

    /**
     * 個人プロジェクトを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "プロジェクト更新（個人）")
    public ResponseEntity<ApiResponse<ProjectResponse>> updateProject(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProjectRequest request) {
        projectAccessGuard.validatePersonalProjectAccess(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(projectService.updateProject(id, request));
    }

    /**
     * 個人プロジェクトを削除する（論理削除）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "プロジェクト削除（個人）")
    public ResponseEntity<Void> deleteProject(@PathVariable Long id) {
        projectAccessGuard.validatePersonalProjectAccess(SecurityUtils.getCurrentUserId(), id);
        projectService.deleteProject(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 個人プロジェクトを手動完了にする。
     */
    @PatchMapping("/{id}/complete")
    @Operation(summary = "プロジェクト手動完了（個人）")
    public ResponseEntity<ApiResponse<ProjectResponse>> completeProject(@PathVariable Long id) {
        projectAccessGuard.validatePersonalProjectAccess(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(projectService.completeProject(id));
    }

    /**
     * 完了した個人プロジェクトを再開する。
     */
    @PatchMapping("/{id}/reopen")
    @Operation(summary = "プロジェクト再開（個人）")
    public ResponseEntity<ApiResponse<ProjectResponse>> reopenProject(@PathVariable Long id) {
        projectAccessGuard.validatePersonalProjectAccess(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(projectService.reopenProject(id));
    }

    // === Milestones ===

    /**
     * 個人プロジェクトのマイルストーン一覧を取得する。
     */
    @GetMapping("/{id}/milestones")
    @Operation(summary = "マイルストーン一覧（個人）")
    public ResponseEntity<ApiResponse<List<MilestoneResponse>>> listMilestones(@PathVariable Long id) {
        projectAccessGuard.validatePersonalProjectAccess(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(projectService.listMilestones(id));
    }

    /**
     * 個人プロジェクトにマイルストーンを作成する。
     */
    @PostMapping("/{id}/milestones")
    @Operation(summary = "マイルストーン作成（個人）")
    public ResponseEntity<ApiResponse<MilestoneResponse>> createMilestone(
            @PathVariable Long id,
            @Valid @RequestBody CreateMilestoneRequest request) {
        projectAccessGuard.validatePersonalProjectAccess(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.createMilestone(id, request));
    }

    /**
     * 個人プロジェクトのマイルストーンを更新する。
     */
    @PutMapping("/{id}/milestones/{mid}")
    @Operation(summary = "マイルストーン更新（個人）")
    public ResponseEntity<ApiResponse<MilestoneResponse>> updateMilestone(
            @PathVariable Long id,
            @PathVariable Long mid,
            @Valid @RequestBody UpdateMilestoneRequest request) {
        projectAccessGuard.validatePersonalProjectAccess(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(projectService.updateMilestone(id, mid, request));
    }

    /**
     * 個人プロジェクトのマイルストーンを削除する。
     */
    @DeleteMapping("/{id}/milestones/{mid}")
    @Operation(summary = "マイルストーン削除（個人）")
    public ResponseEntity<Void> deleteMilestone(
            @PathVariable Long id,
            @PathVariable Long mid) {
        projectAccessGuard.validatePersonalProjectAccess(SecurityUtils.getCurrentUserId(), id);
        projectService.deleteMilestone(id, mid);
        return ResponseEntity.noContent().build();
    }

    /**
     * 個人プロジェクトのマイルストーンを完了にする。
     */
    @PatchMapping("/{id}/milestones/{mid}/complete")
    @Operation(summary = "マイルストーン完了（個人）")
    public ResponseEntity<ApiResponse<MilestoneResponse>> completeMilestone(
            @PathVariable Long id,
            @PathVariable Long mid) {
        projectAccessGuard.validatePersonalProjectAccess(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(projectService.completeMilestone(id, mid));
    }

    // === Project Todos ===

    /**
     * 個人プロジェクト内の TODO 一覧を取得する。
     */
    @GetMapping("/{id}/todos")
    @Operation(summary = "プロジェクト内TODO一覧（個人）")
    public ResponseEntity<ApiResponse<List<TodoResponse>>> listProjectTodos(@PathVariable Long id) {
        projectAccessGuard.validatePersonalProjectAccess(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(todoService.listProjectTodos(id));
    }
}
