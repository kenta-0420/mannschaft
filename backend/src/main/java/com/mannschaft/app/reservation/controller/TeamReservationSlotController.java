package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.reservation.dto.CloseSlotRequest;
import com.mannschaft.app.reservation.dto.CreateSlotRequest;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.dto.ReservationSlotResponse;
import com.mannschaft.app.reservation.dto.UpdateSlotRequest;
import com.mannschaft.app.reservation.service.ReservationService;
import com.mannschaft.app.reservation.service.ReservationSlotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 * チーム予約スロットコントローラー。予約時間枠のCRUD・状態管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/reservation-slots")
@Tag(name = "予約スロット管理", description = "F03.4 チーム予約スロットCRUD")
@RequiredArgsConstructor
public class TeamReservationSlotController {

    private final ReservationSlotService slotService;
    private final ReservationService reservationService;


    /**
     * スロット一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "スロット一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ReservationSlotResponse>>> listSlots(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<ReservationSlotResponse> slots = slotService.listSlots(teamId, from, to);
        return ResponseEntity.ok(ApiResponse.of(slots));
    }

    /**
     * 利用可能なスロット一覧を取得する。
     */
    @GetMapping("/available")
    @Operation(summary = "利用可能スロット一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ReservationSlotResponse>>> listAvailableSlots(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<ReservationSlotResponse> slots = slotService.listAvailableSlots(teamId, from, to);
        return ResponseEntity.ok(ApiResponse.of(slots));
    }

    /**
     * スロット詳細を取得する。
     */
    @GetMapping("/{slotId}")
    @Operation(summary = "スロット詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ReservationSlotResponse>> getSlot(
            @PathVariable Long teamId,
            @PathVariable Long slotId) {
        ReservationSlotResponse response = slotService.getSlot(teamId, slotId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スロットを作成する。
     */
    @PostMapping
    @Operation(summary = "スロット作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ReservationSlotResponse>> createSlot(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateSlotRequest request) {
        ReservationSlotResponse response = slotService.createSlot(teamId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * スロットを更新する。
     */
    @PatchMapping("/{slotId}")
    @Operation(summary = "スロット更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ReservationSlotResponse>> updateSlot(
            @PathVariable Long teamId,
            @PathVariable Long slotId,
            @Valid @RequestBody UpdateSlotRequest request) {
        ReservationSlotResponse response = slotService.updateSlot(teamId, slotId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スロットを削除する。
     */
    @DeleteMapping("/{slotId}")
    @Operation(summary = "スロット削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteSlot(
            @PathVariable Long teamId,
            @PathVariable Long slotId) {
        slotService.deleteSlot(teamId, slotId);
        return ResponseEntity.noContent().build();
    }

    /**
     * スロットをクローズする。
     */
    @PostMapping("/{slotId}/close")
    @Operation(summary = "スロットクローズ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "クローズ成功")
    public ResponseEntity<ApiResponse<ReservationSlotResponse>> closeSlot(
            @PathVariable Long teamId,
            @PathVariable Long slotId,
            @Valid @RequestBody CloseSlotRequest request) {
        ReservationSlotResponse response = slotService.closeSlot(teamId, slotId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スロットを再開する。
     */
    @PostMapping("/{slotId}/reopen")
    @Operation(summary = "スロット再開")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "再開成功")
    public ResponseEntity<ApiResponse<ReservationSlotResponse>> reopenSlot(
            @PathVariable Long teamId,
            @PathVariable Long slotId) {
        ReservationSlotResponse response = slotService.reopenSlot(teamId, slotId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
