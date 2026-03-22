package com.mannschaft.app.parking.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.parking.ParkingScopeType;
import com.mannschaft.app.parking.dto.AcceptApplicationsRequest;
import com.mannschaft.app.parking.dto.AssignRequest;
import com.mannschaft.app.parking.dto.AssignmentResponse;
import com.mannschaft.app.parking.dto.BulkAssignRequest;
import com.mannschaft.app.parking.dto.BulkCreateSpaceRequest;
import com.mannschaft.app.parking.dto.CreateSpaceRequest;
import com.mannschaft.app.parking.dto.MaintenanceToggleRequest;
import com.mannschaft.app.parking.dto.ParkingSettingsResponse;
import com.mannschaft.app.parking.dto.ParkingStatsResponse;
import com.mannschaft.app.parking.dto.PriceHistoryResponse;
import com.mannschaft.app.parking.dto.ReleaseRequest;
import com.mannschaft.app.parking.dto.SpaceDetailResponse;
import com.mannschaft.app.parking.dto.SpaceResponse;
import com.mannschaft.app.parking.dto.SwapRequest;
import com.mannschaft.app.parking.dto.UpdateSettingsRequest;
import com.mannschaft.app.parking.dto.UpdateSpaceRequest;
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
 * チーム駐車区画コントローラー。区画管理+設定+統計（17 EP）。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/parking")
@Tag(name = "チーム駐車区画管理", description = "F09.3 チーム駐車区画CRUD・割り当て・設定・統計")
@RequiredArgsConstructor
public class TeamParkingSpaceController {

    private final ParkingSpaceService spaceService;
    private final ParkingAssignmentService assignmentService;
    private final ParkingSettingsService settingsService;

    private static final String SCOPE_TYPE = ParkingScopeType.TEAM.name();

    private Long getCurrentUserId() {
        return 1L;
    }

