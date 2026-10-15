package com.mannschaft.app.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.lang.Nullable;

/**
 * キャッシュ基盤（Valkey/Redis）障害時に <b>fail-open</b>（例外を握らず握り潰して業務処理を続行）する
 * {@link CacheErrorHandler}。
 *
 * <h3>なぜ握り潰すのか（マスター御裁可・可用性優先）</h3>
 *
 * <p>Spring 既定の {@code SimpleCacheErrorHandler} はキャッシュ操作の例外を<b>そのまま再送出</b>するため、
 * Valkey 断のときに {@code @CacheEvict} を持つミューテーション（例:
 * {@code RoleService.changeRole} の {@code @CacheEvict("role-permissions")}）が
 * {@code RedisConnectionFailureException} で 500 になる。つまり
 * <b>「キャッシュ基盤が落ちると権限の降格・除名ができなくなる」</b>。</p>
 *
 * <p>御裁可された方針は明快である —
 * <b>Redis が落ちている間も「権限の変更（降格・除名）」は成功させる。</b>
 * 緊急時に悪意あるユーザーを降格・除名できない方が、旧権限が最大 TTL ぶん残ることより危険だからである。
 * よって本ハンドラは 4 フックすべてで例外を再送出しない。</p>
 *
 * <h3>なぜ安全と言えるのか（TTL による自然収束）</h3>
 *
 * <p>本アプリのキャッシュは <b>TTL 無しのものが 1 件も存在しない</b>
 * （{@link RedisConfig#cacheManager} の個別指定と既定 30 分でカバーされている）。
 * したがって evict に失敗して古い値が残っても、最悪でも既定 30 分・認可に効く系
 * （{@code role-permissions} / {@code visibilityTemplate} 5 分、{@code dashboard:viewer-role} 60 秒、
 * {@code entitlement:check} 60 秒）は数分で<b>自然収束</b>する。
 * さらに evict が失敗する状況＝Valkey へ到達できない状況では
 * {@code @Cacheable} の get も同時に失敗して常にミス（＝毎回 DB を引く）になるため、
 * 「古い値を返し続ける」窓は実際には TTL よりさらに狭い。</p>
 *
 * <h3>「静かな無効化」にはしない</h3>
 *
 * <p>エラー握り潰し禁止の原則（CLAUDE.md「障害対応の原則」）と両立させるため、
 * fail-open は必ず {@code log.warn} ＋ Micrometer カウンタ {@value #FAIL_OPEN_METRIC}
 * （tag: {@code operation} = get/put/evict/clear, {@code cache} = キャッシュ名）で可視化する。
 * 既存の fail-open 実装（{@code ValkeyRateLimiter} /
 * {@code MembershipChangedListener} / {@code EntitlementCacheEvictor}）と同方針・同作法であり、
 * 本クラスはそれをアノテーション経由の {@code @Cacheable}/{@code @CacheEvict} にも水平展開したものである。</p>
 *
 * <p><b>evict / clear の失敗は get / put より重い</b>。get/put の失敗は「キャッシュが効かない」だけだが、
 * evict/clear の失敗は<b>認可判断が腐りうる</b>（降格したのに旧ロールが残る等）。
 * 同じ WARN でもログ文言でその旨を明示し、運用が両者を区別できるようにしている。</p>
 *
 * <p>本ハンドラはキャッシュ媒体に依存せず {@code CacheInterceptor} が適用するため、
 * 本番の {@code RedisCacheManager} でも test プロファイルの
 * {@code ConcurrentMapCacheManager} でも同一に効く。</p>
 *
 * @see CacheErrorHandlingConfig#errorHandler()
 */
@Slf4j
public class LoggingCacheErrorHandler implements CacheErrorHandler {

    /** fail-open 発生カウンタのメトリクス名（tag: operation / cache）。 */
    public static final String FAIL_OPEN_METRIC = "mannschaft.cache.failopen";

    /** 全キャッシュ横断の共通末尾文言（get / put 用）。 */
    private static final String BENIGN_SUFFIX =
            "キャッシュ基盤障害のため fail-open で続行する（当該操作はキャッシュ無しとして扱われる）";

    /** evict / clear 用の末尾文言。認可が腐りうることを運用に明示する。 */
    private static final String AUTHZ_RISK_SUFFIX =
            "キャッシュ無効化に失敗したため fail-open で業務処理を続行する。"
                    + "古い値が TTL 満了まで残るため認可に影響しうる（ロール/可視性の反映遅延）";

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public LoggingCacheErrorHandler(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /**
     * キャッシュ読み取り失敗。握り潰すと {@code @Cacheable} はキャッシュミス扱いとなり
     * 対象メソッド本体（DB 取得）がそのまま実行される＝結果は常に正しい。
     */
    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("キャッシュ get 失敗: cache={} key={} reason={} — {}",
                cacheName(cache), key, message(exception), BENIGN_SUFFIX, exception);
        recordFailOpen("get", cacheName(cache));
    }

    /**
     * キャッシュ書き込み失敗。握り潰しても戻り値はメソッド本体の結果がそのまま返るため、
     * 呼び出し元から見た振る舞いは「キャッシュに載らなかった」だけである。
     */
    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key,
                                    @Nullable Object value) {
        log.warn("キャッシュ put 失敗: cache={} key={} reason={} — {}",
                cacheName(cache), key, message(exception), BENIGN_SUFFIX, exception);
        recordFailOpen("put", cacheName(cache));
    }

    /**
     * キャッシュ無効化（個別キー）失敗。<b>get/put より重い</b> — 降格・除名等の
     * 権限変更が旧値のまま残りうるため、ログ文言で認可影響を明示する。
     * それでもミューテーション自体は成功させる（御裁可方針）。
     */
    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn("キャッシュ evict 失敗: cache={} key={} reason={} — {}",
                cacheName(cache), key, message(exception), AUTHZ_RISK_SUFFIX, exception);
        recordFailOpen("evict", cacheName(cache));
    }

    /**
     * キャッシュ全消し（{@code allEntries = true}）失敗。
     * evict と同じく認可影響がありうるため同水準で可視化する。
     */
    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn("キャッシュ clear（全件無効化）失敗: cache={} reason={} — {}",
                cacheName(cache), message(exception), AUTHZ_RISK_SUFFIX, exception);
        recordFailOpen("clear", cacheName(cache));
    }

    /**
     * fail-open 発生を Micrometer に記録する。
     *
     * <p>{@link ObjectProvider} 経由で遅延解決するのは、MeterRegistry を持たない最小テスト
     * コンテキストでも本ハンドラが機能するようにするため（{@code ValkeyRateLimiter} と同作法）。</p>
     */
    private void recordFailOpen(String operation, String cacheName) {
        meterRegistryProvider.ifAvailable(registry ->
                Counter.builder(FAIL_OPEN_METRIC)
                        .description("キャッシュ基盤障害による fail-open 発生回数")
                        .tag("operation", operation)
                        .tag("cache", cacheName)
                        .register(registry)
                        .increment());
    }

    /** Cache が null の異常系でも NPE を出さない（fail-open のハンドラ自身が落ちては本末転倒）。 */
    private static String cacheName(@Nullable Cache cache) {
        return cache != null ? cache.getName() : "unknown";
    }

    private static String message(@Nullable RuntimeException exception) {
        return exception != null ? exception.getMessage() : "(no message)";
    }
}
