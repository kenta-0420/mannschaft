package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.service.AuthOAuthLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth プロバイダからの連携コールバックを受けるエンドポイント。
 * <p>
 * <b>試練(red)用スタブ。</b> エンドポイントメソッドはまだ存在しない（= 404 を返す）。
 * 出陣（実装）フェーズでコールバック処理 → リダイレクトを追加する。
 */
@RestController
@RequestMapping("/api/v1/auth/oauth/link")
@RequiredArgsConstructor
public class AuthOAuthCallbackController {

    private final AuthOAuthLinkService authOAuthLinkService;
}
