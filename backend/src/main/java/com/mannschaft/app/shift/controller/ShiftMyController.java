package com.mannschaft.app.shift.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shift.dto.MyConfirmedSlotResponse;
import com.mannschaft.app.shift.service.ShiftMyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.security.SelfScopedEndpoint;

import java.util.List;

/**
 * ログインユーザー自身のシフト情報コントローラー。
 *
 * <p>GET /api/v1/shifts/my/** に対応するユーザー向けシフト照会APIを提供する。
 * 全エンドポイントは認証済みユーザーのみアクセス可能（SecurityConfig の authenticated() により保護）。</p>
 */
@RestController
@RequestMapping("/api/v1/shifts/my")
@Tag(name = "マイシフト", description = "F03.5 ログインユーザー自身の確定シフト照会")
@RequiredArgsConstructor
public class ShiftMyController {

    private final ShiftMyService shiftMyService;

    /**
     * ログインユーザーの確定シフト枠一覧を取得する。
     *
     * <p>ShiftAssignment.status = CONFIRMED の割当のみを返す。
     * 日付昇順・開始時刻昇順でソートされる。</p>
     *
     * @return 確定シフト枠レスポンスのリスト
     */
    @SelfScopedEndpoint("ShiftMyService#getMyConfirmedSlots がSecurityUtils.getCurrentUserId()のみを対象に"
            + "確定シフトを検索する")
    @GetMapping("/confirmed-slots")
    @Operation(summary = "確定シフト一覧取得", description = "ログインユーザーの確定済みシフト枠を一覧で取得する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<MyConfirmedSlotResponse>>> getMyConfirmedSlots() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<MyConfirmedSlotResponse> responses = shiftMyService.getMyConfirmedSlots(userId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }
}
