package com.mannschaft.app.pointcard.controller;

import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.pointcard.dto.PointCardProviderResponse;
import com.mannschaft.app.pointcard.service.PointCardProviderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * ポイントカードプロバイダー一覧 API。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.2
 *
 * <p>カード追加フォームのプリセットボタンに表示する人気プロバイダー一覧を返す。
 * 認証必須・レート制限 60/min（{@code PointCardRateLimitFilter}）。
 */
@RestController
@RequestMapping("/api/v1/point-cards/providers")
@Tag(name = "ポイントカード プロバイダー", description = "F18 プロバイダー（運営マスタ）一覧")
@RequiredArgsConstructor
public class PointCardProviderController {

    private final PointCardProviderService providerService;

    /**
     * 有効化されている全プロバイダーをカテゴリ昇順・表示名昇順で返す。
     * レスポンス形式は設計書 §6.2 に準拠し {@code {"data": [...]}} でラップする。
     *
     * <p><b>認可方式（{@link AuthorizedByPathConfig} メソッド付与）</b>: {@code SecurityConfig.java:457
     * — .anyRequest().authenticated()}。応答はプロバイダー運営マスタ（is_active=true の一覧）であり、
     * ユーザー固有データを含まない（PointCardProviderController#listProviders）。認証必須のみで足りる。
     * 認可根治戦役 Wave6 監査済。</p>
     */
    @AuthorizedByPathConfig
    @GetMapping
    @Operation(summary = "プロバイダー一覧取得",
            description = "is_active=true のプロバイダーを category, display_name 昇順で返す")
    public ResponseEntity<Map<String, List<PointCardProviderResponse>>> listProviders() {
        return ResponseEntity.ok(Map.of("data", providerService.listActiveProviders()));
    }
}
