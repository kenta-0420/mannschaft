package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.reservation.dto.CancelReservationRequest;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.service.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 予約共通コントローラー。ログインユーザー自身の予約管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/reservations")
@Tag(name = "マイ予約", description = "F03.4 ログインユーザーの予約管理")
@RequiredArgsConstructor
public class ReservationCommonController {

    private final ReservationService reservationService;


    /**
     * 自分の予約一覧を取得する。
     */
    @GetMapping("/my")
    @Operation(summary = "マイ予約一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ReservationResponse>>> listMyReservations() {
        List<ReservationResponse> reservations = reservationService.listMyReservations(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(reservations));
    }

    /**
     * 直近の予約一覧を取得する。
     */
    @GetMapping("/upcoming")
    @Operation(summary = "直近の予約一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ReservationResponse>>> listUpcomingReservations() {
        List<ReservationResponse> reservations = reservationService.listUpcomingReservations(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(reservations));
    }

    /**
     * 自分の予約をキャンセルする。
     */
    @PostMapping("/{reservationId}/cancel")
    @Operation(summary = "マイ予約キャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "キャンセル成功")
    public ResponseEntity<ApiResponse<ReservationResponse>> cancelMyReservation(
            @PathVariable Long reservationId,
            @Valid @RequestBody CancelReservationRequest request) {
        ReservationResponse response = reservationService.cancelByUser(SecurityUtils.getCurrentUserId(), reservationId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
