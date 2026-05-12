package com.mannschaft.app.common.pdf.verify;

import java.time.Instant;

/**
 * 内部署名トークン検証 API（F12.1 §5.14 / F09.15 §9.4）のレスポンス DTO。
 *
 * @param valid        最終判定（hashMatch && tokenValid）
 * @param hashMatch    SHA-256 ハッシュが期待値と一致したか
 * @param tokenValid   内部署名トークンが再計算結果と一致したか
 * @param computedHash 再計算した SHA-256（hex 小文字 64 桁）
 * @param verifiedAt   検証実行時刻
 */
public record PdfSignatureVerifyResponse(
        boolean valid,
        boolean hashMatch,
        boolean tokenValid,
        String computedHash,
        Instant verifiedAt
) {
}
