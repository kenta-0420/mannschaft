package com.mannschaft.app.parking.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.parking.ParkingScopeType;
import com.mannschaft.app.parking.dto.*;
import com.mannschaft.app.parking.service.ParkingSpaceService;
import com.mannschaft.app.parking.service.ParkingSubleaseService;
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
import com.mannschaft.app.common.SecurityUtils;

/**
 * 組織サブリースコントローラー（10 EP）。
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/parking/subleases")
@Tag(name = "組織サブリース", description = "F09.3 組織サブリース管理")
@RequiredArgsConstructor
public class OrgParkingSubleaseController {

    private final ParkingSubleaseService subleaseService;
    private final ParkingSpaceService spaceService;

    private static final String SCOPE_TYPE = ParkingScopeType.ORGANIZATION.name();

    @GetMapping
    @Operation(summary = "組織サブリース一覧")
    public ResponseEntity<PagedResponse<SubleaseResponse>> list(
            @PathVariable Long organizationId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        Page<SubleaseResponse> result = subleaseService.list(spaceIds, status,
                PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping
    @Operation(summary = "組織サブリース作成")
    public ResponseEntity<ApiResponse<SubleaseResponse>> create(
            @PathVariable Long organizationId,
            @Valid @RequestBody CreateSubleaseRequest request) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        SubleaseResponse result = subleaseService.create(spaceIds, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @GetMapping("/{id}")
    @Operation(summary = "組織サブリース詳細")
    public ResponseEntity<ApiResponse<SubleaseDetailResponse>> getDetail(
            @PathVariable Long organizationId, @PathVariable Long id) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        SubleaseDetailResponse result = subleaseService.getDetail(spaceIds, id);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PutMapping("/{id}")
    @Operation(summary = "組織サブリース更新")
    public ResponseEntity<ApiResponse<SubleaseResponse>> update(
            @PathVariable Long organizationId, @PathVariable Long id,
            @Valid @RequestBody UpdateSubleaseRequest request) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        SubleaseResponse result = subleaseService.update(spaceIds, id, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "組織サブリース削除")
    public ResponseEntity<Void> delete(@PathVariable Long organizationId, @PathVariable Long id) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        subleaseService.delete(spaceIds, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/apply")
    @Operation(summary = "組織サブリース申込")
    public ResponseEntity<ApiResponse<SubleaseApplicationResponse>> apply(
            @PathVariable Long organizationId, @PathVariable Long id,
            @Valid @RequestBody ApplySubleaseRequest request) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        SubleaseApplicationResponse result = subleaseService.apply(spaceIds, id, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @PatchMapping("/{id}/approve")
    @Operation(summary = "組織サブリース承認")
    public ResponseEntity<ApiResponse<SubleaseDetailResponse>> approve(
            @PathVariable Long organizationId, @PathVariable Long id,
            @Valid @RequestBody ApproveSubleaseRequest request) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        SubleaseDetailResponse result = subleaseService.approve(spaceIds, id, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PatchMapping("/{id}/terminate")
    @Operation(summary = "組織サブリース終了")
    public ResponseEntity<ApiResponse<SubleaseDetailResponse>> terminate(
            @PathVariable Long organizationId, @PathVariable Long id) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        SubleaseDetailResponse result = subleaseService.terminate(spaceIds, id);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping("/{id}/payments")
    @Operation(summary = "組織サブリース決済一覧")
    public ResponseEntity<PagedResponse<SubleasePaymentResponse>> getPayments(
            @PathVariable Long organizationId, @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        Page<SubleasePaymentResponse> result = subleaseService.getPayments(spaceIds, id,
                PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }
}
