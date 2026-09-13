package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Billing Center PR6a: 解約（{@code POST …/cancel}）と解約撤回（{@code DELETE …/cancel}）の回数制限
 * （AC-55 / AC-56。正本 05_billing_center.md §370 の rate limit 表）。
 *
 * <h2>バケットの分け方（AC-56）</h2>
 * <p>制限主体は<b>契約の所属 scope</b>（{@code scopeKind:scopeId}）であり、<b>cancel と撤回は
 * 同一バケットを共有する</b>。別バケットにすると「解約 → 撤回」の往復で実効上限が 2 倍になり、
 * 往復するだけで Stripe の変更系（{@code cancel_at_period_end} の on/off）を無制限に打てる。
 * 利用者から見ても「解約まわりの操作は 1 時間に 10 回まで」という一つの制限である。</p>
 *
 * <h2>なぜ Valkey だけに頼らないか</h2>
 * <p>共通基盤 {@link ValkeyRateLimiter} は Valkey 障害・Bean 不在のとき <b>fail-open</b>（通す）で
 * 振る舞う。読み取り系ではそれが正しいが、本エンドポイントは Stripe への変更系呼び出しを
 * 伴う mutation であり、基盤障害を理由に無制限にするのは筋が悪い。そこで
 * <b>Valkey の判定に加えて、プロセス内の固定ウィンドウカウンタでも同じ上限を掛ける</b>。
 * 二つの AND であるため制限は<b>緩まる方向には決して動かない</b>。複数インスタンス構成での
 * 横断的な正確さは従来どおり Valkey が担い、プロセス内カウンタは Valkey が答えられないときの
 * 最後の歯止めとして働く。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingContractCancelRateLimiter {

    /** Valkey キーの名前空間。他の zone と衝突しない一意名。 */
    static final String ZONE = "billing:contract-cancel";

    /** AC-55: 1 ウィンドウあたりの上限。10 回目までは通り、11 回目が 429 になる。 */
    static final int LIMIT_PER_WINDOW = 10;

    /** AC-55: 窓は 1 時間。 */
    static final Duration WINDOW = Duration.ofHours(1);

    private final ValkeyRateLimiter rateLimiter;
    private final Clock clock;

    /** プロセス内の固定ウィンドウカウンタ（キー = {@code scope:windowStart}）。窓が変われば別キーになる。 */
    private final Map<String, AtomicLong> localWindowCounters = new ConcurrentHashMap<>();

    /**
     * 1 回分を消費し、通してよいかを返す。
     *
     * @param scopeKind 契約の scope 種別
     * @param scopeId   契約の scope ID
     * @return 上限内なら true、超過していれば false（呼び出し側が 429 を返す）
     */
    public boolean tryConsume(EntitlementScopeKind scopeKind, Long scopeId) {
        String bucket = scopeKind.name() + ":" + scopeId;
        RateLimitResult distributed =
                rateLimiter.tryConsume(ZONE, bucket, LIMIT_PER_WINDOW, WINDOW);
        boolean localAllowed = consumeLocalWindow(bucket);
        boolean allowed = distributed.allowed() && localAllowed;
        if (!allowed) {
            log.warn("解約/撤回の回数制限に到達: scopeKind={} scopeId={} distributedAllowed={} localAllowed={}",
                    scopeKind, scopeId, distributed.allowed(), localAllowed);
        }
        return allowed;
    }

    /** プロセス内の固定ウィンドウを 1 進め、上限内かを返す。古い窓のエントリはここで捨てる。 */
    private boolean consumeLocalWindow(String bucket) {
        long windowSeconds = Math.max(1L, WINDOW.getSeconds());
        long windowStart = (clock.instant().getEpochSecond() / windowSeconds) * windowSeconds;
        String key = bucket + ":" + windowStart;
        localWindowCounters.keySet().removeIf(existing ->
                existing.startsWith(bucket + ":") && !existing.equals(key));
        long count = localWindowCounters
                .computeIfAbsent(key, ignored -> new AtomicLong())
                .incrementAndGet();
        return count <= LIMIT_PER_WINDOW;
    }
}
