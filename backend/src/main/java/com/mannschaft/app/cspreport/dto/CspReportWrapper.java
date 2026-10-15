package com.mannschaft.app.cspreport.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CSP 違反レポートのラッパークラス（W3C 標準ブラウザフォーマット）。
 *
 * <p>ブラウザは通常 "csp-report" キーでレポートをラップして送信する。
 * ラッパーなしのフォーマットは {@link CspReportRequest} を直接パースして対応する。</p>
 *
 * @param cspReport CSP 違反レポートの本体
 */
public record CspReportWrapper(
        @JsonProperty("csp-report") CspReportRequest cspReport
) {}
