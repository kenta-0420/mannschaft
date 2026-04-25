package com.mannschaft.app.shift.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.shift.dto.WorkConstraintRequest;
import com.mannschaft.app.shift.dto.WorkConstraintResponse;
import com.mannschaft.app.shift.service.ShiftWorkConstraintService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * シフト勤務制約コントローラー。チームおよびメンバー個別の勤務制約 CRUD API を提供する。
 */
@RestController
@RequestMapping("/api/v1/shifts/teams")
@Tag(name = "シフト勤務制約", description = "F03.5 チームおよびメンバー個別の勤務制約 CRUD")
@RequiredArgsConstructor
public class ShiftWorkConstraintController {

    private final ShiftWorkConstraintService workConstraintService;

    /**
     * チームの勤務制約一覧を取得する（デフォルト + メンバー個別全件）。
     */
    @GetMapping("/{teamId}/work-constraints")
    @Operation(summary = "勤務制約一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<WorkConstraintResponse>>> getConstraints(
            @PathVariable Long teamId) {
        List<WorkConstraintResponse> responses = workConstraintService.getConstraints(teamId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * チームデフォルト勤務制約を作成または更新する。
     */
    @PutMapping("/{teamId}/work-constraints")
    @Operation(summary = "チームデフォルト勤務制約更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<WorkConstraintResponse>> upsertDefault(
            @PathVariable Long teamId,
            @RequestBody WorkConstraintRequest request) {
        WorkConstraintResponse response = workConstraintService.upsertDefault(teamId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メンバー個別の勤務制約を作成または更新する。
     */
    @PutMapping("/{teamId}/work-constraints/{userId}")
    @Operation(summary = "メンバー個別勤務制約更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<WorkConstraintResponse>> upsertMember(
            @PathVariable Long teamId,
            @PathVariable Long userId,
            @RequestBody WorkConstraintRequest request) {
        WorkConstraintResponse response = workConstraintService.upsertMember(teamId, userId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メンバー個別の勤務制約を削除する。
     */
    @DeleteMapping("/{teamId}/work-constraints/{userId}")
    @Operation(summary = "メンバー個別勤務制約削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteMember(
            @PathVariable Long teamId,
            @PathVariable Long userId) {
        workConstraintService.deleteMember(teamId, userId);
        return ResponseEntity.noContent().build();
    }
}
