package com.mannschaft.app.actionmemo;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * F02.5 行動メモ機能のユーザー別レートリミットフィルタ。
 *
 * <p>設計書 §6 に従い、以下の4エンドポイントに対してユーザー単位のレートリミットを適用する:</p>
 * <ul>
 *   <li>{@code POST   /api/v1/action-memos} — 60 req/分</li>
 *   <li>{@code POST   /api/v1/action-memos/publish-daily} — 5 req/分（Phase 2 で使用）</li>
 *   <li>{@code POST   /api/v1/action-memo-tags} — 20 req/分（Phase 4 で使用）</li>
 *   <li>{@code PATCH  /api/v1/action-memo-settings} — 10 req/分</li>
 * </ul>
 *
 * <p><b>設計意図</b>: いずれも人間が手動で打ち込む現実的な最大値の数倍に設定。
 * ADHD ユーザーの「思いついた瞬間に書く」摩擦ゼロ原則に矛盾しないよう、
 * ボット・スクリプト・連打バグの防御のみを目的とする。</p>
 *
 * <p><b>Valkey 化（Phase 2 第一陣）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。
 * エンドポイント判定と (zone, limit, window) 宣言のみ本クラスが持ち、
 * カウント・§4.3 標準ヘッダー・429 応答は {@link AbstractRateLimitFilter} が担う。
 * エンドポイントごとに zone を分離しているのは旧実装の「エンドポイント別 Cache 分離」と
 * 同じ趣旨（互いのカウントが干渉しない）。</p>
 */
@Component
public class ActionMemoRateLimitFilter extends AbstractRateLimitFilter {

    /** エンドポイント別の設定（パス・メソッド・上限値は旧実装から不変）。 */
    private enum Endpoint {
        CREATE_MEMO("/api/v1/action-memos", "POST", 60),
        PUBLISH_DAILY("/api/v1/action-memos/publish-daily", "POST", 5),
        CREATE_TAG("/api/v1/action-memo-tags", "POST", 20),
        UPDATE_SETTINGS("/api/v1/action-memo-settings", "PATCH", 10);

        final String path;
        final String method;
        final int capacityPerMinute;

        Endpoint(String path, String method, int capacityPerMinute) {
            this.path = path;
            this.method = method;
            this.capacityPerMinute = capacityPerMinute;
        }

        boolean matches(HttpServletRequest request) {
            return this.path.equals(request.getServletPath())
                    && this.method.equalsIgnoreCase(request.getMethod());
        }
    }

    /** ウィンドウ長（旧 Bucket4j Bandwidth と同じ 1 分）。 */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Valkey zone 接頭辞。エンドポイント名と組み合わせてバケット名前空間を一意化する。 */
    private static final String ZONE_PREFIX = "action-memo:";

    public ActionMemoRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        for (Endpoint ep : Endpoint.values()) {
            if (ep.matches(request)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        for (Endpoint ep : Endpoint.values()) {
            if (ep.matches(request)) {
                return new RateLimitRule(ZONE_PREFIX + ep.name(), ep.capacityPerMinute, WINDOW);
            }
        }
        return null;
    }
}
