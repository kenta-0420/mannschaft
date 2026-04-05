package com.mannschaft.app.shift.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.shift.dto.CreateShiftRequestRequest;
import com.mannschaft.app.shift.dto.ShiftRequestResponse;
import com.mannschaft.app.shift.dto.ShiftRequestSummaryResponse;
import com.mannschaft.app.shift.dto.UpdateShiftRequestRequest;
import com.mannschaft.app.shift.service.ShiftRequestService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * シフト希望コントローラー。シフト希望の提出・更新・サマリー取得APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/shifts")
@Tag(name = "シフト希望管理", description = "F03.5 シフト希望の提出・管理")
@RequiredArgsConstructor
public class ShiftRequestController {

    private final ShiftRequestService requestService;


    /**
     * スケジュールのシフト希望一覧を取得する。
     */
    @GetMapping("/requests")
    @Operation(summary = "シフト希望一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ShiftRequestResponse>>> listRequests(
            @RequestParam Long scheduleId) {
        List<ShiftRequestResponse> responses = requestService.listRequests(scheduleId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 自分のシフト希望一覧を取得する。
     */
    @GetMapping("/my/requests")
    @Operation(summary = "マイシフト希望一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ShiftRequestResponse>>> listMyRequests() {
        List<ShiftRequestResponse> responses = requestService.listMyRequests(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * シフト希望を提出する。
     */
    @PostMapping("/requests")
    @Operation(summary = "シフト希望提出")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "提出成功")
    public ResponseEntity<ApiResponse<ShiftRequestResponse>> submitRequest(
            @Valid @RequestBody CreateShiftRequestRequest request) {
        ShiftRequestResponse response = requestService.submitRequest(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * シフト希望を更新する。
     */
    @PatchMapping("/requests/{requestId}")
    @Operation(summary = "シフト希望更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ShiftRequestResponse>> updateRequest(
            @PathVariable Long requestId,
            @Valid @RequestBody UpdateShiftRequestRequest request) {
        ShiftRequestResponse response = requestService.updateRequest(requestId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * シフト希望を削除する。
     */
    @DeleteMapping("/requests/{requestId}")
    @Operation(summary = "シフト希望削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteRequest(
            @PathVariable Long requestId) {
        requestService.deleteRequest(requestId);
        return ResponseEntity.noContent().build();
    }

    /**
     * シフト希望提出サマリーを取得する。
     */
    @GetMapping("/requests/summary")
    @Operation(summary = "シフト希望提出サマリー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ShiftRequestSummaryResponse>> getRequestSummary(
            @RequestParam Long scheduleId) {
        ShiftRequestSummaryResponse response = requestService.getRequestSummary(scheduleId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
