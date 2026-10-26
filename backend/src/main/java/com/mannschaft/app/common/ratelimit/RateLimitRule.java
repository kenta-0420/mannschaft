package com.mannschaft.app.common.ratelimit;

import java.time.Duration;

/**
 * フィルタが宣言するレートリミット規則（docs/security/06 §4.3）。
 *
 * <p>{@link AbstractRateLimitFilter#resolveRule} が「このリクエストに適用する規則」として返す。
 * 各フィルタはエンドポイント判定とこの (zone, limit, window) 宣言のみを持ち、
 * カウント・ヘッダー・429 応答は基盤側（{@link ValkeyRateLimiter} / {@link AbstractRateLimitFilter}）が担う。</p>
 *
 * @param zone   バケット名前空間（例: {@code "action-memo:CREATE_MEMO"}）。
 *               Valkey キーの一部になるため、フィルタ間・エンドポイント間で一意にすること
 * @param limit  ウィンドウあたりの上限リクエスト数
 * @param window 固定ウィンドウ長（通常 1 分）
 */
public record RateLimitRule(String zone, int limit, Duration window) {
}
