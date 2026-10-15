package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.SpotlightContentResponse;
import com.mannschaft.app.advertising.dto.SpotlightViewRequest;
import com.mannschaft.app.advertising.dto.SpotlightViewResponse;
import com.mannschaft.app.advertising.dto.SpotlightVisitRequest;
import com.mannschaft.app.advertising.dto.SpotlightVisitResponse;
import com.mannschaft.app.advertising.service.SpotlightServingService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F09.19.2 サービング・計測 API（正本 §6.1〜6.4）。中立命名 {@code /api/v1/spotlight/**}。
 *
 * <p>全エンドポイント認証必須（掲載面・計測に userId が必要）。DOM / URL に広告トークンを含めない
 * 中立命名原則（§4）に従い spotlight を用いる。</p>
 *
 * <p><b>試練（red 先行）</b>: サービス層は骨格（{@link UnsupportedOperationException}）。
 * 本コントローラは配線のみで、認証解決後にサービスへ委譲する。</p>
 */
@RestController
@RequestMapping("/api/v1/spotlight")
@Tag(name = "スポットライト配信", description = "F09.19.2 サービング・計測（中立命名）")
@RequiredArgsConstructor
public class SpotlightController {

    private final SpotlightServingService servingService;

    /** 掲載面に表示する広告候補を取得する（§6.2）。200 固定・候補ゼロも 200。 */
    @GetMapping("/content")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "掲載面の広告候補を取得")
    public ResponseEntity<ApiResponse<SpotlightContentResponse>> content(
            @RequestParam String placement,
            @RequestParam(required = false) Integer count,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) Long scopeId,
            @RequestParam(required = false) String template,
            @RequestParam(required = false) String prefecture,
            @RequestParam(required = false) String locale) {
        Long userId = SecurityUtils.getCurrentUserId();
        SpotlightContentResponse res = servingService.serveContent(
                userId, placement, count, scopeType, scopeId, template, prefecture, locale);
        return ResponseEntity.ok(ApiResponse.of(res));
    }

    /** インプレッションを計上する（§6.3）。初回 201・重複は 200。 */
    @PostMapping("/{creativeId}/view")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "インプレッション計上")
    public ResponseEntity<ApiResponse<SpotlightViewResponse>> view(
            @PathVariable Long creativeId,
            @RequestBody SpotlightViewRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        SpotlightViewResponse res = servingService.recordView(userId, creativeId, request);
        HttpStatus status = res.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.of(res));
    }

    /** クリックを計上する（§6.4）。記録時 201・クールダウン中は 200。 */
    @PostMapping("/{creativeId}/visit")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "クリック計上")
    public ResponseEntity<ApiResponse<SpotlightVisitResponse>> visit(
            @PathVariable Long creativeId,
            @RequestBody SpotlightVisitRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SecurityUtils.getCurrentUserId();
        SpotlightVisitResponse res = servingService.recordVisit(userId, creativeId, resolveIp(httpRequest), request);
        HttpStatus status = res.clickId() == null ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.of(res));
    }

    /**
     * 送信元 IP を解決する（信頼プロキシ配下前提の X-Forwarded-For 先頭 + remoteAddr フォールバック）。
     *
     * <p>正本 §6.4 は {@code AbstractRateLimitFilter.resolveIp()} の再利用を求める。当該メソッドは
     * {@code protected static} で本パッケージから呼べないため、出陣時に共有ガード
     * （{@code AdClickRateLimitGuard}）へ集約するか同メソッドを公開する。骨格では等価な最小解決を置く。</p>
     */
    private static String resolveIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
