package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.reservation.dto.AdminNoteRequest;
import com.mannschaft.app.reservation.dto.CancelReservationRequest;
import com.mannschaft.app.reservation.dto.CreateReminderRequest;
import com.mannschaft.app.reservation.dto.CreateReservationRequest;
import com.mannschaft.app.reservation.dto.ReminderResponse;
import com.mannschaft.app.reservation.dto.RescheduleRequest;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.dto.ReservationStatsResponse;
import com.mannschaft.app.reservation.service.ReservationReminderService;
import com.mannschaft.app.reservation.service.ReservationService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * チーム予約コントローラー。予約のCRUD・ステータス遷移・統計APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/reservations")
@Tag(name = "予約管理", description = "F03.4 チーム予約CRUD・ステータス管理")
@RequiredArgsConstructor
public class TeamReservationController {

    private final ReservationService reservationService;
    private final ReservationReminderService reminderService;


    /**
     * チームの予約一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "チーム予約一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ReservationResponse>> listReservations(
            @PathVariable Long teamId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ReservationResponse> result = reservationService.listTeamReservations(
                teamId, status, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 予約詳細を取得する。
     */
    @GetMapping("/{reservationId}")
    @Operation(summary = "予約詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ReservationResponse>> getReservation(
            @PathVariable Long teamId,
            @PathVariable Long reservationId) {
        ReservationResponse response = reservationService.getReservation(teamId, reservationId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 予約を作成する。
     */
    @PostMapping
    @Operation(summary = "予約作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ReservationResponse>> createReservation(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateReservationRequest request) {
        ReservationResponse response = reservationService.createReservation(teamId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 予約を確定する。
     */
    @PostMapping("/{reservationId}/confirm")
    @Operation(summary = "予約確定")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "確定成功")
    public ResponseEntity<ApiResponse<ReservationResponse>> confirmReservation(
            @PathVariable Long teamId,
            @PathVariable Long reservationId) {
        ReservationResponse response = reservationService.confirmReservation(teamId, reservationId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 予約をキャンセルする（管理者）。
     */
    @PostMapping("/{reservationId}/cancel")
    @Operation(summary = "予約キャンセル（管理者）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "キャンセル成功")
    public ResponseEntity<ApiResponse<ReservationResponse>> cancelReservation(
            @PathVariable Long teamId,
            @PathVariable Long reservationId,
            @Valid @RequestBody CancelReservationRequest request) {
        ReservationResponse response = reservationService.cancelByAdmin(teamId, reservationId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 予約を完了する。
     */
    @PostMapping("/{reservationId}/complete")
    @Operation(summary = "予約完了")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "完了成功")
    public ResponseEntity<ApiResponse<ReservationResponse>> completeReservation(
            @PathVariable Long teamId,
            @PathVariable Long reservationId) {
        ReservationResponse response = reservationService.completeReservation(teamId, reservationId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ノーショーとしてマークする。
     */
    @PostMapping("/{reservationId}/no-show")
    @Operation(summary = "ノーショー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "マーク成功")
    public ResponseEntity<ApiResponse<ReservationResponse>> markNoShow(
            @PathVariable Long teamId,
            @PathVariable Long reservationId) {
        ReservationResponse response = reservationService.markNoShow(teamId, reservationId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 予約をリスケジュールする。
     */
    @PostMapping("/{reservationId}/reschedule")
    @Operation(summary = "予約リスケジュール")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "リスケ成功")
    public ResponseEntity<ApiResponse<ReservationResponse>> rescheduleReservation(
            @PathVariable Long teamId,
            @PathVariable Long reservationId,
            @Valid @RequestBody RescheduleRequest request) {
        ReservationResponse response = reservationService.rescheduleReservation(teamId, reservationId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 管理者メモを更新する。
     */
    @PatchMapping("/{reservationId}/admin-note")
    @Operation(summary = "管理者メモ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ReservationResponse>> updateAdminNote(
            @PathVariable Long teamId,
            @PathVariable Long reservationId,
            @Valid @RequestBody AdminNoteRequest request) {
        ReservationResponse response = reservationService.updateAdminNote(teamId, reservationId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チームの予約統計を取得する。
     */
    @GetMapping("/stats")
    @Operation(summary = "予約統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ReservationStatsResponse>> getStats(
            @PathVariable Long teamId) {
        ReservationStatsResponse response = reservationService.getStats(teamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 予約のリマインダー一覧を取得する。
     */
    @GetMapping("/{reservationId}/reminders")
    @Operation(summary = "リマインダー一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ReminderResponse>>> listReminders(
            @PathVariable Long teamId,
            @PathVariable Long reservationId) {
        List<ReminderResponse> reminders = reminderService.listReminders(reservationId);
        return ResponseEntity.ok(ApiResponse.of(reminders));
    }

    /**
     * リマインダーを作成する。
     */
    @PostMapping("/{reservationId}/reminders")
    @Operation(summary = "リマインダー作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ReminderResponse>> createReminder(
            @PathVariable Long teamId,
            @PathVariable Long reservationId,
            @Valid @RequestBody CreateReminderRequest request) {
        ReminderResponse response = reminderService.createReminder(reservationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
