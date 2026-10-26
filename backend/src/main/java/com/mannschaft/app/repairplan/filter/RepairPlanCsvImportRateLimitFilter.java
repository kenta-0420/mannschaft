package com.mannschaft.app.repairplan.filter;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 修繕計画 CSV インポート用レートリミットフィルタ（F08.8 Phase 1）。
 *
 * <p>{@code POST /api/v1/<scopeType>/<scopeId>/repair-plan/items/import-csv}
 * および {@code .../import-csv/confirm} に対し、ユーザー単位で 5 req/分の上限を課す。</p>
 *
 * <p>5MB の CSV アップロード処理が連続して発生するとサーバー負荷が高いため、
 * 人間が手動で操作する現実的なペースを十分上回らない値（人力で 1 分に 5 回は無理）に絞っている。</p>
 *
 * <p><b>Valkey 化（第二陣B）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。
 * カウント・§4.3 標準ヘッダー・429 応答は {@link AbstractRateLimitFilter} が担う。</p>
 */
@Component
public class RepairPlanCsvImportRateLimitFilter extends AbstractRateLimitFilter {

    /** 分あたりの許容リクエスト数（旧実装から不変）*/
    private static final int CAPACITY_PER_MINUTE = 5;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** マッチ対象のパス末尾（複数のスコープ階層に対応するため endsWith で判定） */
    private static final String IMPORT_PATH_SUFFIX = "/repair-plan/items/import-csv";

    private static final String CONFIRM_PATH_SUFFIX = "/repair-plan/items/import-csv/confirm";

    private static final String ZONE = "repairplan:csv-import";

    public RepairPlanCsvImportRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        if (path == null) return true;
        // confirm も import-csv 自身もどちらも /repair-plan/items/import-csv で始まる
        return !(path.endsWith(IMPORT_PATH_SUFFIX) || path.endsWith(CONFIRM_PATH_SUFFIX));
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null) return null;
        if (path.endsWith(IMPORT_PATH_SUFFIX) || path.endsWith(CONFIRM_PATH_SUFFIX)) {
            return new RateLimitRule(ZONE, CAPACITY_PER_MINUTE, WINDOW);
        }
        return null;
    }
}
