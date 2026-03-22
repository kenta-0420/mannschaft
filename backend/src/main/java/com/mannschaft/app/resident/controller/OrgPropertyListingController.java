package com.mannschaft.app.resident.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.resident.dto.CreateInquiryRequest;
import com.mannschaft.app.resident.dto.CreatePropertyListingRequest;
import com.mannschaft.app.resident.dto.InquiryResponse;
import com.mannschaft.app.resident.dto.PropertyListingResponse;
import com.mannschaft.app.resident.dto.UpdatePropertyListingRequest;
import com.mannschaft.app.resident.service.PropertyListingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 組織物件掲示板コントローラー。
 */
@RestController
@Tag(name = "物件掲示板（組織）", description = "F09.1 組織物件掲示板CRUD")
@RequiredArgsConstructor
public class OrgPropertyListingController {

    private final PropertyListingService listingService;

    private Long getCurrentUserId() {
        return 1L;
    }

    @GetMapping("/api/v1/organizations/{orgId}/property-listings")
    @Operation(summary = "物件一覧")
    public ResponseEntity<PagedResponse<PropertyListingResponse>> list(
            @PathVariable Long orgId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String listingType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PropertyListingResponse> result = listingService.listByOrganization(orgId, status, listingType, PageRequest.of(page, Math.min(size, 50)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping("/api/v1/organizations/{orgId}/property-listings")
    @Operation(summary = "物件掲示作成")
    public ResponseEntity<ApiResponse<PropertyListingResponse>> create(
            @PathVariable Long orgId,
            @Valid @RequestBody CreatePropertyListingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.of(listingService.createForOrganization(orgId, getCurrentUserId(), request)));
    }

    @GetMapping("/api/v1/organizations/{orgId}/property-listings/{id}")
    @Operation(summary = "物件詳細")
    public ResponseEntity<ApiResponse<PropertyListingResponse>> get(
            @PathVariable Long orgId, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(listingService.getByOrganization(orgId, id)));
    }

    @PutMapping("/api/v1/organizations/{orgId}/property-listings/{id}")
    @Operation(summary = "物件更新")
    public ResponseEntity<ApiResponse<PropertyListingResponse>> update(
            @PathVariable Long orgId, @PathVariable Long id,
            @Valid @RequestBody UpdatePropertyListingRequest request) {
        return ResponseEntity.ok(ApiResponse.of(listingService.updateForOrganization(orgId, id, request)));
    }

    @DeleteMapping("/api/v1/organizations/{orgId}/property-listings/{id}")
    @Operation(summary = "物件削除")
    public ResponseEntity<Void> delete(@PathVariable Long orgId, @PathVariable Long id) {
        listingService.deleteForOrganization(orgId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/organizations/{orgId}/property-listings/{id}/inquiries")
    @Operation(summary = "問い合わせ")
    public ResponseEntity<ApiResponse<InquiryResponse>> createInquiry(
            @PathVariable Long orgId, @PathVariable Long id,
            @RequestBody CreateInquiryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.of(listingService.createInquiry(id, getCurrentUserId(), request)));
    }

    @GetMapping("/api/v1/organizations/{orgId}/property-listings/{id}/inquiries")
    @Operation(summary = "問い合わせ一覧")
    public ResponseEntity<ApiResponse<List<InquiryResponse>>> listInquiries(
            @PathVariable Long orgId, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(listingService.listInquiries(id)));
    }
}
