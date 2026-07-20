package com.mannschaft.app.landing.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.landing.dto.PublicStatsResponse;
import com.mannschaft.app.landing.service.PublicStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ランディングページ公開統計コントローラー。認証不要エンドポイント。
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・凍結ストア該当 1 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で
 * {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig.java:333 — requestMatchers(GET, "/api/v1/public/stats").permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * ランディングページ用の<b>集計済み統計値のみ</b>（個票を含まない）。未ログイン訪問者が最初に開くトップページが参照するため公開必須で、
 * 過去に permitAll 登録漏れで 401 となり未ログイン訪問者が /login へ強制遷移する障害を起こした経緯がある。
 * </p>
 *
 * <p>認可根治戦役 Wave5 監査済。レスポンス項目が将来増えた場合は公開の妥当性が崩れうるため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること。</p>
 */
@IntentionallyPublic
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "公開統計", description = "ランディングページ用公開統計API")
@RequiredArgsConstructor
public class PublicStatsController {

    private final PublicStatsService publicStatsService;

    @GetMapping("/stats")
    @Operation(summary = "公開統計取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<PublicStatsResponse>> getPublicStats() {
        PublicStatsResponse stats = publicStatsService.getPublicStats();
        return ResponseEntity.ok(ApiResponse.of(stats));
    }
}
