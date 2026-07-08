package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reservation.dto.WaitlistEntryResponse;
import com.mannschaft.app.reservation.service.ReservationWaitlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本人のキャンセル待ち一覧コントローラー（F03.4.5 §6.1）。
 *
 * <p>ログインユーザー自身の WAITING 一覧のみを返す（他人の一覧は構造的に取得不可・IDOR 秘匿）。</p>
 */
@RestController
@RequestMapping("/api/v1/users/me/reservation-waitlist")
@Tag(name = "予約管理", description = "F03.4.5 キャンセル待ち（本人）")
@RequiredArgsConstructor
public class MyReservationWaitlistController {

    private final ReservationWaitlistService waitlistService;

    /**
     * 自分のキャンセル待ち一覧を取得する（枠情報同梱・新しい順）。
     */
    @GetMapping
    @Operation(summary = "自分のキャンセル待ち一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<WaitlistEntryResponse>>> listMine() {
        List<WaitlistEntryResponse> response =
                waitlistService.listMine(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
