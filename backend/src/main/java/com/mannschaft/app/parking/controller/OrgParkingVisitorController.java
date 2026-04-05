package com.mannschaft.app.parking.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.parking.ParkingScopeType;
import com.mannschaft.app.parking.dto.*;
import com.mannschaft.app.parking.service.ParkingSpaceService;
import com.mannschaft.app.parking.service.ParkingVisitorRecurringService;
import com.mannschaft.app.parking.service.ParkingVisitorReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 組織来場者予約+定期テンプレートコントローラー（9+4=13 EP）。
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/parking")
@Tag(name = "組織来場者予約", description = "F09.3 組織来場者予約・定期テンプレート管理")
@RequiredArgsConstructor
public class OrgParkingVisitorController {

    private final ParkingVisitorReservationService reservationService;
    private final ParkingVisitorRecurringService recurringService;
    private final ParkingSpaceService spaceService;

    private static final String SCOPE_TYPE = ParkingScopeType.ORGANIZATION.name();

    @GetMapping("/visitor-reservations")
    @Operation(summary = "組織来場者予約一覧")
    public ResponseEntity<PagedResponse<VisitorReservationResponse>> listReservations(
            @PathVariable Long organizationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        Page<VisitorReservationResponse> result = reservationService.list(spaceIds, date,
                PageRequest.of(page, Math.min(size, 100), Sort.by("reservedDate", "timeFrom")));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping("/visitor-reservations")
    @Operation(summary = "組織来場者予約作成")
    public ResponseEntity<ApiResponse<VisitorReservationResponse>> createReservation(
            @PathVariable Long organizationId,
            @Valid @RequestBody CreateVisitorReservationRequest request) {
        VisitorReservationResponse result = reservationService.create(SCOPE_TYPE, organizationId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @GetMapping("/visitor-reservations/availability")
    @Operation(summary = "組織来場者用区画空き状況")
    public ResponseEntity<ApiResponse<AvailabilityResponse>> getAvailability(
            @PathVariable Long organizationId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        AvailabilityResponse result = reservationService.getAvailability(SCOPE_TYPE, organizationId, date);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping("/visitor-reservations/{id}")
    @Operation(summary = "組織来場者予約詳細")
    public ResponseEntity<ApiResponse<VisitorReservationResponse>> getReservation(
            @PathVariable Long organizationId, @PathVariable Long id) {
        VisitorReservationResponse result = reservationService.getDetail(id);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PatchMapping("/visitor-reservations/{id}/approve")
    @Operation(summary = "組織来場者予約承認")
    public ResponseEntity<ApiResponse<VisitorReservationResponse>> approveReservation(
            @PathVariable Long organizationId, @PathVariable Long id) {
        VisitorReservationResponse result = reservationService.approve(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PatchMapping("/visitor-reservations/{id}/reject")
    @Operation(summary = "組織来場者予約拒否")
    public ResponseEntity<ApiResponse<VisitorReservationResponse>> rejectReservation(
            @PathVariable Long organizationId, @PathVariable Long id,
            @RequestParam(required = false) String adminComment) {
        VisitorReservationResponse result = reservationService.reject(id, SecurityUtils.getCurrentUserId(), adminComment);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PatchMapping("/visitor-reservations/{id}/check-in")
    @Operation(summary = "組織来場者チェックイン")
    public ResponseEntity<ApiResponse<VisitorReservationResponse>> checkIn(
            @PathVariable Long organizationId, @PathVariable Long id) {
        VisitorReservationResponse result = reservationService.checkIn(id);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PatchMapping("/visitor-reservations/{id}/complete")
    @Operation(summary = "組織来場者予約完了")
    public ResponseEntity<ApiResponse<VisitorReservationResponse>> complete(
            @PathVariable Long organizationId, @PathVariable Long id) {
        VisitorReservationResponse result = reservationService.complete(id);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @DeleteMapping("/visitor-reservations/{id}")
    @Operation(summary = "組織来場者予約キャンセル")
    public ResponseEntity<Void> cancelReservation(@PathVariable Long organizationId, @PathVariable Long id) {
        reservationService.cancel(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/visitor-recurring")
    @Operation(summary = "組織定期予約テンプレート一覧")
    public ResponseEntity<ApiResponse<List<VisitorRecurringResponse>>> listRecurring(@PathVariable Long organizationId) {
        List<VisitorRecurringResponse> result = recurringService.list(SecurityUtils.getCurrentUserId(), SCOPE_TYPE, organizationId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PostMapping("/visitor-recurring")
    @Operation(summary = "組織定期予約テンプレート作成")
    public ResponseEntity<ApiResponse<VisitorRecurringResponse>> createRecurring(
            @PathVariable Long organizationId,
            @Valid @RequestBody CreateVisitorRecurringRequest request) {
        VisitorRecurringResponse result = recurringService.create(SecurityUtils.getCurrentUserId(), SCOPE_TYPE, organizationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @PutMapping("/visitor-recurring/{id}")
    @Operation(summary = "組織定期予約テンプレート更新")
    public ResponseEntity<ApiResponse<VisitorRecurringResponse>> updateRecurring(
            @PathVariable Long organizationId, @PathVariable Long id,
            @Valid @RequestBody UpdateVisitorRecurringRequest request) {
        VisitorRecurringResponse result = recurringService.update(SecurityUtils.getCurrentUserId(), SCOPE_TYPE, organizationId, id, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @DeleteMapping("/visitor-recurring/{id}")
    @Operation(summary = "組織定期予約テンプレート削除")
    public ResponseEntity<Void> deleteRecurring(@PathVariable Long organizationId, @PathVariable Long id) {
        recurringService.delete(SecurityUtils.getCurrentUserId(), SCOPE_TYPE, organizationId, id);
        return ResponseEntity.noContent().build();
    }
}
