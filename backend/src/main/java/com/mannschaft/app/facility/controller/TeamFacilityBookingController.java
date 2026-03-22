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
import com.mannschaft.app.common.pdf.PdfFileNameBuilder;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.pdf.PdfResponseHelper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * チーム施設予約コントローラー。予約CRUD・承認・チェックイン・支払い・カレンダー・PDFを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/facilities/bookings")
@Tag(name = "チーム施設予約", description = "F09.5 チーム施設予約CRUD・承認・チェックイン")
@RequiredArgsConstructor
public class TeamFacilityBookingController {

    private static final String SCOPE_TYPE = "TEAM";

    private final FacilityBookingService bookingService;
    private final FacilityPaymentService paymentService;
    private final PdfGeneratorService pdfGeneratorService;

    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 予約一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "予約一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<BookingResponse>> listBookings(
            @PathVariable Long teamId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<BookingResponse> result = bookingService.listBookings(SCOPE_TYPE, teamId, status, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 予約を作成する。
     */
    @PostMapping
    @Operation(summary = "予約作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<BookingResponse>> createBooking(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateBookingRequest request) {
        BookingResponse response = bookingService.createBooking(SCOPE_TYPE, teamId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 予約詳細を取得する。
     */
    @GetMapping("/{bookingId}")
    @Operation(summary = "予約詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> getBooking(
            @PathVariable Long teamId,
            @PathVariable Long bookingId) {
        BookingDetailResponse response = bookingService.getBooking(bookingId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 予約を更新する。
     */
    @PatchMapping("/{bookingId}")
    @Operation(summary = "予約変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> updateBooking(
            @PathVariable Long teamId,
            @PathVariable Long bookingId,
            @Valid @RequestBody UpdateBookingRequest request) {
        BookingDetailResponse response = bookingService.updateBooking(bookingId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 予約をキャンセルする。
     */
    @DeleteMapping("/{bookingId}")
    @Operation(summary = "予約キャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "キャンセル成功")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> cancelBooking(
            @PathVariable Long teamId,
            @PathVariable Long bookingId,
            @Valid @RequestBody CancelBookingRequest request) {
        BookingDetailResponse response = bookingService.cancelBooking(bookingId, getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 予約を承認する。
     */
    @PatchMapping("/{bookingId}/approve")
    @Operation(summary = "予約承認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承認成功")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> approveBooking(
            @PathVariable Long teamId,
            @PathVariable Long bookingId,
            @Valid @RequestBody ApproveBookingRequest request) {
        BookingDetailResponse response = bookingService.approveBooking(bookingId, getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 予約を却下する。
     */
    @PatchMapping("/{bookingId}/reject")
    @Operation(summary = "予約却下")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "却下成功")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> rejectBooking(
            @PathVariable Long teamId,
            @PathVariable Long bookingId,
            @Valid @RequestBody RejectBookingRequest request) {
        BookingDetailResponse response = bookingService.rejectBooking(bookingId, getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チェックインする。
     */
    @PatchMapping("/{bookingId}/check-in")
    @Operation(summary = "チェックイン")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "チェックイン成功")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> checkIn(
            @PathVariable Long teamId,
            @PathVariable Long bookingId) {
        BookingDetailResponse response = bookingService.checkIn(bookingId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 利用完了する。
     */
    @PatchMapping("/{bookingId}/complete")
    @Operation(summary = "利用完了")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "完了成功")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> completeBooking(
            @PathVariable Long teamId,
            @PathVariable Long bookingId) {
        BookingDetailResponse response = bookingService.completeBooking(bookingId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 支払い情報を取得する。
     */
    @GetMapping("/{bookingId}/payment")
    @Operation(summary = "支払い情報取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<BookingPaymentResponse>> getPayment(
            @PathVariable Long teamId,
            @PathVariable Long bookingId) {
        BookingPaymentResponse response = paymentService.getPayment(bookingId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * DIRECT支払いを確認する。
     */
    @PatchMapping("/{bookingId}/payment/confirm")
    @Operation(summary = "DIRECT支払い確認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "確認成功")
    public ResponseEntity<ApiResponse<BookingPaymentResponse>> confirmPayment(
            @PathVariable Long teamId,
            @PathVariable Long bookingId) {
        BookingPaymentResponse response = paymentService.confirmDirectPayment(bookingId, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * カレンダー予約を取得する。
     */
    @GetMapping("/calendar")
    @Operation(summary = "カレンダー予約")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CalendarBookingResponse>>> getCalendar(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        List<CalendarBookingResponse> responses = bookingService.getCalendarBookings(SCOPE_TYPE, teamId, dateFrom, dateTo);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 確認用PDFを取得する。
     */
    @GetMapping("/{bookingId}/confirmation-pdf")
    @Operation(summary = "確認PDF")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "PDF生成成功",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/pdf"))
    public ResponseEntity<byte[]> getConfirmationPdf(
            @PathVariable Long teamId,
            @PathVariable Long bookingId) {
        BookingDetailResponse booking = bookingService.getBookingForPdf(bookingId);

        Map<String, Object> variables = new HashMap<>();
        variables.put("booking", booking);
        variables.put("title", "施設予約確認書");

        byte[] pdfBytes = pdfGeneratorService.generateFromTemplate("pdf/facility-booking", variables);

        String fileName = PdfFileNameBuilder.of("施設予約")
                .date(booking.getBookingDate())
                .identifier(booking.getFacilityName() + "_予約" + booking.getId())
                .build();

        return PdfResponseHelper.toResponse(pdfBytes, fileName);
    }
}
