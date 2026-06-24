package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.service.AuthOAuthLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 設定画面からの OAuth 連携開始エンドポイント。
 * <p>
 * <b>試練(red)用スタブ。</b> エンドポイントメソッドはまだ存在しない（= 404 を返す）。
 * 受け入れ条件 AC-1〜AC-4 のテストはこの 404 によって RED になる。
 * 出陣（実装）フェーズで認可URL生成エンドポイント等を追加し、テストを green 化する。
 */
@RestController
@RequestMapping("/api/v1/users/me/oauth/link")
@RequiredArgsConstructor
public class UserOAuthLinkController {

    private final AuthOAuthLinkService authOAuthLinkService;
}
