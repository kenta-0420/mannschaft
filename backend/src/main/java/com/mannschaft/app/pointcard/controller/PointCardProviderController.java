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
     */
    // SecurityConfig の anyRequest().authenticated() で認証必須。応答は運営マスタの
    // プロバイダー一覧のみで、全認証済みユーザーに同一の結果を返す（利用者固有情報を含まない）。
    @AuthorizedByPathConfig("anyRequest().authenticated()")
    @GetMapping
    @Operation(summary = "プロバイダー一覧取得",
            description = "is_active=true のプロバイダーを category, display_name 昇順で返す")
    public ResponseEntity<Map<String, List<PointCardProviderResponse>>> listProviders() {
        return ResponseEntity.ok(Map.of("data", providerService.listActiveProviders()));
    }
}
