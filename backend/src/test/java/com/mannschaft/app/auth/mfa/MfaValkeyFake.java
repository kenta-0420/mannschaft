package com.mannschaft.app.auth.mfa;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

/**
 * CMP-261012-0910 2FA 試練用の Valkey（Redis）インメモリ代役。
 *
 * <h2>なぜ代役が要るのか</h2>
 * <p>{@code AbstractMySqlIntegrationTest} は {@link StringRedisTemplate} を {@code @MockitoBean} にしており
 * （TestContext を 1 つに保つための全 IT 共通構成。再宣言禁止）、そのままでは {@code opsForValue()} が null を返し
 * MFA セッション・試行カウンタ・TOTP 使用済み印・メールOTP がすべて機能しない。既存の auth 系 IT
 * （{@code AuthTokenReplayLogoutPersistenceIT}）や RL 結線 IT（{@code TeamOrgInviteRateLimitWiringIT}）と同じ流儀で、
 * 外部境界である Valkey だけをプロセス内の代役に差し替える（DB・認可・自前 Bean はモックしない）。</p>
 *
 * <h2>実装側への契約（出陣の足軽へ）</h2>
 * <p>本代役は Valkey の<b>単一コマンドの原子性</b>（GET / SET / SET NX / SET XX / INCR / GETDEL / DEL / EXPIRE）を
 * {@code synchronized} で再現する。原子化はこれらのプリミティブ（{@code setIfAbsent} / {@code increment} /
 * {@code getAndDelete} / {@code delete} の戻り値）で行うこと。Lua スクリプト（{@code execute(RedisScript, ...)}）は
 * 本代役では解釈しない（Mock 既定の null が返る）。</p>
 *
 * <h2>競合の決定的再現（{@link #armReadBarrier}）</h2>
 * <p>「読んでから書く」非原子実装（{@code hasKey} → {@code set} 等）は、2 スレッドが同時に読む窓を作らないと
 * たまたま通ってしまう。{@link #armReadBarrier} は指定接頭辞のキーへの<b>読み取り</b>（{@code get} / {@code hasKey}）を
 * 指定人数が揃うまで待たせ、両スレッドが「書く前に読む」順序を強制する。原子プリミティブだけで実装されていれば
 * 読み取り自体が起きないため影響しない（タイムアウト 3 秒で解放されるので原子実装を詰まらせることもない）。</p>
 *
 * <h2>時間</h2>
 * <p>TTL は本代役の内部時計で判定する。{@link #advance(Duration)} で時計を進めると期限切れキーは消える
 * （30 分ロックの境界・10 分の OTP 期限等の境界試験用）。</p>
 */
public final class MfaValkeyFake {

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final AtomicLong clockOffsetMillis = new AtomicLong();
    private volatile String barrierPrefix;
    private volatile CyclicBarrier barrier;
    private final AtomicInteger barrierRemaining = new AtomicInteger();

    private record Entry(String value, Long expiresAtMillis) {
    }

    private MfaValkeyFake() {
    }

    /**
     * 基底クラスの Mock {@link StringRedisTemplate} に本代役を結線する（各テストの {@code @BeforeEach} で呼ぶ。
     * {@code @MockitoBean} はテストごとにリセットされる）。
     */
    @SuppressWarnings("unchecked")
    public static MfaValkeyFake install(StringRedisTemplate redisTemplate) {
        MfaValkeyFake fake = new MfaValkeyFake();
        ValueOperations<String, String> ops = (ValueOperations<String, String>) Proxy.newProxyInstance(
                MfaValkeyFake.class.getClassLoader(),
                new Class<?>[]{ValueOperations.class},
                fake.new ValueOpsHandler(redisTemplate));
        given(redisTemplate.opsForValue()).willReturn(ops);
        willAnswer(inv -> fake.del(inv.getArgument(0))).given(redisTemplate).delete(anyString());
        willAnswer(inv -> {
            Collection<String> keys = inv.getArgument(0);
            long n = 0;
            for (String k : keys) {
                if (Boolean.TRUE.equals(fake.del(k))) {
                    n++;
                }
            }
            return n;
        }).given(redisTemplate).delete(anyCollection());
        willAnswer(inv -> fake.hasKey(inv.getArgument(0))).given(redisTemplate).hasKey(anyString());
        willAnswer(inv -> fake.expire(inv.getArgument(0),
                ((TimeUnit) inv.getArgument(2)).toMillis(inv.getArgument(1))))
                .given(redisTemplate).expire(anyString(), anyLong(), any(TimeUnit.class));
        willAnswer(inv -> fake.expire(inv.getArgument(0), ((Duration) inv.getArgument(1)).toMillis()))
                .given(redisTemplate).expire(anyString(), any(Duration.class));
        willAnswer(inv -> fake.ttlMillis(inv.getArgument(0)) / 1000)
                .given(redisTemplate).getExpire(anyString());
        willAnswer(inv -> {
            long ms = fake.ttlMillis(inv.getArgument(0));
            return ms < 0 ? ms / 1000 : ((TimeUnit) inv.getArgument(1)).convert(ms, TimeUnit.MILLISECONDS);
        }).given(redisTemplate).getExpire(anyString(), any(TimeUnit.class));
        willAnswer(inv -> fake.keys(inv.getArgument(0))).given(redisTemplate).keys(anyString());
        return fake;
    }

