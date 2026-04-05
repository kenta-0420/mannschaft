package com.mannschaft.app.shift.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.shift.dto.CreateSwapRequestRequest;
import com.mannschaft.app.shift.dto.ResolveSwapRequestRequest;
import com.mannschaft.app.shift.dto.SwapRequestResponse;
import com.mannschaft.app.shift.service.ShiftSwapService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * シフト交代リクエストコントローラー。交代申請・承諾・承認フローAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/shifts/swap-requests")
@Tag(name = "シフト交代リクエスト管理", description = "F03.5 シフト交代リクエストの申請・承認フロー")
@RequiredArgsConstructor
public class ShiftSwapController {

    private final ShiftSwapService swapService;


    /**
     * 交代リクエスト一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "交代リクエスト一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<SwapRequestResponse>>> listSwapRequests(
            @RequestParam(required = false) String status) {
        List<SwapRequestResponse> responses = swapService.listSwapRequests(status);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 交代リクエストを作成する。
     */
    @PostMapping
    @Operation(summary = "交代リクエスト作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<SwapRequestResponse>> createSwapRequest(
            @Valid @RequestBody CreateSwapRequestRequest request) {
        SwapRequestResponse response = swapService.createSwapRequest(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 交代リクエストを承諾する（交代相手）。
     */
    @PostMapping("/{swapId}/accept")
    @Operation(summary = "交代リクエスト承諾")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承諾成功")
    public ResponseEntity<ApiResponse<SwapRequestResponse>> acceptSwapRequest(
            @PathVariable Long swapId) {
        SwapRequestResponse response = swapService.acceptSwapRequest(swapId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 交代リクエストを承認・却下する（管理者）。
     */
    @PostMapping("/{swapId}/resolve")
    @Operation(summary = "交代リクエスト承認・却下")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "処理成功")
    public ResponseEntity<ApiResponse<SwapRequestResponse>> resolveSwapRequest(
            @PathVariable Long swapId,
            @Valid @RequestBody ResolveSwapRequestRequest request) {
        SwapRequestResponse response = swapService.resolveSwapRequest(swapId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 交代リクエストをキャンセルする。
     */
    @DeleteMapping("/{swapId}")
    @Operation(summary = "交代リクエストキャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "キャンセル成功")
    public ResponseEntity<Void> cancelSwapRequest(
            @PathVariable Long swapId) {
        swapService.cancelSwapRequest(swapId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
