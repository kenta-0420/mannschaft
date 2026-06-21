package com.mannschaft.app.todo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.organization.service.OrganizationService;
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
 * 組織プロジェクトコントローラー。組織スコープのプロジェクト・マイルストーン API を提供する。
 *
 * <p>{@link TeamProjectController} の写経。teamId → {@code teamService.resolveTeamId} の代わりに
 * {@code organizationService.resolveOrgId(slug)} で組織内部 ID を解決し、
 * {@link ProjectService} に {@link TodoScopeType#ORGANIZATION} と orgId を渡す。</p>
 *
 * <p><b>試練フェーズの骨格</b>: 一覧／作成の scopeId 配線（ORGANIZATION + orgId）は入れてあるが、
 * 認可ゲート（{@link ProjectAccessGuard#validateOrgMembership(Long, Long)} /
 * {@link ProjectAccessGuard#validateOrgProjectAccess(Long, Long, Long)}）は <b>まだ呼んでいない</b>。
 * そのため非メンバー（AC-1）・別組織 IDOR（AC-3）・マイルストーン系（AC-5）のテストは red になる。
 * 出陣フェーズで各 EP に guard 呼び出しを配線し green 化すること。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{slug}/projects")
@Tag(name = "プロジェクト（組織）", description = "F02.3 組織プロジェクト管理")
@RequiredArgsConstructor
public class OrgProjectController {

    private final ProjectService projectService;
    private final TodoService todoService;
    private final OrganizationService organizationService;
    // 試練フェーズで注入。出陣で各 EP の認可ゲートとして配線する（現状未呼び出し → IDOR/非メンバーテストが red）。
    private final ProjectAccessGuard projectAccessGuard;

    /**
     * プロジェクト一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "プロジェクト一覧（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ProjectResponse>> listProjects(
            @PathVariable String slug,
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long orgId = organizationService.resolveOrgId(slug);
        // TODO(出陣): projectAccessGuard.validateOrgMembership(SecurityUtils.getCurrentUserId(), orgId);
        return ResponseEntity.ok(projectService.listProjects(
                TodoScopeType.ORGANIZATION, orgId, ProjectStatus.valueOf(status), page, size));
    }

    /**
     * プロジェクトを作成する。
     */
    @PostMapping
    @Operation(summary = "プロジェクト作成（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ProjectResponse>> createProject(
            @PathVariable String slug,
            @Valid @RequestBody CreateProjectRequest request) {
        Long orgId = organizationService.resolveOrgId(slug);
        // TODO(出陣): projectAccessGuard.validateOrgMembership(SecurityUtils.getCurrentUserId(), orgId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.createProject(
                        TodoScopeType.ORGANIZATION, orgId, request, SecurityUtils.getCurrentUserId()));
    }

    /**
     * プロジェクト詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "プロジェクト詳細（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ProjectDetailResponse>> getProject(
            @PathVariable String slug,
            @PathVariable Long id) {
        Long orgId = organizationService.resolveOrgId(slug);
        // TODO(出陣): projectAccessGuard.validateOrgProjectAccess(SecurityUtils.getCurrentUserId(), orgId, id);
        return ResponseEntity.ok(projectService.getProject(id));
    }

    /**
     * プロジェクトを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "プロジェクト更新（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ProjectResponse>> updateProject(
            @PathVariable String slug,
            @PathVariable Long id,
            @Valid @RequestBody UpdateProjectRequest request) {
        Long orgId = organizationService.resolveOrgId(slug);
        // TODO(出陣): projectAccessGuard.validateOrgProjectAccess(SecurityUtils.getCurrentUserId(), orgId, id);
        return ResponseEntity.ok(projectService.updateProject(id, request));
    }

    /**
     * プロジェクトを削除する（論理削除）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "プロジェクト削除（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteProject(
            @PathVariable String slug,
            @PathVariable Long id) {
        Long orgId = organizationService.resolveOrgId(slug);
        // TODO(出陣): projectAccessGuard.validateOrgProjectAccess(SecurityUtils.getCurrentUserId(), orgId, id);
        projectService.deleteProject(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * プロジェクトを手動完了にする。
     */
    @PatchMapping("/{id}/complete")
    @Operation(summary = "プロジェクト手動完了（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "完了成功")
    public ResponseEntity<ApiResponse<ProjectResponse>> completeProject(
            @PathVariable String slug,
            @PathVariable Long id) {
        Long orgId = organizationService.resolveOrgId(slug);
        // TODO(出陣): projectAccessGuard.validateOrgProjectAccess(SecurityUtils.getCurrentUserId(), orgId, id);
        return ResponseEntity.ok(projectService.completeProject(id));
    }

    /**
     * 完了プロジェクトを再開する。
     */
    @PatchMapping("/{id}/reopen")
    @Operation(summary = "プロジェクト再開（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "再開成功")
    public ResponseEntity<ApiResponse<ProjectResponse>> reopenProject(
            @PathVariable String slug,
            @PathVariable Long id) {
        Long orgId = organizationService.resolveOrgId(slug);
        // TODO(出陣): projectAccessGuard.validateOrgProjectAccess(SecurityUtils.getCurrentUserId(), orgId, id);
        return ResponseEntity.ok(projectService.reopenProject(id));
    }

    // --- マイルストーン ---

    /**
     * マイルストーン一覧を取得する。
     */
    @GetMapping("/{id}/milestones")
    @Operation(summary = "マイルストーン一覧（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<MilestoneResponse>>> listMilestones(
            @PathVariable String slug,
            @PathVariable Long id) {
        Long orgId = organizationService.resolveOrgId(slug);
        // TODO(出陣): projectAccessGuard.validateOrgProjectAccess(SecurityUtils.getCurrentUserId(), orgId, id);
        return ResponseEntity.ok(projectService.listMilestones(id));
    }

    /**
     * マイルストーンを作成する。
     */
    @PostMapping("/{id}/milestones")
    @Operation(summary = "マイルストーン作成（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<MilestoneResponse>> createMilestone(
            @PathVariable String slug,
            @PathVariable Long id,
            @Valid @RequestBody CreateMilestoneRequest request) {
        Long orgId = organizationService.resolveOrgId(slug);
        // TODO(出陣): projectAccessGuard.validateOrgProjectAccess(SecurityUtils.getCurrentUserId(), orgId, id);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.createMilestone(id, request));
    }

    /**
     * マイルストーンを更新する。
     */
    @PutMapping("/{id}/milestones/{mid}")
    @Operation(summary = "マイルストーン更新（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<MilestoneResponse>> updateMilestone(
            @PathVariable String slug,
            @PathVariable Long id,
            @PathVariable Long mid,
            @Valid @RequestBody UpdateMilestoneRequest request) {
        Long orgId = organizationService.resolveOrgId(slug);
        // TODO(出陣): projectAccessGuard.validateOrgProjectAccess(SecurityUtils.getCurrentUserId(), orgId, id);
        return ResponseEntity.ok(projectService.updateMilestone(id, mid, request));
    }

    /**
     * マイルストーンを削除する。
     */
    @DeleteMapping("/{id}/milestones/{mid}")
    @Operation(summary = "マイルストーン削除（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteMilestone(
            @PathVariable String slug,
            @PathVariable Long id,
            @PathVariable Long mid) {
        Long orgId = organizationService.resolveOrgId(slug);
        // TODO(出陣): projectAccessGuard.validateOrgProjectAccess(SecurityUtils.getCurrentUserId(), orgId, id);
        projectService.deleteMilestone(id, mid);
        return ResponseEntity.noContent().build();
    }

    /**
     * マイルストーンを完了にする。
     */
    @PatchMapping("/{id}/milestones/{mid}/complete")
    @Operation(summary = "マイルストーン完了（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "完了成功")
    public ResponseEntity<ApiResponse<MilestoneResponse>> completeMilestone(
            @PathVariable String slug,
            @PathVariable Long id,
            @PathVariable Long mid) {
        Long orgId = organizationService.resolveOrgId(slug);
        // TODO(出陣): projectAccessGuard.validateOrgProjectAccess(SecurityUtils.getCurrentUserId(), orgId, id);
        return ResponseEntity.ok(projectService.completeMilestone(id, mid));
    }

    /**
     * プロジェクト内の TODO 一覧を取得する。
     */
    @GetMapping("/{id}/todos")
    @Operation(summary = "プロジェクト内TODO一覧（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TodoResponse>>> listProjectTodos(
            @PathVariable String slug,
            @PathVariable Long id) {
        Long orgId = organizationService.resolveOrgId(slug);
        // TODO(出陣): projectAccessGuard.validateOrgProjectAccess(SecurityUtils.getCurrentUserId(), orgId, id);
        return ResponseEntity.ok(todoService.listProjectTodos(id));
    }
}
