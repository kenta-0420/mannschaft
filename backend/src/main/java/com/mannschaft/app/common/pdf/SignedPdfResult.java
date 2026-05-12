package com.mannschaft.app.common.pdf;

import java.time.Instant;

/**
 * 内部署名トークン付き PDF の生成結果（F12.1 §5.14）。
 *
 * <p>v1 簡易方式: {@link #hashSha256()} は PDF 本体の SHA-256（hex 表現、64 桁）、
 * {@link #timestampToken()} は HMAC-SHA256 を Base64URL でエンコードした内部署名トークン。
 *
 * @param pdf            署名対象の PDF バイト列（本実装では入力 PDF をそのまま返す。
 *                       将来 PDF 末尾ページにトークン埋め込みする場合は加工後バイトを返す）
 * @param hashSha256     PDF 本体の SHA-256（hex 小文字 64 桁）
 * @param timestampToken 内部署名トークン（Base64URL）
 * @param signedAt       署名時刻（UTC、{@link Instant}）
 * @param subjectId      署名対象の識別子（succession_covenants.id 等）
 */
public record SignedPdfResult(
        byte[] pdf,
        String hashSha256,
        String timestampToken,
        Instant signedAt,
        String subjectId
) {
}
