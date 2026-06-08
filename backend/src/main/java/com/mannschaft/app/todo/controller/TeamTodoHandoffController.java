package com.mannschaft.app.todo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.dto.TodoHandoffRequest;
import com.mannschaft.app.todo.dto.TodoHandoffResponse;
import com.mannschaft.app.todo.service.TodoHandoffService;
import com.mannschaft.app.team.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * チームスコープ TODO キャッチボール API（F02.3.1 Phase 2）。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/todos/{todoId}")
@Tag(name = "TODO キャッチボール（チーム）", description = "F02.3.1 チームTODO の引き渡し操作と履歴取得")
@RequiredArgsConstructor
public class TeamTodoHandoffController {

    private final TodoHandoffService handoffService;
    private final TeamService teamService;

    /**
     * チーム TODO を別メンバーへ渡す。
     */
    @PostMapping("/handoff")
    @Operation(summary = "TODO キャッチボール（引き渡し）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "引き渡し成功（履歴行を新規作成）")
    public ResponseEntity<ApiResponse<TodoHandoffResponse>> handoff(
            @PathVariable UUID teamId,
            @PathVariable Long todoId,
            @Valid @RequestBody TodoHandoffRequest request) {
        Long internalTeamId = teamService.resolveTeamId(teamId);
        // 履歴行（todo_handoffs）を1行新規作成するため、リソース新規作成を表す 201 Created を返す
        return ResponseEntity.status(HttpStatus.CREATED).body(handoffService.handoff(
                TodoScopeType.TEAM, internalTeamId, todoId, request, SecurityUtils.getCurrentUserId()));
    }

    /**
     * チーム TODO のキャッチボール履歴を取得する。
     */
    @GetMapping("/handoffs")
    @Operation(summary = "TODO キャッチボール履歴")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TodoHandoffResponse>>> listHistory(
            @PathVariable UUID teamId,
            @PathVariable Long todoId) {
        Long internalTeamId = teamService.resolveTeamId(teamId);
        return ResponseEntity.ok(handoffService.listHistory(
                TodoScopeType.TEAM, internalTeamId, todoId, SecurityUtils.getCurrentUserId()));
    }
}
