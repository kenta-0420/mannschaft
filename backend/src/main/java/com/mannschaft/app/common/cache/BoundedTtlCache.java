package com.mannschaft.app.common.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>件数上限つき・TTL つきのインメモリキャッシュ</b>（Issue #2487 項目 1）。
 *
 * <h2>解決した問題</h2>
 * <p>{@code UserTimezoneCache} / {@code UserLocaleCache} は素の {@link java.util.concurrent.ConcurrentHashMap}
 * を持ち、<b>件数上限も期限切れの回収も無かった</b>（削除は明示 {@code evict} のみ）。
 * #2482 で一括取得の経路（ベータ特典 自動付与バッチ = 1 ページ 500 件 × 最大 200 ページ）ができたため、
 * 常駐メモリが「これまでに一度でも見た全ユーザー数」に単調増加する構造だった。
 * 1000 万ユーザー規模（{@code docs/architecture/db_scalability.md}）では現実の問題になる。</p>
 *
 * <h2>方式: 件数上限つき LRU ＋ 参照時の期限切れ回収</h2>
 * <ul>
 *   <li><b>件数上限</b> — {@link LinkedHashMap} の {@code accessOrder=true} ＋
 *       {@link LinkedHashMap#removeEldestEntry} で、上限を超えた時点で<b>最も長く参照されていない</b>
 *       エントリを 1 件追い出す。上限は呼び出し側が設定値から与える（外部ライブラリを増やさない）。</li>
 *   <li><b>期限切れ回収</b> — {@link #get} で期限切れを検出したら、その場でエントリを削除して miss を返す
 *       （期限切れの値を返さないだけでなく、参照されたゴミを残さない）。件数の天井は LRU が保証するため、
 *       未参照のまま期限切れになったエントリを掃くための定期タスク（スレッド）は設けない。</li>
 * </ul>
 *
 * <p><b>なぜ LRU で足りるか</b>: 大量投入の主犯であるバッチはページを一度だけ舐める<b>ストリーミング走査</b>
 * であり、同一ユーザーを再訪しない。よって後続ページに押し出されても再クエリは発生しない。
 * 一方、リクエスト駆動の常連ユーザーは繰り返し参照されるため LRU の上位に残る。
 * 「バッチの通過分を切り捨て、リクエストの hot set を守る」という LRU の性質が、この用途に最も合う。</p>
 *
 * <h2>スレッド安全性</h2>
 * <p>{@link LinkedHashMap} は accessOrder=true のとき {@code get} でも内部リンクを書き換えるため、
 * {@code ConcurrentHashMap} のような無ロック読み取りはできない。本クラスは専用のロックオブジェクトで
 * <b>map 操作のみ</b>を保護する（DB アクセスは呼び出し側がロック外で行うため、I/O 中にロックを保持しない）。
 * ロック外で同一キーが同時に miss した場合、従来の {@code ConcurrentHashMap} 実装と同じく
 * ロードが重複しうるが、結果は同値であり挙動は変わらない。</p>
 *
 * @param <K> キー型（userId 等）
 * @param <V> 値型（timezone / locale 文字列等）
 */
public class BoundedTtlCache<K, V> {

    private final Duration ttl;
    private final Clock clock;
    private final Object lock = new Object();
    private final LruMap<K, CacheEntry<V>> entries;

    /**
     * @param maxEntries 常駐させる最大エントリ数（1 以上）
     * @param ttl        エントリの有効期間
     */
    public BoundedTtlCache(int maxEntries, Duration ttl) {
        this(maxEntries, ttl, Clock.systemUTC());
    }

    /**
     * テストから期限切れを決定論的に検証するための {@link Clock} 差し込み口。
     *
     * @param maxEntries 常駐させる最大エントリ数（1 以上）
     * @param ttl        エントリの有効期間
     * @param clock      現在時刻の供給源
     */
    BoundedTtlCache(int maxEntries, Duration ttl, Clock clock) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries は 1 以上である必要があります: " + maxEntries);
        }
        this.ttl = ttl;
        this.clock = clock;
        this.entries = new LruMap<>(maxEntries);
    }

    /**
     * キーに対応する値を返す。未登録または期限切れなら {@code null}（＝呼び出し側がロードする）。
     * 期限切れを検出した場合はエントリをその場で削除する。
     *
     * @param key キー
     * @return 有効な値。無ければ {@code null}
     */
    public V get(K key) {
        Instant now = clock.instant();
        synchronized (lock) {
            CacheEntry<V> entry = entries.get(key);
            if (entry == null) {
                return null;
            }
            if (now.isAfter(entry.expiresAt())) {
                entries.remove(key);
                return null;
            }
            return entry.value();
        }
    }

    /**
     * 値を登録する（TTL は登録時点から起算）。上限を超えた場合は最も長く参照されていないエントリを追い出す。
     *
     * @param key   キー
     * @param value 値
     */
    public void put(K key, V value) {
        Instant expiresAt = clock.instant().plus(ttl);
        synchronized (lock) {
            entries.put(key, new CacheEntry<>(value, expiresAt));
        }
    }

    /**
     * 指定キーのエントリを即時削除する（値の更新時に呼ぶ）。
     *
     * @param key キー
     */
    public void evict(K key) {
        synchronized (lock) {
            entries.remove(key);
        }
    }

    /**
     * 現在の常駐エントリ数（上限の実効性を検証するために公開する）。
     *
     * @return エントリ数
     */
    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /** 値と失効時刻の組。 */
    private record CacheEntry<V>(V value, Instant expiresAt) {
    }

    /** 件数上限に達したら最も長く参照されていないエントリを 1 件追い出す {@link LinkedHashMap}。 */
    private static final class LruMap<K, V> extends LinkedHashMap<K, V> {

        private static final long serialVersionUID = 1L;

        private final int maxEntries;

        LruMap(int maxEntries) {
            // accessOrder=true: get() でも「直近利用」に押し上げる（挿入順ではなく参照順で追い出す）。
            super(16, 0.75f, true);
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxEntries;
        }
    }
}
