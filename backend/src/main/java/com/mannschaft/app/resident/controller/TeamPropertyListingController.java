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
 * チーム物件掲示板コントローラー。
 */
@RestController
@Tag(name = "物件掲示板（チーム）", description = "F09.1 チーム物件掲示板CRUD")
@RequiredArgsConstructor
public class TeamPropertyListingController {

    private final PropertyListingService listingService;

    private Long getCurrentUserId() {
        return 1L;
    }

    @GetMapping("/api/v1/teams/{teamId}/property-listings")
    @Operation(summary = "物件一覧")
    public ResponseEntity<PagedResponse<PropertyListingResponse>> list(
            @PathVariable Long teamId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String listingType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PropertyListingResponse> result = listingService.listByTeam(teamId, status, listingType, PageRequest.of(page, Math.min(size, 50)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping("/api/v1/teams/{teamId}/property-listings")
    @Operation(summary = "物件掲示作成")
    public ResponseEntity<ApiResponse<PropertyListingResponse>> create(
            @PathVariable Long teamId,
            @Valid @RequestBody CreatePropertyListingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.of(listingService.createForTeam(teamId, getCurrentUserId(), request)));
    }

    @GetMapping("/api/v1/teams/{teamId}/property-listings/{id}")
    @Operation(summary = "物件詳細")
    public ResponseEntity<ApiResponse<PropertyListingResponse>> get(
            @PathVariable Long teamId, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(listingService.getByTeam(teamId, id)));
    }

    @PutMapping("/api/v1/teams/{teamId}/property-listings/{id}")
    @Operation(summary = "物件更新")
    public ResponseEntity<ApiResponse<PropertyListingResponse>> update(
            @PathVariable Long teamId, @PathVariable Long id,
            @Valid @RequestBody UpdatePropertyListingRequest request) {
        return ResponseEntity.ok(ApiResponse.of(listingService.updateForTeam(teamId, id, request)));
    }

    @DeleteMapping("/api/v1/teams/{teamId}/property-listings/{id}")
    @Operation(summary = "物件削除")
    public ResponseEntity<Void> delete(@PathVariable Long teamId, @PathVariable Long id) {
        listingService.deleteForTeam(teamId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/teams/{teamId}/property-listings/{id}/inquiries")
    @Operation(summary = "問い合わせ")
    public ResponseEntity<ApiResponse<InquiryResponse>> createInquiry(
            @PathVariable Long teamId, @PathVariable Long id,
            @RequestBody CreateInquiryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.of(listingService.createInquiry(id, getCurrentUserId(), request)));
    }

    @GetMapping("/api/v1/teams/{teamId}/property-listings/{id}/inquiries")
    @Operation(summary = "問い合わせ一覧")
    public ResponseEntity<ApiResponse<List<InquiryResponse>>> listInquiries(
            @PathVariable Long teamId, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(listingService.listInquiries(id)));
    }
}
