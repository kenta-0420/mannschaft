package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.service.AuthOAuthLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * OAuth プロバイダからの連携コールバックを受けるエンドポイント。
 * <p>
 * {@code GET /api/v1/auth/oauth/link/{provider}/callback} を認証不要で公開し、
 * 処理結果に応じてフロントエンドにリダイレクトする。
 */
@RestController
@RequestMapping("/api/v1/auth/oauth/link")
@RequiredArgsConstructor
public class AuthOAuthCallbackController {

    private final AuthOAuthLinkService authOAuthLinkService;

    /**
     * OAuth プロバイダのコールバックを処理し、フロントエンドへリダイレクトする。
     * <p>
     * プロバイダ側でエラーが発生した場合（{@code error} パラメータあり、または {@code code} が null）は
     * {@code ?error=oauth_denied} へリダイレクトする。
     *
     * @param provider プロバイダ識別子（例: {@code GOOGLE}）
     * @param code     認可コード（プロバイダが付与）
     * @param state    認可時に発行した state
     * @param error    プロバイダ側エラー文字列（エラー時のみ存在）
     * @return 302 リダイレクト
     */
    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> handleCallback(
            @PathVariable String provider,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {

        // プロバイダ側エラーまたは code 欠落の場合は null を渡して oauth_denied リダイレクト
        String effectiveCode = (error != null || code == null) ? null : code;
        String redirectUrl = authOAuthLinkService.processCallback(
                provider.toUpperCase(), state, effectiveCode);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(redirectUrl))
                .build();
    }
}