    // ─────────────────────────────────────────────────────────────
    // テストから使う操作
    // ─────────────────────────────────────────────────────────────

    /** 内部時計を進める（TTL 判定に使う）。 */
    public void advance(Duration duration) {
        clockOffsetMillis.addAndGet(duration.toMillis());
    }

    /**
     * 指定接頭辞のキーへの読み取りを {@code parties} 件揃うまで待たせる（最大 3 秒）。
     * 揃った時点で解除される（1 回限り）。
     */
    public void armReadBarrier(String keyPrefix, int parties) {
        this.barrier = new CyclicBarrier(parties);
        this.barrierRemaining.set(parties);
        this.barrierPrefix = keyPrefix;
    }

    /** 現在の生存キー（期限切れ除く）のうち、正規表現に一致するもの。 */
    public Set<String> liveKeysMatching(String regex) {
        Pattern p = Pattern.compile(regex);
        return store.keySet().stream()
                .filter(k -> lookup(k) != null)
                .filter(k -> p.matcher(k).find())
                .collect(Collectors.toSet());
    }

    /** キーの残り TTL（ミリ秒）。キー無し -2、期限無し -1（Valkey の PTTL と同じ規約）。 */
    public long ttlMillis(String key) {
        Entry e = lookup(key);
        if (e == null) {
            return -2;
        }
        if (e.expiresAtMillis() == null) {
            return -1;
        }
        return Math.max(0, e.expiresAtMillis() - now());
    }

    /** 生値（期限切れは null）。 */
    public String rawGet(String key) {
        Entry e = lookup(key);
        return e == null ? null : e.value();
    }

    // ─────────────────────────────────────────────────────────────
    // 内部
    // ─────────────────────────────────────────────────────────────

    private long now() {
        return System.currentTimeMillis() + clockOffsetMillis.get();
    }

    private Entry lookup(String key) {
        Entry e = store.get(key);
        if (e != null && e.expiresAtMillis() != null && e.expiresAtMillis() <= now()) {
            store.remove(key, e);
            return null;
        }
        return e;
    }