    @GetMapping("/spaces")
    @Operation(summary = "チーム区画一覧")
    public ResponseEntity<PagedResponse<SpaceResponse>> listSpaces(
            @PathVariable Long teamId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String spaceType,
            @RequestParam(required = false) String floor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SpaceResponse> result = spaceService.list(SCOPE_TYPE, teamId, status, spaceType, floor,
                PageRequest.of(page, Math.min(size, 100), Sort.by("spaceNumber")));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping("/spaces")
    @Operation(summary = "チーム区画作成")
    public ResponseEntity<ApiResponse<SpaceResponse>> createSpace(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateSpaceRequest request) {
        SpaceResponse result = spaceService.create(SCOPE_TYPE, teamId, request, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @PostMapping("/spaces/bulk-create")
    @Operation(summary = "チーム区画一括作成")
    public ResponseEntity<ApiResponse<List<SpaceResponse>>> bulkCreateSpaces(
            @PathVariable Long teamId,
            @Valid @RequestBody BulkCreateSpaceRequest request) {
        List<SpaceResponse> result = spaceService.bulkCreate(SCOPE_TYPE, teamId, request, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @GetMapping("/spaces/{id}")
    @Operation(summary = "チーム区画詳細")
    public ResponseEntity<ApiResponse<SpaceDetailResponse>> getSpace(
            @PathVariable Long teamId, @PathVariable Long id) {
        SpaceDetailResponse result = spaceService.getDetail(SCOPE_TYPE, teamId, id);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PutMapping("/spaces/{id}")
    @Operation(summary = "チーム区画更新")
    public ResponseEntity<ApiResponse<SpaceResponse>> updateSpace(
            @PathVariable Long teamId, @PathVariable Long id,
            @Valid @RequestBody UpdateSpaceRequest request) {
        SpaceResponse result = spaceService.update(SCOPE_TYPE, teamId, id, request, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @DeleteMapping("/spaces/{id}")
    @Operation(summary = "チーム区画削除")
    public ResponseEntity<Void> deleteSpace(@PathVariable Long teamId, @PathVariable Long id) {
        spaceService.delete(SCOPE_TYPE, teamId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/spaces/bulk-assign")
    @Operation(summary = "チーム区画一括割り当て")
    public ResponseEntity<ApiResponse<List<AssignmentResponse>>> bulkAssign(
            @PathVariable Long teamId,
            @Valid @RequestBody BulkAssignRequest request) {
        List<AssignmentResponse> result = assignmentService.bulkAssign(SCOPE_TYPE, teamId, request, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping("/spaces/vacant")
    @Operation(summary = "チーム空き区画一覧")
    public ResponseEntity<ApiResponse<List<SpaceResponse>>> listVacant(@PathVariable Long teamId) {
        List<SpaceResponse> result = spaceService.listVacant(SCOPE_TYPE, teamId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PostMapping("/spaces/{id}/assign")
    @Operation(summary = "チーム区画割り当て")
    public ResponseEntity<ApiResponse<AssignmentResponse>> assign(
            @PathVariable Long teamId, @PathVariable Long id,
            @Valid @RequestBody AssignRequest request) {
        AssignmentResponse result = assignmentService.assign(SCOPE_TYPE, teamId, id, request, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PostMapping("/spaces/{id}/release")
    @Operation(summary = "チーム区画解除")
    public ResponseEntity<Void> release(
            @PathVariable Long teamId, @PathVariable Long id,
            @Valid @RequestBody ReleaseRequest request) {
        assignmentService.release(SCOPE_TYPE, teamId, id, request, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/spaces/{id}/maintenance")
    @Operation(summary = "チーム区画メンテナンス切替")
    public ResponseEntity<ApiResponse<SpaceResponse>> toggleMaintenance(
            @PathVariable Long teamId, @PathVariable Long id,
            @Valid @RequestBody MaintenanceToggleRequest request) {
        SpaceResponse result = spaceService.toggleMaintenance(SCOPE_TYPE, teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PatchMapping("/spaces/{id}/accept-applications")
    @Operation(summary = "チーム区画申請受付開始")
    public ResponseEntity<ApiResponse<SpaceResponse>> acceptApplications(
            @PathVariable Long teamId, @PathVariable Long id,
            @Valid @RequestBody AcceptApplicationsRequest request) {
        SpaceResponse result = spaceService.acceptApplications(SCOPE_TYPE, teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping("/spaces/{id}/history")
    @Operation(summary = "チーム区画割り当て履歴")
    public ResponseEntity<PagedResponse<AssignmentResponse>> getHistory(
            @PathVariable Long teamId, @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AssignmentResponse> result = spaceService.getHistory(SCOPE_TYPE, teamId, id,
                PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "assignedAt")));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping("/spaces/swap")
    @Operation(summary = "チーム区画交換")
    public ResponseEntity<Void> swap(
            @PathVariable Long teamId,
            @Valid @RequestBody SwapRequest request) {
        spaceService.swap(SCOPE_TYPE, teamId, request, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/spaces/{id}/price-history")
    @Operation(summary = "チーム区画料金履歴")
    public ResponseEntity<PagedResponse<PriceHistoryResponse>> getPriceHistory(
            @PathVariable Long teamId, @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PriceHistoryResponse> result = spaceService.getPriceHistory(SCOPE_TYPE, teamId, id,
                PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "changedAt")));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @GetMapping("/stats")
    @Operation(summary = "チーム駐車場統計")
    public ResponseEntity<ApiResponse<ParkingStatsResponse>> getStats(@PathVariable Long teamId) {
        ParkingStatsResponse result = spaceService.getStats(SCOPE_TYPE, teamId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping("/settings")
    @Operation(summary = "チーム駐車場設定取得")
    public ResponseEntity<ApiResponse<ParkingSettingsResponse>> getSettings(@PathVariable Long teamId) {
        ParkingSettingsResponse result = settingsService.getSettings(SCOPE_TYPE, teamId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PutMapping("/settings")
    @Operation(summary = "チーム駐車場設定更新")
    public ResponseEntity<ApiResponse<ParkingSettingsResponse>> updateSettings(
            @PathVariable Long teamId,
            @Valid @RequestBody UpdateSettingsRequest request) {
        ParkingSettingsResponse result = settingsService.updateSettings(SCOPE_TYPE, teamId, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
