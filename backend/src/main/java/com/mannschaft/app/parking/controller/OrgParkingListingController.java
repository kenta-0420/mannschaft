package com.mannschaft.app.parking.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.parking.ParkingScopeType;
import com.mannschaft.app.parking.dto.*;
import com.mannschaft.app.parking.service.ParkingListingService;
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
 * 組織譲渡希望コントローラー（7 EP）。
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/parking/listings")
@Tag(name = "組織譲渡希望", description = "F09.3 組織譲渡希望管理")
@RequiredArgsConstructor
public class OrgParkingListingController {

    private final ParkingListingService listingService;
    private final ParkingSpaceService spaceService;

    private static final String SCOPE_TYPE = ParkingScopeType.ORGANIZATION.name();

    private Long getCurrentUserId() {
        return 1L;
    }

    @GetMapping
    @Operation(summary = "組織譲渡希望一覧")
    public ResponseEntity<PagedResponse<ListingResponse>> list(
            @PathVariable Long organizationId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        Page<ListingResponse> result = listingService.list(spaceIds, status,
                PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping
    @Operation(summary = "組織譲渡希望作成")
    public ResponseEntity<ApiResponse<ListingResponse>> create(
            @PathVariable Long organizationId,
            @Valid @RequestBody CreateListingRequest request) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        ListingResponse result = listingService.create(spaceIds, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @GetMapping("/{id}")
    @Operation(summary = "組織譲渡希望詳細")
    public ResponseEntity<ApiResponse<ListingDetailResponse>> getDetail(
            @PathVariable Long organizationId, @PathVariable Long id) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        ListingDetailResponse result = listingService.getDetail(spaceIds, id);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PutMapping("/{id}")
    @Operation(summary = "組織譲渡希望更新")
    public ResponseEntity<ApiResponse<ListingResponse>> update(
            @PathVariable Long organizationId, @PathVariable Long id,
            @Valid @RequestBody UpdateListingRequest request) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        ListingResponse result = listingService.update(spaceIds, id, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "組織譲渡希望削除")
    public ResponseEntity<Void> delete(@PathVariable Long organizationId, @PathVariable Long id) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        listingService.delete(spaceIds, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/apply")
    @Operation(summary = "組織譲渡希望申込")
    public ResponseEntity<ApiResponse<ApplicationResponse>> apply(
            @PathVariable Long organizationId, @PathVariable Long id,
            @Valid @RequestBody ListingApplyRequest request) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        ApplicationResponse result = listingService.apply(spaceIds, id, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @PatchMapping("/{id}/transfer")
    @Operation(summary = "組織譲渡確定")
    public ResponseEntity<ApiResponse<ListingDetailResponse>> transfer(
            @PathVariable Long organizationId, @PathVariable Long id) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        ListingDetailResponse result = listingService.transfer(spaceIds, id);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
