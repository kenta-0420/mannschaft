package com.mannschaft.app.shift.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shift.dto.BulkCreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.CreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.ShiftSlotResponse;
import com.mannschaft.app.shift.dto.SlotAssignmentPatchRequest;
import com.mannschaft.app.shift.dto.UpdateShiftSlotRequest;
import com.mannschaft.app.shift.service.ShiftSlotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * シフト枠コントローラー。シフト枠のCRUD・一括操作APIを提供する。
 *
 * <p><b>認可（認可根治 Wave6）:</b> 本コントローラーは全エンドポイントが無認可で、
 * 認証さえ通れば任意チームのシフト枠を閲覧・改変・割当できる状態だった。
 * scope は<b>パス変数でなくスケジュール実体由来</b>（{@code scheduleId} / {@code slotId} から
 * 解決した teamId）のため {@code @accessGuard} の SpEL では表現できない。よって宣言は
 * {@code isAuthenticated()} に留め、真の per-scope 認可は {@link ShiftSlotService} 内で
 * 強制する（参照=メンバー かつ SUPPORTER 不可 / 更新・割当=ADMIN 以上）。</p>
 */
@RestController
@RequestMapping("/api/v1/shifts")
@Tag(name = "シフト枠管理", description = "F03.5 シフト枠のCRUD・一括操作")
@RequiredArgsConstructor
public class ShiftSlotController {

    private final ShiftSlotService slotService;

    /**
     * スケジュールのシフト枠一覧を取得する。
     */
    @GetMapping("/schedules/{scheduleId}/slots")
    @Operation(summary = "シフト枠一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<ShiftSlotResponse>>> listSlots(
            @PathVariable Long scheduleId) {
        List<ShiftSlotResponse> responses = slotService.listSlots(scheduleId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * シフト枠を作成する。
     */
    @PostMapping("/schedules/{scheduleId}/slots")
    @Operation(summary = "シフト枠作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ShiftSlotResponse>> createSlot(
            @PathVariable Long scheduleId,
            @Valid @RequestBody CreateShiftSlotRequest request) {
        ShiftSlotResponse response = slotService.createSlot(
                scheduleId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * シフト枠を一括作成する。
     */
    @PostMapping("/schedules/{scheduleId}/slots/bulk")
    @Operation(summary = "シフト枠一括作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "一括作成成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<ShiftSlotResponse>>> bulkCreateSlots(
            @PathVariable Long scheduleId,
            @Valid @RequestBody BulkCreateShiftSlotRequest request) {
        List<ShiftSlotResponse> responses = slotService.bulkCreateSlots(
                scheduleId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(responses));
    }

    /**
     * シフト枠を更新する。
     */
    @PatchMapping("/slots/{slotId}")
    @Operation(summary = "シフト枠更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ShiftSlotResponse>> updateSlot(
            @PathVariable Long slotId,
            @Valid @RequestBody UpdateShiftSlotRequest request) {
        ShiftSlotResponse response = slotService.updateSlot(
                slotId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * シフト枠を削除する。
     */
    @DeleteMapping("/slots/{slotId}")
    @Operation(summary = "シフト枠削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteSlot(
            @PathVariable Long slotId) {
        slotService.deleteSlot(slotId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * スロットの割当ユーザーを差分更新する。
     * 楽観ロック競合時は 409 Conflict を返す。
     */
    @PatchMapping("/slots/{slotId}/assignments")
    @Operation(summary = "スロット差分割当更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "競合（楽観ロック）")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ShiftSlotResponse>> patchSlotAssignments(
            @PathVariable Long slotId,
            @Valid @RequestBody SlotAssignmentPatchRequest request) {
        ShiftSlotResponse response = slotService.patchSlotAssignments(
                slotId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
