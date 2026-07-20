package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.service.AuthOAuthLinkService;
import com.mannschaft.app.common.security.IntentionallyPublic;
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
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・凍結ストア該当 1 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で
 * {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig.java:214 — requestMatchers("/api/v1/auth/oauth/**").permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * OAuth プロバイダからのリダイレクトを受ける<b>認証フローのコールバック</b>で、未認証状態で到達するのが仕様。
 * 資格情報の検証は認証処理そのものが行う。
 * </p>
 *
 * <p>認可根治戦役 Wave5 監査済。レスポンス項目が将来増えた場合は公開の妥当性が崩れうるため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること。</p>
 */
@IntentionallyPublic
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
