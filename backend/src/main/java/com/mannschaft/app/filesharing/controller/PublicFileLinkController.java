package com.mannschaft.app.filesharing.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.filesharing.dto.AccessLinkRequest;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.dto.SharedFileDownloadUrlResponse;
import com.mannschaft.app.filesharing.service.SharedFileLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F05.5 PR-D: 公開ファイルリンク（Google Drive 風）コントローラー。<b>未認証・非会員でも到達可能</b>な公開経路。
 *
 * <p>トークンが capability（正当に発行されたリンクの所持者は誰でも閲覧可）であり、フォルダスコープ認可
 * （membership / role）は通さない。ただし以下のフラグ検証は<b>必ず</b>通す:</p>
 * <ul>
 *   <li>token 実在（404 LINK_NOT_FOUND・存在秘匿）</li>
 *   <li>is_active（手動失効なら 410 LINK_INACTIVE）</li>
 *   <li>expires_at（期限切れなら 410 LINK_EXPIRED）</li>
 *   <li>password_hash（設定時は照合・不一致なら 403 LINK_PASSWORD_INVALID）</li>
 *   <li>DL 経路のみ: download_allowed（false なら 403 LINK_DOWNLOAD_NOT_ALLOWED）＋
 *       C: download_disabled（true なら 403 DOWNLOAD_DISABLED・C 優先の AND 評価）</li>
 * </ul>
 *
 * <p><b>SecurityConfig</b>: この 2 経路は {@code permitAll}（1 階層厳格 {@code *}・§7.4 IDOR 規約）で公開し、
 * {@code PublicApiRateLimitFilter} でトークン総当りをレート制限する。</p>
 *
 * <p><b>DL 完全防止の原理的限界</b>: ブラウザで表示できる以上、閲覧可能なファイルの完全な DL 防止は不可能。
 * download_allowed / download_disabled は DL URL 発行拒否による運用上の抑止に留まる。</p>
 */
@RestController
@RequestMapping("/api/v1/public/file-links")
@Tag(name = "ファイル共有 - 公開リンク", description = "F05.5 PR-D 公開ファイルリンク（未認証可）")
@RequiredArgsConstructor
public class PublicFileLinkController {

    private final SharedFileLinkService linkService;

    /**
     * 公開リンクでファイルメタにアクセスする（未認証可）。
     */
    @PostMapping("/{token}/access")
    @Operation(summary = "公開リンクアクセス（未認証可・メタ返却）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アクセス成功")
    public ResponseEntity<ApiResponse<FileResponse>> access(
            @PathVariable String token,
            @RequestBody(required = false) AccessLinkRequest request) {
        FileResponse response = linkService.accessLinkPublic(token, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 公開リンクでダウンロード URL を発行する（未認証可・download_allowed && !download_disabled 必須）。
     */
    @PostMapping("/{token}/download-url")
    @Operation(summary = "公開リンク ダウンロードURL発行（未認証可・DL許可リンクのみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    public ResponseEntity<ApiResponse<SharedFileDownloadUrlResponse>> downloadUrl(
            @PathVariable String token,
            @RequestBody(required = false) AccessLinkRequest request) {
        SharedFileDownloadUrlResponse response = linkService.presignDownloadForLink(token, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
