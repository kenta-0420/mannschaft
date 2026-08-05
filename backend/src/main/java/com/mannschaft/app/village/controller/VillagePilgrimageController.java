package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.village.dto.PilgrimageRecommendationResponse;
import com.mannschaft.app.village.service.VillagePilgrimageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F17.1 Phase 3-β — 巡礼 Controller。
 *
 * <p>「おすすめ村ローテーション」を扱う認証ユーザー専用 API。
 * 推薦の発番は日次バッチで行うため、本 Controller では参照・訪問記録・履歴一覧のみ提供する。</p>
 *
 * <p>担当 API:</p>
 * <ul>
 *   <li>{@code GET  /api/v1/me/pilgrimage/today} — 今日の推薦を 1 件取得（無ければ 200 + null）</li>
 *   <li>{@code POST /api/v1/me/pilgrimage/{recommendationId}/visit} — 訪問記録</li>
 *   <li>{@code GET  /api/v1/me/pilgrimage/history} — 自分の巡礼履歴（推薦日降順）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/me/pilgrimage")
@Tag(name = "巡礼 (F17.1 Phase 3-β)",
     description = "おすすめ村ローテーション: 日次バッチが生成した推薦の参照・訪問記録・履歴")
@RequiredArgsConstructor
public class VillagePilgrimageController {

    /** デフォルトページサイズ（Service 側の上限 100 と同期）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final VillagePilgrimageService pilgrimageService;

    /**
     * 今日の巡礼推薦を取得する。バッチ未実行 / 対象村なしの場合は {@code data: null}。
     */
    @SelfScopedEndpoint("検索条件が (SecurityUtils.getCurrentUserId(), 当日) のみで、"
            + "リクエストは他ユーザーの識別子も推薦 ID も受け取らない"
            + "（VillagePilgrimageService#getTodaysRecommendation の findByUserIdAndRecommendedDate）")
    @GetMapping("/today")
    @Operation(summary = "今日の巡礼推薦を取得する（無ければ data: null）")
    public ResponseEntity<ApiResponse<PilgrimageRecommendationResponse>> getToday() {
        Long userId = SecurityUtils.getCurrentUserId();
        Optional<PilgrimageRecommendationResponse> today = pilgrimageService.getTodaysRecommendation(userId);
        return ResponseEntity.ok(ApiResponse.of(today.orElse(null)));
    }

    /**
     * 巡礼推薦を訪問したことを記録する。既に訪問済みなら冪等 no-op。
     *
     * <p>認可は {@link VillagePilgrimageService#recordVisit} 内で実施する。推薦エンティティを
     * 先に取得し、その {@code userId} が認証主体と一致しない場合は
     * {@code PILGRIMAGE_NOT_FOUND}（404）で存在を秘匿する。</p>
     */
    @AuthorizedInService
    @PostMapping("/{recommendationId}/visit")
    @Operation(summary = "巡礼推薦の訪問を記録する（冪等）")
    public ApiResponse<PilgrimageRecommendationResponse> recordVisit(
            @PathVariable("recommendationId") UUID recommendationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(pilgrimageService.recordVisit(userId, recommendationId));
    }

    /**
     * 自分の巡礼履歴を取得する（推薦日降順）。
     */
    @SelfScopedEndpoint("検索条件が SecurityUtils.getCurrentUserId() のみで、"
            + "リクエストはページング指定しか受け取らない"
            + "（VillagePilgrimageService#listMyHistory の findByUserIdOrderByRecommendedDateDesc）")
    @GetMapping("/history")
    @Operation(summary = "自分の巡礼履歴を取得する（推薦日降順）")
    public ApiResponse<List<PilgrimageRecommendationResponse>> history(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        Pageable pageable = PageRequest.of(Math.max(page, 0), size <= 0 ? DEFAULT_PAGE_SIZE : size);
        return ApiResponse.of(pilgrimageService.listMyHistory(userId, pageable));
    }
}
