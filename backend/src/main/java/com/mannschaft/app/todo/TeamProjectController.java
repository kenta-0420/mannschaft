package com.mannschaft.app.todo;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.todo.dto.CreateMilestoneRequest;
import com.mannschaft.app.todo.dto.CreateProjectRequest;
import com.mannschaft.app.todo.dto.MilestoneResponse;
import com.mannschaft.app.todo.dto.ProjectDetailResponse;
import com.mannschaft.app.todo.dto.ProjectResponse;
import com.mannschaft.app.todo.dto.TodoResponse;
import com.mannschaft.app.todo.dto.UpdateMilestoneRequest;
import com.mannschaft.app.todo.dto.UpdateProjectRequest;
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
 * チームプロジェクトコントローラー。チームスコープのプロジェクト・マイルストーンAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/projects")
@Tag(name = "プロジェクト（チーム）", description = "F02.3 チームプロジェクト管理")
@RequiredArgsConstructor
public class TeamProjectController {

    private final ProjectService projectService;
    private final TodoService todoService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * プロジェクト一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "プロジェクト一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ProjectResponse>> listProjects(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int perPage) {
        return ResponseEntity.ok(projectService.listProjects(
                TodoScopeType.TEAM, teamId, ProjectStatus.valueOf(status), page, perPage));
    }

    /**
     * プロジェクトを作成する。
     */
    @PostMapping
    @Operation(summary = "プロジェクト作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ProjectResponse>> createProject(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.createProject(TodoScopeType.TEAM, teamId, request, getCurrentUserId()));
    }

    /**
     * プロジェクト詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "プロジェクト詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ProjectDetailResponse>> getProject(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        return ResponseEntity.ok(projectService.getProject(id));
    }

    /**
     * プロジェクトを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "プロジェクト更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ProjectResponse>> updateProject(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateProjectRequest request) {
        return ResponseEntity.ok(projectService.updateProject(id, request));
    }

    /**
     * プロジェクトを削除する（論理削除）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "プロジェクト削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteProject(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        projectService.deleteProject(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * プロジェクトを手動完了にする。
     */
    @PatchMapping("/{id}/complete")
    @Operation(summary = "プロジェクト手動完了")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "完了成功")
    public ResponseEntity<ApiResponse<ProjectResponse>> completeProject(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        return ResponseEntity.ok(projectService.completeProject(id));
    }

    /**
     * 完了プロジェクトを再開する。
     */
    @PatchMapping("/{id}/reopen")
    @Operation(summary = "プロジェクト再開")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "再開成功")
    public ResponseEntity<ApiResponse<ProjectResponse>> reopenProject(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        return ResponseEntity.ok(projectService.reopenProject(id));
    }

    // --- マイルストーン ---

    /**
     * マイルストーン一覧を取得する。
     */
    @GetMapping("/{id}/milestones")
    @Operation(summary = "マイルストーン一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<MilestoneResponse>>> listMilestones(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        return ResponseEntity.ok(projectService.listMilestones(id));
    }

    /**
     * マイルストーンを作成する。
     */
    @PostMapping("/{id}/milestones")
    @Operation(summary = "マイルストーン作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<MilestoneResponse>> createMilestone(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody CreateMilestoneRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.createMilestone(id, request));
    }

    /**
     * マイルストーンを更新する。
     */
    @PutMapping("/{id}/milestones/{mid}")
    @Operation(summary = "マイルストーン更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<MilestoneResponse>> updateMilestone(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @PathVariable Long mid,
            @Valid @RequestBody UpdateMilestoneRequest request) {
        return ResponseEntity.ok(projectService.updateMilestone(id, mid, request));
    }

    /**
     * マイルストーンを削除する。
     */
    @DeleteMapping("/{id}/milestones/{mid}")
    @Operation(summary = "マイルストーン削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteMilestone(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @PathVariable Long mid) {
        projectService.deleteMilestone(id, mid);
        return ResponseEntity.noContent().build();
    }

    /**
     * マイルストーンを完了にする。
     */
    @PatchMapping("/{id}/milestones/{mid}/complete")
    @Operation(summary = "マイルストーン完了")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "完了成功")
    public ResponseEntity<ApiResponse<MilestoneResponse>> completeMilestone(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @PathVariable Long mid) {
        return ResponseEntity.ok(projectService.completeMilestone(id, mid));
    }

    /**
     * プロジェクト内のTODO一覧を取得する。
     */
    @GetMapping("/{id}/todos")
    @Operation(summary = "プロジェクト内TODO一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TodoResponse>>> listProjectTodos(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        return ResponseEntity.ok(todoService.listProjectTodos(id));
    }
}