    private void maybeAwaitBarrier(String key) {
        String prefix = barrierPrefix;
        CyclicBarrier b = barrier;
        if (prefix == null || b == null || !key.startsWith(prefix)) {
            return;
        }
        if (barrierRemaining.getAndDecrement() <= 0) {
            return;
        }
        try {
            b.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (BrokenBarrierException | TimeoutException ex) {
            // 相手が来ない（原子実装で読み取りが 1 回しか起きない等）なら素通しする
        }
    }

    private synchronized Boolean del(String key) {
        return lookup(key) != null && store.remove(key) != null;
    }

    private Boolean hasKey(String key) {
        maybeAwaitBarrier(key);
        synchronized (this) {
            return lookup(key) != null;
        }
    }

    private synchronized Boolean expire(String key, long millis) {
        Entry e = lookup(key);
        if (e == null) {
            return false;
        }
        store.put(key, new Entry(e.value(), now() + millis));
        return true;
    }

    private synchronized Set<String> keys(String glob) {
        String regex = "^" + Pattern.quote(glob).replace("*", "\\E.*\\Q") + "$";
        return liveKeysMatching(regex);
    }

    private final class ValueOpsHandler implements InvocationHandler {

        private final StringRedisTemplate template;

        ValueOpsHandler(StringRedisTemplate template) {
            this.template = template;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            int n = args == null ? 0 : args.length;
            switch (name) {
                case "get":
                    if (n == 1) {
                        String key = (String) args[0];
                        maybeAwaitBarrier(key);
                        synchronized (MfaValkeyFake.this) {
                            return rawGet(key);
                        }
                    }
                    break;
                case "set":
                    if (n == 2) {
                        return put((String) args[0], (String) args[1], null, false, false);
                    }
                    if (n == 3 && args[2] instanceof Duration d) {
                        put((String) args[0], (String) args[1], d.toMillis(), false, false);
                        return null;
                    }
                    if (n == 4 && args[3] instanceof TimeUnit u) {
                        put((String) args[0], (String) args[1], u.toMillis((Long) args[2]), false, false);
                        return null;
                    }
                    break;
                case "setIfAbsent":
                    if (n == 2) {
                        return put((String) args[0], (String) args[1], null, true, false);
                    }
                    if (n == 3 && args[2] instanceof Duration d) {
                        return put((String) args[0], (String) args[1], d.toMillis(), true, false);
                    }
                    if (n == 4 && args[3] instanceof TimeUnit u) {
                        return put((String) args[0], (String) args[1], u.toMillis((Long) args[2]), true, false);
                    }
                    break;
                case "setIfPresent":
                    if (n == 2) {
                        return put((String) args[0], (String) args[1], null, false, true);
                    }
                    if (n == 3 && args[2] instanceof Duration d) {
                        return put((String) args[0], (String) args[1], d.toMillis(), false, true);
                    }
                    if (n == 4 && args[3] instanceof TimeUnit u) {
                        return put((String) args[0], (String) args[1], u.toMillis((Long) args[2]), false, true);
                    }
                    break;
                case "increment":
                    if (n == 1) {
                        return incr((String) args[0], 1L);
                    }
                    if (n == 2 && args[1] instanceof Long delta) {
                        return incr((String) args[0], delta);
                    }
                    break;
                case "decrement":
                    if (n == 1) {
                        return incr((String) args[0], -1L);
                    }
                    if (n == 2 && args[1] instanceof Long delta) {
                        return incr((String) args[0], -delta);
                    }
                    break;
                case "getAndDelete":
                    if (n == 1) {
                        synchronized (MfaValkeyFake.this) {
                            String v = rawGet((String) args[0]);
                            store.remove((String) args[0]);
                            return v;
                        }
                    }
                    break;
                case "getAndSet":
                    if (n == 2) {
                        synchronized (MfaValkeyFake.this) {
                            String v = rawGet((String) args[0]);
                            store.put((String) args[0], new Entry((String) args[1], null));
                            return v;
                        }
                    }
                    break;
                case "getOperations":
                    return template;
                case "toString":
                    return "MfaValkeyFake.ValueOperations";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    break;
            }
            throw new UnsupportedOperationException(
                    "MfaValkeyFake は ValueOperations#" + name + "(" + n + " args) を模していない。"
                            + "必要なら MfaValkeyFake に追加すること");
        }

        private Boolean put(String key, String value, Long ttlMillis, boolean onlyIfAbsent, boolean onlyIfPresent) {
            synchronized (MfaValkeyFake.this) {
                Entry existing = lookup(key);
                if (onlyIfAbsent && existing != null) {
                    return false;
                }
                if (onlyIfPresent && existing == null) {
                    return false;
                }
                store.put(key, new Entry(value, ttlMillis == null ? null : now() + ttlMillis));
                return true;
            }
        }

        private Long incr(String key, long delta) {
            synchronized (MfaValkeyFake.this) {
                Entry existing = lookup(key);
                long current = existing == null ? 0L : Long.parseLong(existing.value());
                long next = current + delta;
                store.put(key, new Entry(String.valueOf(next), existing == null ? null : existing.expiresAtMillis()));
                return next;
            }
        }
    }
}
