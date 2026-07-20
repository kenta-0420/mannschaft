package com.mannschaft.app.cspreport.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.cspreport.dto.CspReportRequest;
import com.mannschaft.app.cspreport.dto.CspReportWrapper;
import com.mannschaft.app.cspreport.service.CspReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * CSP 違反レポート受信コントローラー。
 * フロントエンドの nuxt.config.ts に設定された report-uri からの
 * ブラウザ自動送信を受け付ける。認証不要エンドポイント。
 *
 * <p>ブラウザは {@code application/csp-report} または {@code application/json} で送信する。
 * 両 Content-Type に対応し、"csp-report" ラッパーあり・なし両形式を処理する。</p>
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・凍結ストア該当 1 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で
 * {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig.java:235 — requestMatchers(POST, "/api/v1/security/csp-reports").permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * CSP 違反レポートは<b>ブラウザが自動送信</b>するもので、認証情報を添付できない（認証を課すと違反検知そのものが機能しない）
 * 。書込専用かつ応答は 204 固定で、情報を一切返さない。
 * </p>
 *
 * <p>認可根治戦役 Wave5 監査済。レスポンス項目が将来増えた場合は公開の妥当性が崩れうるため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること。</p>
 */
@IntentionallyPublic
@RestController
@RequestMapping("/api/v1/security")
@Tag(name = "セキュリティ", description = "CSP 違反レポート受信 API")
@RequiredArgsConstructor
@Slf4j
public class CspReportController {

    private final CspReportService cspReportService;
    private final ObjectMapper objectMapper;

    /**
     * CSP 違反レポートを受信する。
     *
     * <p>ブラウザが送信する 2 種類のフォーマットに対応する:</p>
     * <ul>
     *   <li>W3C 標準: {@code {"csp-report": {...}}} のラッパーあり形式</li>
     *   <li>簡易形式: {@code {...}} のラッパーなし形式</li>
     * </ul>
     *
     * <p>パース失敗や空ボディは WARNING ログのみ記録し、必ず 204 を返す。
     * ブラウザはレスポンスを無視するため、エラー伝播は不要。</p>
     */
    @PostMapping(
            value = "/csp-reports",
            consumes = {
                MediaType.APPLICATION_JSON_VALUE,
                "application/csp-report"  // 一部ブラウザが送る MIME 型
            }
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "CSP 違反レポート受信", description = "ブラウザからの CSP 違反を記録する（認証不要）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "受信成功（ブラウザはレスポンスを無視）")
    public void receiveCspReport(
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request) {

        if (rawBody == null || rawBody.isBlank()) {
            log.debug("CSP違反レポート: 空のボディを受信（無視）");
            return;
        }

        try {
            CspReportRequest reportRequest = parseReport(rawBody);
            if (reportRequest == null) {
                log.warn("CSP違反レポートのパース結果がnull（無視）: body={}", truncateForLog(rawBody));
                return;
            }

            String ipAddress = resolveIpAddress(request);
            String userAgent = request.getHeader("User-Agent");
            cspReportService.receive(reportRequest, ipAddress, userAgent);

        } catch (Exception e) {
            log.warn("CSP違反レポートのパース失敗（無視）: error={}, body={}",
                    e.getMessage(), truncateForLog(rawBody));
        }
    }

    /**
     * JSON ボディを CspReportRequest にパースする。
     * ラッパーあり形式（"csp-report" キー）とラッパーなし形式の両方に対応する。
     */
    private CspReportRequest parseReport(String rawBody) throws Exception {
        // まずラッパーあり形式（W3C 標準）を試みる
        if (rawBody.contains("csp-report")) {
            CspReportWrapper wrapper = objectMapper.readValue(rawBody, CspReportWrapper.class);
            if (wrapper.cspReport() != null) {
                return wrapper.cspReport();
            }
        }

        // ラッパーなし形式を試みる
        return objectMapper.readValue(rawBody, CspReportRequest.class);
    }

    /**
     * X-Forwarded-For を優先して IP アドレスを解決する。
     */
    private String resolveIpAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * ログ出力用にボディを切り詰める（PII 漏洩防止）。
     */
    private String truncateForLog(String body) {
        if (body == null) return null;
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }
}
