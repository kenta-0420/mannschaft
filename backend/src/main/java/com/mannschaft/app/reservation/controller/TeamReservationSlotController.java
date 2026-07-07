package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.reservation.dto.CloseSlotRequest;
import com.mannschaft.app.reservation.dto.CreateSlotRequest;
import com.mannschaft.app.reservation.dto.ReservationGridResponse;
import com.mannschaft.app.reservation.dto.ReservationSlotResponse;
import com.mannschaft.app.reservation.dto.UpdateSlotRequest;
import com.mannschaft.app.reservation.service.ReservationGridService;
import com.mannschaft.app.reservation.service.ReservationSlotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.util.UUID;
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
    private final ReservationGridService gridService;

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
     * 複数予約対象の空きグリッドを取得する（機能C・§4.C / F03.4.4 §4.1 拡張）。
     *
     * <p>列＝予約対象（スタッフ・共通。{@code axis=LINE} 時はライン・共通）、各セル＝時間帯の状態。
     * 単日（{@code date}）または日付レンジ（{@code from}/{@code to}・最大7日・{@code days[]} 応答）。
     * <b>{@code date} は F03.4.4 で {@code required=false} 化</b>し、「{@code date} XOR
     * ({@code from},{@code to})」の排他は Service 層で明示検証する（バインド段階の
     * {@code MissingServletRequestParameterException} に任せない — B3。検証位置・文言の一元管理）。</p>
     *
     * <p>認可は {@code @PreAuthorize} では表現せず（会員/公開ユーザーが使うため {@code isScopeAdmin} を付けない）、
     * Service 層（{@link ReservationGridService}）で予約閲覧の view ゲート（会員 or 公開）を適用する。
     * 未認証は認証層で 401、非会員かつ非公開は 403（RESERVATION_021）。</p>
     */
    @GetMapping("/grid")
    @Operation(summary = "空きグリッド（複数予約対象・ライン軸/日付レンジ/メニューフィルター対応）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ReservationGridResponse>> getGrid(
            @PathVariable Long teamId,
            @Parameter(description = "単日指定（from/to とは排他。どちらかの指定が必須）")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "日付レンジ開始日（to と両方指定・最大7日・date とは排他）")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "日付レンジ終了日")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "列軸（既定 STAFF）", schema = @Schema(allowableValues = {"STAFF", "LINE"}))
            @RequestParam(required = false) String axis,
            @Parameter(description = "メニューフィルター（axis=LINE のときのみ有効）")
            @RequestParam(required = false) UUID menuId,
            @RequestParam(required = false) List<Long> staffUserIds) {
        ReservationGridResponse response = gridService.getGrid(
                teamId, SecurityUtils.getCurrentUserId(), date, from, to, axis, menuId, staffUserIds);
        return ResponseEntity.ok(ApiResponse.of(response));
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
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
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
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
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
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
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
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
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
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<ReservationSlotResponse>> reopenSlot(
            @PathVariable Long teamId,
            @PathVariable Long slotId) {
        ReservationSlotResponse response = slotService.reopenSlot(teamId, slotId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
