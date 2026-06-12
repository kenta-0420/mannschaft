package com.mannschaft.app.visibility;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * F01.7 カスタム公開範囲テンプレートのユーザー別レートリミットフィルタ。
 *
 * <p>設計書 §5 に従い、以下の5エンドポイントに対してユーザー単位のレートリミットを適用する:</p>
 * <ul>
 *   <li>{@code POST  /api/v1/visibility-templates} - 10 req/時間</li>
 *   <li>{@code PUT   /api/v1/visibility-templates/{id}} - 30 req/時間</li>
 *   <li>{@code DELETE /api/v1/visibility-templates/{id}} - 30 req/時間</li>
 *   <li>{@code POST  /api/v1/visibility-templates/{id}/evaluate} - 100 req/時間</li>
 *   <li>{@code GET   /api/v1/visibility-templates/{id}/resolved-members} - 20 req/時間</li>
 * </ul>
 *
 * <p><b>設計意図</b>: テンプレート作成・更新・削除は操作の重み（DB書き込み）に対して上限を設定。
 * evaluate は閲覧時に頻繁に呼ばれるため余裕を持った上限を設定。
 * ボット・スクリプト・連打バグの防御のみを目的とする。</p>
 *
 * <p><b>認証必須 / 未認証透過</b>: 未認証ユーザーはセキュリティフィルタで弾かれるため、
 * {@link #resolveRule} で null を返して透過させる。
 * 基底 {@link AbstractRateLimitFilter#resolveClientKey} は認証済みの場合
 * {@code "u:{userId}"} を返すが、このフィルタでは未認証リクエストをそもそも対象外にする
 * ことで旧実装の挙動（userId null の場合は透過）を維持する。</p>
 *
 * <p><b>X-RateLimit-Remaining ヘッダー</b>: 旧実装は手動で付与していたが、
 * 基底 {@link AbstractRateLimitFilter#applyRateLimitHeaders} が §4.3 標準ヘッダー
 * （X-RateLimit-Limit / X-RateLimit-Remaining / X-RateLimit-Reset）を成功・失敗共通で付与するため、
 * 旧実装の手動付与と同等以上の情報が提供される。</p>
 *
 * <p><b>Valkey 化（第二陣A）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。</p>
 */
@Component
public class VisibilityTemplateRateLimitFilter extends AbstractRateLimitFilter {

    private static final String BASE_PATH = "/api/v1/visibility-templates";

    /** エンドポイント別の設定 */
    private enum Endpoint {
        CREATE_TEMPLATE(BASE_PATH, "POST", 10),
        UPDATE_TEMPLATE(BASE_PATH + "/*", "PUT", 30),
        DELETE_TEMPLATE(BASE_PATH + "/*", "DELETE", 30),
        EVALUATE(BASE_PATH + "/*/evaluate", "POST", 100),
        RESOLVED_MEMBERS(BASE_PATH + "/*/resolved-members", "GET", 20);

        final String pathPattern;
        final String method;
        final int capacityPerHour;

        Endpoint(String pathPattern, String method, int capacityPerHour) {
            this.pathPattern = pathPattern;
            this.method = method;
            this.capacityPerHour = capacityPerHour;
        }

        boolean matches(HttpServletRequest request) {
            return this.method.equalsIgnoreCase(request.getMethod())
                    && matchesPath(request.getRequestURI());
        }

        private boolean matchesPath(String uri) {
            // 末尾スラッシュを除去して比較
            String normalizedUri = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;

            if (!pathPattern.contains("*")) {
                // 完全一致
                return pathPattern.equals(normalizedUri);
            }

            // ワイルドカードパターンマッチング
            String[] patternParts = pathPattern.split("/\\*/");
            if (patternParts.length == 1) {
                // 末尾がワイルドカード: BASE_PATH/* の形式
                String prefix = pathPattern.replace("/*", "");
                if (!normalizedUri.startsWith(prefix + "/")) {
                    return false;
                }
                // BASE_PATH/{id} の形式：さらにサブパスがないことを確認
                String remainder = normalizedUri.substring(prefix.length() + 1);
                return !remainder.isEmpty() && !remainder.contains("/");
            }

            // 中間にワイルドカード: BASE_PATH/*/evaluate または BASE_PATH/*/resolved-members
            String prefix = patternParts[0];
            String suffix = patternParts[1];
            if (!normalizedUri.startsWith(prefix + "/")) {
                return false;
            }
            String afterPrefix = normalizedUri.substring(prefix.length() + 1);
            int slashIndex = afterPrefix.indexOf('/');
            if (slashIndex < 0) {
                return false;
            }
            String afterId = afterPrefix.substring(slashIndex + 1);
            return suffix.equals(afterId);
        }
    }

    /** ウィンドウ長（時間ウィンドウ）。 */
    private static final Duration WINDOW = Duration.ofHours(1);

    /** Valkey zone 接頭辞。 */
    private static final String ZONE_PREFIX = "visibility-template:";

    public VisibilityTemplateRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
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
        // 未認証の場合はレート制限をスキップ（セキュリティフィルタで弾かれる）
        // 旧実装の "userId == null ならチェーン透過" の挙動を維持する
        if (!isAuthenticated()) {
            return null;
        }

        // 優先度順にマッチング（より具体的なパターンを先に評価）
        // evaluate と resolved-members は PUT/DELETE より先に判定する必要がある
        for (Endpoint ep : new Endpoint[]{
                Endpoint.EVALUATE,
                Endpoint.RESOLVED_MEMBERS,
                Endpoint.CREATE_TEMPLATE,
                Endpoint.UPDATE_TEMPLATE,
                Endpoint.DELETE_TEMPLATE
        }) {
            if (ep.matches(request)) {
                return new RateLimitRule(ZONE_PREFIX + ep.name(), ep.capacityPerHour, WINDOW);
            }
        }
        return null;
    }
}
