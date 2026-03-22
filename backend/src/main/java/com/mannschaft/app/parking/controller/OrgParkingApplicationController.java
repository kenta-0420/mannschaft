package com.mannschaft.app.parking.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.parking.ParkingScopeType;
import com.mannschaft.app.parking.dto.ApplicationResponse;
import com.mannschaft.app.parking.dto.CreateApplicationRequest;
import com.mannschaft.app.parking.dto.RejectApplicationRequest;
import com.mannschaft.app.parking.service.ParkingApplicationService;
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
 * 組織区画申請コントローラー（6 EP）。
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/parking/applications")
@Tag(name = "組織区画申請", description = "F09.3 組織区画申請管理")
@RequiredArgsConstructor
public class OrgParkingApplicationController {

    private final ParkingApplicationService applicationService;
    private final ParkingSpaceService spaceService;

    private static final String SCOPE_TYPE = ParkingScopeType.ORGANIZATION.name();

    private Long getCurrentUserId() {
        return 1L;
    }

    @GetMapping
    @Operation(summary = "組織申請一覧")
    public ResponseEntity<PagedResponse<ApplicationResponse>> list(
            @PathVariable Long organizationId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        Page<ApplicationResponse> result = applicationService.list(spaceIds, status,
                PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping
    @Operation(summary = "組織区画申請")
    public ResponseEntity<ApiResponse<ApplicationResponse>> create(
            @PathVariable Long organizationId,
            @Valid @RequestBody CreateApplicationRequest request) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        ApplicationResponse result = applicationService.create(spaceIds, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @PatchMapping("/{id}/approve")
    @Operation(summary = "組織申請承認")
    public ResponseEntity<ApiResponse<ApplicationResponse>> approve(
            @PathVariable Long organizationId, @PathVariable Long id) {
        ApplicationResponse result = applicationService.approve(id);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PatchMapping("/{id}/reject")
    @Operation(summary = "組織申請拒否")
    public ResponseEntity<ApiResponse<ApplicationResponse>> reject(
            @PathVariable Long organizationId, @PathVariable Long id,
            @Valid @RequestBody RejectApplicationRequest request) {
        ApplicationResponse result = applicationService.reject(id, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "組織申請取消")
    public ResponseEntity<Void> cancel(@PathVariable Long organizationId, @PathVariable Long id) {
        applicationService.cancel(id, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/lottery")
    @Operation(summary = "組織抽選実行")
    public ResponseEntity<ApiResponse<List<ApplicationResponse>>> lottery(
            @PathVariable Long organizationId,
            @RequestParam Long spaceId) {
        List<ApplicationResponse> result = applicationService.executeLottery(spaceId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
