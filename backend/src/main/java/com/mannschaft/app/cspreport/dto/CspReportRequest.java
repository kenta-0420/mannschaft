package com.mannschaft.app.cspreport.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * CSP 違反レポートリクエスト（W3C 標準フォーマット）。
 *
 * <p>ブラウザは以下の JSON 構造で送信する:</p>
 * <pre>
 * {
 *   "csp-report": {
 *     "document-uri": "https://example.com/page",
 *     "blocked-uri": "https://evil.com/script.js",
 *     "violated-directive": "script-src",
 *     ...
 *   }
 * }
 * </pre>
 *
 * <p>ブラウザによっては "csp-report" ラッパーがない場合もある。
 * {@link CspReportWrapper} と組み合わせて両方のフォーマットに対応する。</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CspReportRequest {

    @JsonProperty("document-uri")
    @Size(max = 1000)
    private String documentUri;

    @JsonProperty("blocked-uri")
    @Size(max = 1000)
    private String blockedUri;

    @JsonProperty("violated-directive")
    @Size(max = 200)
    private String violatedDirective;

    @JsonProperty("effective-directive")
    @Size(max = 200)
    private String effectiveDirective;

    @JsonProperty("original-policy")
    private String originalPolicy;

    @JsonProperty("disposition")
    @Size(max = 20)
    private String disposition;

    @JsonProperty("script-sample")
    @Size(max = 500)
    private String scriptSample;

    @JsonProperty("status-code")
    private Integer statusCode;
}
