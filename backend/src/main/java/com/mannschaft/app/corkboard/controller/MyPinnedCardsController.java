package com.mannschaft.app.corkboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.corkboard.dto.PinnedCardListResponse;
import com.mannschaft.app.corkboard.service.MyPinnedCardsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F09.8.1 Phase 3 ピン止めカード横断取得コントローラ。
 *
 * <p>個人ボード横断のピン止めカード一覧と参照先メタデータを 1 リクエストで返す
 * ダッシュボード用 API を提供する。</p>
 *
 * <p>API パス設計の根拠は設計書 §4.3 / D-10。既存 {@link MyCorkboardController} の
 * ベース URL {@code /api/v1/users/me/corkboards} に揃え API 探索性を上げる。</p>
 */
@RestController
@RequestMapping("/api/v1/users/me/corkboards")
@Tag(name = "個人コルクボード（横断ピン止め）", description = "F09.8.1 ピン止めカード横断取得 API")
@RequiredArgsConstructor
public class MyPinnedCardsController {

    private final MyPinnedCardsService pinnedCardsService;

    /**
     * ピン止めカード横断取得。
     *
     * @param limit  取得件数（省略時は 20、最大 50。範囲外は丸める）
     * @param cursor 前回レスポンスの {@code nextCursor}（初回は省略）
     */
    @GetMapping("/pinned-cards")
    @Operation(summary = "ピン止めカード横断取得（ダッシュボード用）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<PinnedCardListResponse>> listPinnedCards(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        Long userId = SecurityUtils.getCurrentUserId();
        PinnedCardListResponse response = pinnedCardsService.list(userId, limit, cursor);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
