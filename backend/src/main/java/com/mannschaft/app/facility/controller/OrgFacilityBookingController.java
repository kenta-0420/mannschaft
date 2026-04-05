package com.mannschaft.app.facility.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.facility.dto.ApproveBookingRequest;
import com.mannschaft.app.facility.dto.BookingDetailResponse;
import com.mannschaft.app.facility.dto.BookingPaymentResponse;
import com.mannschaft.app.facility.dto.BookingResponse;
import com.mannschaft.app.facility.dto.CalendarBookingResponse;
import com.mannschaft.app.facility.dto.CancelBookingRequest;
import com.mannschaft.app.facility.dto.CreateBookingRequest;
import com.mannschaft.app.facility.dto.RejectBookingRequest;
import com.mannschaft.app.facility.dto.UpdateBookingRequest;
import com.mannschaft.app.facility.service.FacilityBookingService;
import com.mannschaft.app.facility.service.FacilityPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 組織施設予約コントローラー。予約CRUD・承認・チェックイン・支払い・カレンダー・PDFを提供する。
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/facilities/bookings")
@Tag(name = "組織施設予約", description = "F09.5 組織施設予約CRUD・承認・チェックイン")
@RequiredArgsConstructor
public class OrgFacilityBookingController {

    private static final String SCOPE_TYPE = "ORGANIZATION";

    private final FacilityBookingService bookingService;
    private final FacilityPaymentService paymentService;

    @GetMapping
    @Operation(summary = "予約一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<BookingResponse>> listBookings(
            @PathVariable Long organizationId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<BookingResponse> result = bookingService.listBookings(SCOPE_TYPE, organizationId, status, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping
    @Operation(summary = "予約作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<BookingResponse>> createBooking(
            @PathVariable Long organizationId,
            @Valid @RequestBody CreateBookingRequest request) {
        BookingResponse response = bookingService.createBooking(SCOPE_TYPE, organizationId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping("/{bookingId}")
    @Operation(summary = "予約詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> getBooking(
            @PathVariable Long organizationId,
            @PathVariable Long bookingId) {
        BookingDetailResponse response = bookingService.getBooking(bookingId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PatchMapping("/{bookingId}")
    @Operation(summary = "予約変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> updateBooking(
            @PathVariable Long organizationId,
            @PathVariable Long bookingId,
            @Valid @RequestBody UpdateBookingRequest request) {
        BookingDetailResponse response = bookingService.updateBooking(bookingId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @DeleteMapping("/{bookingId}")
    @Operation(summary = "予約キャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "キャンセル成功")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> cancelBooking(
            @PathVariable Long organizationId,
            @PathVariable Long bookingId,
            @Valid @RequestBody CancelBookingRequest request) {
        BookingDetailResponse response = bookingService.cancelBooking(bookingId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PatchMapping("/{bookingId}/approve")
    @Operation(summary = "予約承認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承認成功")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> approveBooking(
            @PathVariable Long organizationId,
            @PathVariable Long bookingId,
            @Valid @RequestBody ApproveBookingRequest request) {
        BookingDetailResponse response = bookingService.approveBooking(bookingId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PatchMapping("/{bookingId}/reject")
    @Operation(summary = "予約却下")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "却下成功")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> rejectBooking(
            @PathVariable Long organizationId,
            @PathVariable Long bookingId,
            @Valid @RequestBody RejectBookingRequest request) {
        BookingDetailResponse response = bookingService.rejectBooking(bookingId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PatchMapping("/{bookingId}/check-in")
    @Operation(summary = "チェックイン")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "チェックイン成功")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> checkIn(
            @PathVariable Long organizationId,
            @PathVariable Long bookingId) {
        BookingDetailResponse response = bookingService.checkIn(bookingId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PatchMapping("/{bookingId}/complete")
    @Operation(summary = "利用完了")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "完了成功")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> completeBooking(
            @PathVariable Long organizationId,
            @PathVariable Long bookingId) {
        BookingDetailResponse response = bookingService.completeBooking(bookingId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @GetMapping("/{bookingId}/payment")
    @Operation(summary = "支払い情報取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<BookingPaymentResponse>> getPayment(
            @PathVariable Long organizationId,
            @PathVariable Long bookingId) {
        BookingPaymentResponse response = paymentService.getPayment(bookingId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PatchMapping("/{bookingId}/payment/confirm")
    @Operation(summary = "DIRECT支払い確認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "確認成功")
    public ResponseEntity<ApiResponse<BookingPaymentResponse>> confirmPayment(
            @PathVariable Long organizationId,
            @PathVariable Long bookingId) {
        BookingPaymentResponse response = paymentService.confirmDirectPayment(bookingId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @GetMapping("/calendar")
    @Operation(summary = "カレンダー予約")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CalendarBookingResponse>>> getCalendar(
            @PathVariable Long organizationId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        List<CalendarBookingResponse> responses = bookingService.getCalendarBookings(SCOPE_TYPE, organizationId, dateFrom, dateTo);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    @GetMapping("/{bookingId}/confirmation-pdf")
    @Operation(summary = "確認PDF")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> getConfirmationPdf(
            @PathVariable Long organizationId,
            @PathVariable Long bookingId) {
        BookingDetailResponse response = bookingService.getBookingForPdf(bookingId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
