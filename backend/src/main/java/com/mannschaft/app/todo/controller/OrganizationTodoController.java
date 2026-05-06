package com.mannschaft.app.todo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.dto.GanttTodoResponse;
import com.mannschaft.app.todo.service.TodoGanttService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 組織TODOコントローラー。組織スコープのTODO関連APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "TODO（組織）", description = "F02.3 組織TODO管理")
@RequiredArgsConstructor
public class OrganizationTodoController {

    private final TodoGanttService ganttService;

    // ========================================
    // ガントバー
    // ========================================

    /**
     * 組織ガントバー用TODO一覧を取得する。
     * fromDate・toDate は必須（from > to の場合は400）。
     */
    @GetMapping("/{orgId}/todos/gantt")
    @Operation(summary = "組織ガントバー用TODO一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "from > to の場合")
    public ResponseEntity<ApiResponse<List<GanttTodoResponse>>> getOrgGanttTodos(
            @PathVariable Long orgId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from.isAfter(to)) {
            return ResponseEntity.badRequest().build();
        }
        List<GanttTodoResponse> result = ganttService.getGanttTodos(
                TodoScopeType.ORGANIZATION, orgId, from, to);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
