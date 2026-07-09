package com.mannschaft.app.analytics.controller;

import com.mannschaft.app.analytics.dto.PageViewBeaconRequest;
import com.mannschaft.app.analytics.service.PageViewRecordingService;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ページビュー計測ビーコン受信コントローラー（F10.8 アクセス解析）。
 *
 * <p>{@code POST /api/v1/page-views} は認証不要（公開ページのゲスト計測を許可）。
 * SecurityConfig に {@code .requestMatchers(HttpMethod.POST, "/api/v1/page-views").permitAll()}
 * を登録済み（二の陣・AC-02 の E2E 耐性前提）。</p>
 *
 * <p>処理フロー:</p>
 * <ol>
 *   <li>Bean Validation でリクエストボディを検証（ENUM 外・相対パス以外は 400）</li>
 *   <li>匿名 cookie {@code mnsft_vid} を解決（新規の場合 Set-Cookie ヘッダーを付与）</li>
 *   <li>{@link PageViewRecordingService#record} でイベントを publish → 即 202 Accepted 返却</li>
 *   <li>DB 書き込みは非同期リスナー（{@code PageViewRecordListener}）が行う</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/page-views")
@Tag(name = "アクセス解析ビーコン")
@RequiredArgsConstructor
public class PageViewController {

    private final PageViewRecordingService recordingService;

    /**
     * ページビュー計測ビーコン受信。
     *
     * <p>認証済み・未認証いずれも受け付ける。未認証の場合は {@code userId = null}（ゲスト計測）。
     * Body 検証エラー（ENUM 外・url 絶対 URL 等）は Bean Validation が 400 で返す（AC-04・AC-22）。</p>
     *
     * @param existingVisitorId 既存の匿名訪問者 cookie 値（未発行時は {@code null}）
     * @param body              ビーコンリクエストボディ
     * @return 202 Accepted（ボディなし）
     */
    @PostMapping
    @Operation(summary = "ページビュービーコン送信", description = "閲覧イベントを非同期で記録する。認証不要。")
    public ResponseEntity<Void> receiveBeacon(
            @CookieValue(name = PageViewRecordingService.VISITOR_COOKIE_NAME, required = false)
            String existingVisitorId,
            @Valid @RequestBody PageViewBeaconRequest body) {

        // 匿名訪問者 ID を解決（既存 cookie 再利用 or 新規採番）
        String visitorId = recordingService.resolveVisitorId(existingVisitorId);

        // 認証済みユーザーの ID を取得（未認証なら null = ゲスト）
        Long userId = SecurityUtils.getCurrentUserIdOrNull();

        // イベントを publish → 即 202 返却（DB 書き込みは非同期）
        recordingService.record(
                body.getScope(),
                body.getScopeId(),
                body.getContentType(),
                body.getContentId(),
                body.getUrl(),
                body.getTitle(),
                userId,
                visitorId);

        // 新規採番 cookie のみ Set-Cookie（毎回返すと無駄・AC-03）
        if (recordingService.isNewVisitor(existingVisitorId)) {
            ResponseCookie cookie = recordingService.buildVisitorCookie(visitorId);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .header(HttpHeaders.SET_COOKIE, cookie.toString())
                    .build();
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
