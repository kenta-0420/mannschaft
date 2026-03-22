package com.mannschaft.app.parking.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.parking.ParkingScopeType;
import com.mannschaft.app.parking.dto.*;
import com.mannschaft.app.parking.service.ParkingAssignmentService;
import com.mannschaft.app.parking.service.ParkingSettingsService;
import com.mannschaft.app.parking.service.ParkingSpaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 組織駐車区画コントローラー。区画管理+設定+統計（17 EP）。
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/parking")
@Tag(name = "組織駐車区画管理", description = "F09.3 組織駐車区画CRUD・割り当て・設定・統計")
@RequiredArgsConstructor
public class OrgParkingSpaceController {

    private final ParkingSpaceService spaceService;
    private final ParkingAssignmentService assignmentService;
    private final ParkingSettingsService settingsService;

    private static final String SCOPE_TYPE = ParkingScopeType.ORGANIZATION.name();

    private Long getCurrentUserId() {
        return 1L;
    }

    @GetMapping("/spaces")
    @Operation(summary = "組織区画一覧")
    public ResponseEntity<PagedResponse<SpaceResponse>> listSpaces(
            @PathVariable Long organizationId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String spaceType,
            @RequestParam(required = false) String floor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SpaceResponse> result = spaceService.list(SCOPE_TYPE, organizationId, status, spaceType, floor,
                PageRequest.of(page, Math.min(size, 100), Sort.by("spaceNumber")));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping("/spaces")
    @Operation(summary = "組織区画作成")
    public ResponseEntity<ApiResponse<SpaceResponse>> createSpace(
            @PathVariable Long organizationId,
            @Valid @RequestBody CreateSpaceRequest request) {
        SpaceResponse result = spaceService.create(SCOPE_TYPE, organizationId, request, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @PostMapping("/spaces/bulk-create")
    @Operation(summary = "組織区画一括作成")
    public ResponseEntity<ApiResponse<List<SpaceResponse>>> bulkCreateSpaces(
            @PathVariable Long organizationId,
            @Valid @RequestBody BulkCreateSpaceRequest request) {
        List<SpaceResponse> result = spaceService.bulkCreate(SCOPE_TYPE, organizationId, request, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @GetMapping("/spaces/{id}")
    @Operation(summary = "組織区画詳細")
    public ResponseEntity<ApiResponse<SpaceDetailResponse>> getSpace(
            @PathVariable Long organizationId, @PathVariable Long id) {
        SpaceDetailResponse result = spaceService.getDetail(SCOPE_TYPE, organizationId, id);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PutMapping("/spaces/{id}")
    @Operation(summary = "組織区画更新")
    public ResponseEntity<ApiResponse<SpaceResponse>> updateSpace(
            @PathVariable Long organizationId, @PathVariable Long id,
            @Valid @RequestBody UpdateSpaceRequest request) {
        SpaceResponse result = spaceService.update(SCOPE_TYPE, organizationId, id, request, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @DeleteMapping("/spaces/{id}")
    @Operation(summary = "組織区画削除")
    public ResponseEntity<Void> deleteSpace(@PathVariable Long organizationId, @PathVariable Long id) {
        spaceService.delete(SCOPE_TYPE, organizationId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/spaces/bulk-assign")
    @Operation(summary = "組織区画一括割り当て")
    public ResponseEntity<ApiResponse<List<AssignmentResponse>>> bulkAssign(
            @PathVariable Long organizationId,
            @Valid @RequestBody BulkAssignRequest request) {
        List<AssignmentResponse> result = assignmentService.bulkAssign(SCOPE_TYPE, organizationId, request, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping("/spaces/vacant")
    @Operation(summary = "組織空き区画一覧")
    public ResponseEntity<ApiResponse<List<SpaceResponse>>> listVacant(@PathVariable Long organizationId) {
        List<SpaceResponse> result = spaceService.listVacant(SCOPE_TYPE, organizationId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PostMapping("/spaces/{id}/assign")
    @Operation(summary = "組織区画割り当て")
    public ResponseEntity<ApiResponse<AssignmentResponse>> assign(
            @PathVariable Long organizationId, @PathVariable Long id,
            @Valid @RequestBody AssignRequest request) {
        AssignmentResponse result = assignmentService.assign(SCOPE_TYPE, organizationId, id, request, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PostMapping("/spaces/{id}/release")
    @Operation(summary = "組織区画解除")
    public ResponseEntity<Void> release(
            @PathVariable Long organizationId, @PathVariable Long id,
            @Valid @RequestBody ReleaseRequest request) {
        assignmentService.release(SCOPE_TYPE, organizationId, id, request, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/spaces/{id}/maintenance")
    @Operation(summary = "組織区画メンテナンス切替")
    public ResponseEntity<ApiResponse<SpaceResponse>> toggleMaintenance(
            @PathVariable Long organizationId, @PathVariable Long id,
            @Valid @RequestBody MaintenanceToggleRequest request) {
        SpaceResponse result = spaceService.toggleMaintenance(SCOPE_TYPE, organizationId, id, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PatchMapping("/spaces/{id}/accept-applications")
    @Operation(summary = "組織区画申請受付開始")
    public ResponseEntity<ApiResponse<SpaceResponse>> acceptApplications(
            @PathVariable Long organizationId, @PathVariable Long id,
            @Valid @RequestBody AcceptApplicationsRequest request) {
        SpaceResponse result = spaceService.acceptApplications(SCOPE_TYPE, organizationId, id, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping("/spaces/{id}/history")
    @Operation(summary = "組織区画割り当て履歴")
    public ResponseEntity<PagedResponse<AssignmentResponse>> getHistory(
            @PathVariable Long organizationId, @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AssignmentResponse> result = spaceService.getHistory(SCOPE_TYPE, organizationId, id,
                PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "assignedAt")));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping("/spaces/swap")
    @Operation(summary = "組織区画交換")
    public ResponseEntity<Void> swap(
            @PathVariable Long organizationId,
            @Valid @RequestBody SwapRequest request) {
        spaceService.swap(SCOPE_TYPE, organizationId, request, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/spaces/{id}/price-history")
    @Operation(summary = "組織区画料金履歴")
    public ResponseEntity<PagedResponse<PriceHistoryResponse>> getPriceHistory(
            @PathVariable Long organizationId, @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PriceHistoryResponse> result = spaceService.getPriceHistory(SCOPE_TYPE, organizationId, id,
                PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "changedAt")));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @GetMapping("/stats")
    @Operation(summary = "組織駐車場統計")
    public ResponseEntity<ApiResponse<ParkingStatsResponse>> getStats(@PathVariable Long organizationId) {
        ParkingStatsResponse result = spaceService.getStats(SCOPE_TYPE, organizationId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping("/settings")
    @Operation(summary = "組織駐車場設定取得")
    public ResponseEntity<ApiResponse<ParkingSettingsResponse>> getSettings(@PathVariable Long organizationId) {
        ParkingSettingsResponse result = settingsService.getSettings(SCOPE_TYPE, organizationId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PutMapping("/settings")
    @Operation(summary = "組織駐車場設定更新")
    public ResponseEntity<ApiResponse<ParkingSettingsResponse>> updateSettings(
            @PathVariable Long organizationId,
            @Valid @RequestBody UpdateSettingsRequest request) {
        ParkingSettingsResponse result = settingsService.updateSettings(SCOPE_TYPE, organizationId, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
