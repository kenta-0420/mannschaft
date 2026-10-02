package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reservation.dto.CancelReservationGroupRequest;
import com.mannschaft.app.reservation.dto.CreateReservationGroupRequest;
import com.mannschaft.app.reservation.dto.ReservationGroupCancelResponse;
import com.mannschaft.app.reservation.dto.ReservationGroupResponse;
import com.mannschaft.app.reservation.service.ReservationGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 予約グループコントローラー（F03.4.3 機能G・複数枠/連続枠予約）。
 *
 * <p>認可の割り付け（§4・6 本の正確な割り付け）:</p>
 * <ul>
 *   <li>POST 作成: {@code @PreAuthorize} なし・Service 層で view ゲート（会員 or 公開）</li>
 *   <li>GET 詳細 / cancel: 「本人 or ADMIN」の複合条件のため Service 層で判定（非該当 404 = 040 秘匿）</li>
 *   <li>confirm / complete / no-show の 3 本のみ {@code @PreAuthorize isScopeAdmin}（self-gate・role ベース）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/reservation-groups")
@Tag(name = "予約グループ", description = "F03.4.3 複数枠・連続枠予約（メニュー起点の一括確保）")
@RequiredArgsConstructor
public class ReservationGroupController {

    private final ReservationGroupService groupService;

    /**
     * グループ予約を作成する（連続 N 枠を同一トランザクションで確保・部分成功禁止）。
     */
    @PostMapping
    @Operation(summary = "予約グループ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ReservationGroupResponse>> createGroup(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateReservationGroupRequest request) {
        ReservationGroupResponse response =
                groupService.createGroup(teamId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * グループ詳細を取得する（本人 or ADMIN。非該当は 404 で存在秘匿）。
     */
    @GetMapping("/{groupId}")
    @Operation(summary = "予約グループ詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ReservationGroupResponse>> getGroup(
            @PathVariable Long teamId,
            @PathVariable UUID groupId) {
        ReservationGroupResponse response =
                groupService.getGroup(teamId, groupId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * グループ全枠を一括キャンセルする（本人=締切内 / ADMIN=常時）。
     */
    @PostMapping("/{groupId}/cancel")
    @Operation(summary = "予約グループ一括キャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "キャンセル成功")
    public ResponseEntity<ApiResponse<ReservationGroupCancelResponse>> cancelGroup(
            @PathVariable Long teamId,
            @PathVariable UUID groupId,
            @Valid @RequestBody(required = false) CancelReservationGroupRequest request) {
        String cancelReason = request != null ? request.getCancelReason() : null;
        ReservationGroupCancelResponse response =
                groupService.cancelGroup(teamId, groupId, SecurityUtils.getCurrentUserId(), cancelReason);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * グループ全枠を一括確定する（PENDING → CONFIRMED・ADMIN 限定）。
     */
    @PostMapping("/{groupId}/confirm")
    @Operation(summary = "予約グループ確定")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "確定成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<ReservationGroupResponse>> confirmGroup(
            @PathVariable Long teamId,
            @PathVariable UUID groupId) {
        ReservationGroupResponse response =
                groupService.confirmGroup(teamId, groupId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * グループ全枠を来店完了にする（ADMIN 限定）。
     */
    @PostMapping("/{groupId}/complete")
    @Operation(summary = "予約グループ完了")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "完了成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<ReservationGroupResponse>> completeGroup(
            @PathVariable Long teamId,
            @PathVariable UUID groupId) {
        ReservationGroupResponse response =
                groupService.completeGroup(teamId, groupId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * グループ全枠をノーショーにする（ADMIN 限定）。
     */
    @PostMapping("/{groupId}/no-show")
    @Operation(summary = "予約グループ ノーショー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "マーク成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<ReservationGroupResponse>> markGroupNoShow(
            @PathVariable Long teamId,
            @PathVariable UUID groupId) {
        ReservationGroupResponse response =
                groupService.markGroupNoShow(teamId, groupId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
