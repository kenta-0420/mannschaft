package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
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
     *
     * <p><b>認可（{@link AuthorizedInService} 付与の根拠・認可根治戦役 Wave7 監査済）</b>:
     * 本 EP は引数を取らず、{@code ReservationWaitlistService#listMine(Long)} が
     * {@code ReservationWaitlistEntryRepository#findByUserIdAndStatusOrderByCreatedAtDesc} で
     * <b>呼び出しユーザー自身の userId のみ</b>を検索条件に固定して一覧を返す構造的な自己スコープ EP
     * である。他人の userId を差し込む余地がなく、権限昇格は発生しない。
     * データ依存でない構造的な自己スコープ認可のため白名簿クラス呼び出しを持たず、
     * 本マーカーで監査済であることを明示する。</p>
     */
    @GetMapping
    @Operation(operationId = "listMyWaitlist", summary = "自分のキャンセル待ち一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<List<WaitlistEntryResponse>>> listMine() {
        List<WaitlistEntryResponse> response =
                waitlistService.listMine(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
