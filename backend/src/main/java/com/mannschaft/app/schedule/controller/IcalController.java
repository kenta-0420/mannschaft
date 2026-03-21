package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.schedule.dto.IcalTokenResponse;
import com.mannschaft.app.schedule.service.IcalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * iCal購読コントローラー。iCalトークン管理・iCalフィード配信APIを提供する。
 */
@RestController
@Tag(name = "iCal購読", description = "F03.3 iCalトークン管理・iCalフィード配信")
@RequiredArgsConstructor
public class IcalController {

    private static final MediaType TEXT_CALENDAR = MediaType.parseMediaType("text/calendar; charset=UTF-8");

    private final IcalService icalService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * iCalトークンを取得する。未発行の場合は自動生成する。
     */
    @GetMapping("/api/v1/me/ical/token")
    @Operation(summary = "iCalトークン取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<IcalTokenResponse>> getToken() {
        IcalTokenResponse response = icalService.getOrCreateToken(getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * iCalトークンを再生成する。
     */
    @PostMapping("/api/v1/me/ical/token/regenerate")
    @Operation(summary = "iCalトークン再生成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "再生成成功")
    public ResponseEntity<ApiResponse<IcalTokenResponse>> regenerateToken() {
        IcalTokenResponse response = icalService.regenerateToken(getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * iCalトークンを削除する。
     */
    @DeleteMapping("/api/v1/me/ical/token")
    @Operation(summary = "iCalトークン削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteToken() {
        icalService.deleteToken(getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * iCalフィードを配信する。認証不要のパブリックエンドポイント。
     * ?action=subscribe の場合は webcal:// スキームへ 302 リダイレクトする。
     * ?scope=team&id={N} / ?scope=organization&id={N} / ?scope=personal でスコープ絞り込み可能。
     */
    @GetMapping("/ical/{token}.ics")
    @Operation(summary = "iCalフィード配信（認証不要）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "iCalフィード")
    public ResponseEntity<byte[]> getIcalFeed(
            @PathVariable String token,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Long id) {

        // webcal:// リダイレクト
        if ("subscribe".equals(action)) {
            String webcalUrl = "webcal://localhost/ical/" + token + ".ics";
            if (scope != null) {
                webcalUrl += "?scope=" + scope;
                if (id != null) {
                    webcalUrl += "&id=" + id;
                }
            }
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(webcalUrl))
                    .build();
        }

        // iCalフィード生成
        String icalContent = icalService.generateIcalFeed(token, scope, id);

        // ETag算出
        String etag = icalService.calculateETag(token, scope, id);

        // ポーリング記録
        icalService.recordPoll(token);

        byte[] body = icalContent.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(TEXT_CALENDAR)
                .header(HttpHeaders.ETAG, "\"" + etag + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, must-revalidate")
                .body(body);
    }
}
