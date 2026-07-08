package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reservation.dto.WaitlistCountResponse;
import com.mannschaft.app.reservation.dto.WaitlistEntryResponse;
import com.mannschaft.app.reservation.service.ReservationWaitlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * キャンセル待ち（waitlist）コントローラー（F03.4.5 §6.1）。
 *
 * <p>枠に紐づくキャンセル待ちの登録・本人取消・枠別件数（ADMIN）を提供する。
 * 登録・取消は会員/公開の view ゲート（Service 層 {@code ReservationViewAccessGuard}）で認可し、
 * 件数は ADMIN self-gate（{@code isScopeAdmin}）で認可する。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/reservation-slots/{slotId}/waitlist")
@Tag(name = "予約管理", description = "F03.4.5 キャンセル待ち")
@RequiredArgsConstructor
public class ReservationWaitlistController {

    private final ReservationWaitlistService waitlistService;

    /**
     * 満席枠へキャンセル待ちを登録する（会員/公開）。
     */
    @PostMapping
    @Operation(summary = "キャンセル待ち登録")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "登録成功")
    public ResponseEntity<ApiResponse<WaitlistEntryResponse>> register(
            @PathVariable Long teamId,
            @PathVariable Long slotId) {
        WaitlistEntryResponse response =
                waitlistService.register(teamId, slotId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 自分の WAITING を取消する（本人）。
     *
     * <p>解決は (slot, 本人) で行うため他人のエントリは掴めない（IDOR 秘匿）。
     * 自分の WAITING が無ければ 404。</p>
     */
    @DeleteMapping
    @Operation(summary = "キャンセル待ち取消（本人）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "取消成功")
    public ResponseEntity<Void> cancel(
            @PathVariable Long teamId,
            @PathVariable Long slotId) {
        waitlistService.cancelOwn(teamId, slotId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 枠別のキャンセル待ち件数を取得する（ADMIN 専用）。
     *
     * <p>認可順序（§6.1）: {@code isScopeAdmin}（teamId）が先＝非 ADMIN は 403 →
     * Service 層で slot を {@code findByIdAndTeamId} 解決し他チームは 404 秘匿。</p>
     */
    @GetMapping("/count")
    @Operation(summary = "キャンセル待ち件数（ADMIN）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<WaitlistCountResponse>> count(
            @PathVariable Long teamId,
            @PathVariable Long slotId) {
        WaitlistCountResponse response = waitlistService.countWaiting(teamId, slotId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
