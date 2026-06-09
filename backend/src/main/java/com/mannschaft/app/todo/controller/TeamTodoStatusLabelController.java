package com.mannschaft.app.todo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.todo.TodoStatusLabelScope;
import com.mannschaft.app.todo.dto.CreateTodoStatusLabelRequest;
import com.mannschaft.app.todo.dto.TodoStatusLabelResponse;
import com.mannschaft.app.todo.dto.UpdateTodoStatusLabelRequest;
import com.mannschaft.app.todo.service.TodoStatusLabelService;
import com.mannschaft.app.team.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * チームスコープ TODO ステータスラベル管理 API（F02.3.1 Phase 1a）。
 *
 * <p>一覧はチームメンバー全員が参照可、CRUD は ADMIN/DEPUTY_ADMIN のみ。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/todo-status-labels")
@Tag(name = "TODO ステータスラベル（チーム）", description = "F02.3.1 チームスコープのカスタムステータスラベル CRUD")
@RequiredArgsConstructor
public class TeamTodoStatusLabelController {

    private final TodoStatusLabelService labelService;
    private final TeamService teamService;

    @GetMapping
    @Operation(summary = "チームステータスラベル一覧（SYSTEM 既定 + チームスコープ）")
    public ResponseEntity<ApiResponse<List<TodoStatusLabelResponse>>> list(@PathVariable String teamId) {
        Long internalTeamId = teamService.resolveTeamId(teamId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                labelService.list(TodoStatusLabelScope.TEAM, internalTeamId, userId)));
    }

    @PostMapping
    @Operation(summary = "チームステータスラベル作成（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<TodoStatusLabelResponse>> create(
            @PathVariable String teamId,
            @Valid @RequestBody CreateTodoStatusLabelRequest request) {
        Long internalTeamId = teamService.resolveTeamId(teamId);
        Long userId = SecurityUtils.getCurrentUserId();
        TodoStatusLabelResponse response = labelService.create(
                TodoStatusLabelScope.TEAM, internalTeamId, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PutMapping("/{labelId}")
    @Operation(summary = "チームステータスラベル更新（ADMIN のみ）")
    public ResponseEntity<ApiResponse<TodoStatusLabelResponse>> update(
            @PathVariable String teamId,
            @PathVariable Long labelId,
            @Valid @RequestBody UpdateTodoStatusLabelRequest request) {
        Long internalTeamId = teamService.resolveTeamId(teamId);
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                labelService.update(labelId, TodoStatusLabelScope.TEAM, internalTeamId, request, userId)));
    }

    @DeleteMapping("/{labelId}")
    @Operation(summary = "チームステータスラベル削除（ADMIN のみ）")
    public ResponseEntity<Void> delete(@PathVariable String teamId, @PathVariable Long labelId) {
        Long internalTeamId = teamService.resolveTeamId(teamId);
        Long userId = SecurityUtils.getCurrentUserId();
        labelService.delete(labelId, TodoStatusLabelScope.TEAM, internalTeamId, userId);
        return ResponseEntity.noContent().build();
    }
}
