package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reservation.dto.CreateReservationMenuRequest;
import com.mannschaft.app.reservation.dto.ReservationMenuDeleteResponse;
import com.mannschaft.app.reservation.dto.ReservationMenuResponse;
import com.mannschaft.app.reservation.dto.UpdateReservationMenuRequest;
import com.mannschaft.app.reservation.service.ReservationMenuService;
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
import java.util.UUID;

/**
 * 予約メニューコントローラー（F03.4.1 機能E）。
 *
 * <p>管理系 3 エンドポイント（POST/PATCH/DELETE）は
 * {@code @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")} の
 * self-gate（既存ゲートの存在を仮定しない・親 §6 の方針踏襲。role ベース・permission 不使用）。
 * GET は {@code @PreAuthorize} を付けず Service 層で
 * {@code ReservationViewAccessGuard.assertCanView}（会員 or 公開。非許可 403 = RESERVATION_021）。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/reservation-menus")
@Tag(name = "予約メニュー管理", description = "F03.4.1 チーム予約メニューCRUD（所要時間・提供可否・料金表示のみ）")
@RequiredArgsConstructor
public class ReservationMenuController {

    private final ReservationMenuService menuService;

    /**
     * メニュー一覧を取得する。会員/公開ユーザーは有効メニューのみ、ADMIN+ は無効含む全件。
     */
    @GetMapping
    @Operation(summary = "予約メニュー一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ReservationMenuResponse>>> listMenus(
            @PathVariable Long teamId) {
        List<ReservationMenuResponse> menus =
                menuService.listMenus(teamId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(menus));
    }

    /**
     * メニューを作成する（最大20件）。
     */
    @PostMapping
    @Operation(summary = "予約メニュー作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<ReservationMenuResponse>> createMenu(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateReservationMenuRequest request) {
        ReservationMenuResponse response =
                menuService.createMenu(teamId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * メニューを部分更新する（提供可否 {@code lineIds} 含む）。
     */
    @PatchMapping("/{menuId}")
    @Operation(summary = "予約メニュー更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<ReservationMenuResponse>> updateMenu(
            @PathVariable Long teamId,
            @PathVariable UUID menuId,
            @Valid @RequestBody UpdateReservationMenuRequest request) {
        ReservationMenuResponse response =
                menuService.updateMenu(teamId, menuId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メニューを削除する（論理削除・200 OK）。
     */
    @DeleteMapping("/{menuId}")
    @Operation(summary = "予約メニュー削除（論理削除）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "削除成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<ReservationMenuDeleteResponse>> deleteMenu(
            @PathVariable Long teamId,
            @PathVariable UUID menuId) {
        ReservationMenuDeleteResponse response =
                menuService.deleteMenu(teamId, menuId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
