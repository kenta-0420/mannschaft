package com.mannschaft.app.errorreport.service;

import java.util.Map;

/**
 * F12.5 Phase 2-C — Claude モデル別の単価マスタ。
 *
 * <p>USD per 1M tokens を {@code [入力, 出力]} の double[2] で保持する。
 * モデルが見つからない場合は Haiku 相当のレートにフォールバックする。</p>
 */
public final class ClaudeModelPricing {

    /** USD → JPY 換算レート（保守的に固定値）。 */
    private static final double USD_TO_JPY = 150.0;

    /** モデルごとの USD per 1M tokens。 */
    private static final Map<String, double[]> PRICING_USD_PER_M_TOKENS = Map.of(
            "claude-haiku-4-5", new double[]{1.0, 5.0},
            "claude-sonnet-4-6", new double[]{3.0, 15.0},
            "claude-opus-4-7", new double[]{15.0, 75.0});

    private ClaudeModelPricing() {
        // ユーティリティクラス
    }

    /**
     * 推定コストを円で算出する（最低 1 円）。
     *
     * @param model            モデル名
     * @param promptTokens     入力トークン数
     * @param completionTokens 出力トークン数
     * @return 推定コスト（円）。
     */
    public static int estimateJpy(String model, int promptTokens, int completionTokens) {
        double[] prices = PRICING_USD_PER_M_TOKENS.getOrDefault(model, new double[]{1.0, 5.0});
        double usd = (promptTokens * prices[0] + completionTokens * prices[1]) / 1_000_000.0;
        int jpy = (int) Math.ceil(usd * USD_TO_JPY);
        return Math.max(1, jpy);
    }
}
