package com.mannschaft.app.common.ratelimit;

/**
 * レートリミット判定結果（docs/security/06 §4.3 統一仕様）。
 *
 * <p>{@link ValkeyRateLimiter#tryConsume(String, String, int, java.time.Duration)} の返り値。
 * フィルタ層はこの値から §4.3 標準ヘッダー
 * （{@code X-RateLimit-Limit} / {@code X-RateLimit-Remaining} / {@code X-RateLimit-Reset}）と
 * 429 時の {@code Retry-After} を組み立てる。</p>
 *
 * @param allowed           リクエストを通過させてよいか（fail-open 時は常に {@code true}）
 * @param limit             ウィンドウあたりの上限値（{@code X-RateLimit-Limit}）
 * @param remaining         現在ウィンドウの残り回数（{@code X-RateLimit-Remaining}、下限 0）
 * @param resetEpochSeconds 現在ウィンドウが終了しカウンタがリセットされる Unix 秒（{@code X-RateLimit-Reset}）
 * @param retryAfterSeconds リセットまでの残り秒数（429 時の {@code Retry-After}、下限 1）
 */
public record RateLimitResult(
        boolean allowed,
        int limit,
        long remaining,
        long resetEpochSeconds,
        long retryAfterSeconds) {

    /**
     * fail-open 用の結果を生成する（Valkey 障害・Bean 不在時）。
     *
     * <p>可用性優先の設計判断: レートリミット基盤の障害でサービス全体を止めない。
     * 詳細は {@link ValkeyRateLimiter} のクラス Javadoc を参照。</p>
     */
    public static RateLimitResult failOpen(int limit, long resetEpochSeconds, long retryAfterSeconds) {
        return new RateLimitResult(true, limit, limit, resetEpochSeconds, retryAfterSeconds);
    }
}
