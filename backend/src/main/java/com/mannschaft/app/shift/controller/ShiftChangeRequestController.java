package com.mannschaft.app.shift.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shift.dto.ChangeRequestResponse;
import com.mannschaft.app.shift.dto.CreateChangeRequestRequest;
import com.mannschaft.app.shift.dto.ReviewChangeRequestRequest;
import com.mannschaft.app.shift.service.ShiftChangeRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

/**
 * シフト変更依頼コントローラー。
 * A-1確定前変更・A-2個別交代・A-3オープンコールの依頼 API を提供する。
 */
@RestController
@RequestMapping("/api/v1/shifts/change-requests")
@Tag(name = "シフト変更依頼管理", description = "F03.5 シフト変更依頼の申請・審査フロー")
@RequiredArgsConstructor
public class ShiftChangeRequestController {

    private final ShiftChangeRequestService changeRequestService;

    /**
     * 変更依頼を作成する。
     */
    @PostMapping
    @Operation(summary = "変更依頼作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ChangeRequestResponse>> createChangeRequest(
            @Valid @RequestBody CreateChangeRequestRequest request) {
        ChangeRequestResponse response = changeRequestService.create(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 変更依頼一覧を取得する（scheduleId クエリパラメータ必須）。
     * ADMIN は全件、MEMBER は自分の依頼のみ返す。
     */
    @GetMapping
    @Operation(summary = "変更依頼一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ChangeRequestResponse>>> listChangeRequests(
            @RequestParam Long scheduleId,
            @RequestParam(defaultValue = "MEMBER") String role) {
        List<ChangeRequestResponse> responses = changeRequestService.list(
                scheduleId, SecurityUtils.getCurrentUserId(), role);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 変更依頼詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "変更依頼詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ChangeRequestResponse>> getChangeRequest(
            @PathVariable Long id) {
        ChangeRequestResponse response = changeRequestService.get(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 変更依頼を審査する（ADMIN のみ）。
     */
    @PatchMapping("/{id}/review")
    @Operation(summary = "変更依頼審査")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "審査成功")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ChangeRequestResponse>> reviewChangeRequest(
            @PathVariable Long id,
            @Valid @RequestBody ReviewChangeRequestRequest request) {
        ChangeRequestResponse response = changeRequestService.review(id, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 変更依頼を取り下げる（依頼者のみ、OPEN のもの）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "変更依頼取下")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "取下成功")
    public ResponseEntity<Void> withdrawChangeRequest(
            @PathVariable Long id) {
        changeRequestService.withdraw(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
