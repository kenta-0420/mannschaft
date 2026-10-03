package com.mannschaft.app.common.timezone;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.cache.BoundedTtlCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * ユーザーの timezone をキャッシュするサービス。
 *
 * <p>{@link com.mannschaft.app.common.i18n.UserLocaleCache} と同じパターン。
 * リクエストごとに DB クエリが発生しないよう TTL 5分のインメモリキャッシュを提供する。
 * 単体取得は {@code UserRepository#findTimezoneById}、bulk 取得（{@link #getTimezones}）は
 * {@code UserRepository#findTimezonesByIdIn} を用いる。</p>
 *
 * <p><b>常駐メモリの上限（Issue #2487 項目 1）</b>: 素の {@code ConcurrentHashMap} は件数上限も期限切れ回収も
 * 持たず、「これまでに一度でも見た全ユーザー数」に単調増加していた。現在は
 * {@link BoundedTtlCache}（件数上限つき LRU ＋ 参照時の期限切れ回収）に置き換えてある。
 * 上限は {@code mannschaft.cache.user-timezone.max-entries} で変更できる。</p>
 */
@Service
public class UserTimezoneCache {

    private final UserRepository userRepository;

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String DEFAULT_TIMEZONE = "Asia/Tokyo";

    /** userId → timezone（件数上限つき LRU・TTL 5分）。 */
    private final BoundedTtlCache<Long, String> cache;

    /**
     * @param userRepository ユーザーリポジトリ
     * @param maxEntries     常駐させる最大エントリ数（{@code mannschaft.cache.user-timezone.max-entries}）。
     *                       <b>既定 50,000 の根拠</b>: 1 エントリは「Long キー＋短い IANA 名＋失効時刻＋
     *                       LinkedHashMap のノード」で概ね 200B 前後であり、50,000 件でも約 10MB に収まる。
     *                       一方でベータ第 4 段階の想定規模（1 万人）と、リクエスト駆動で常時アクセスされる
     *                       hot set を丸ごと収容できる。自動付与バッチ（最大 10 万人）は一度きりの
     *                       ストリーミング走査で同一ユーザーを再訪しないため、上限を超えた分が押し出されても
     *                       再クエリは発生しない（{@link BoundedTtlCache} の方式選定理由を参照）。
     */
    public UserTimezoneCache(
            UserRepository userRepository,
            @Value("${mannschaft.cache.user-timezone.max-entries:50000}") int maxEntries) {
        this.userRepository = userRepository;
        this.cache = new BoundedTtlCache<>(maxEntries, TTL);
    }

    /**
     * userId に対応する timezone 文字列を返す。
     * キャッシュヒット（かつ有効期限内）の場合はキャッシュを返す。
     * キャッシュミスまたは期限切れの場合は DB から取得してキャッシュする。
     * DB に timezone が存在しない（null）場合は "Asia/Tokyo" を返す。
     *
     * @param userId ユーザーID
     * @return timezone 文字列（例: "Asia/Tokyo"）
     */
    public String getTimezone(Long userId) {
        String cached = cache.get(userId);
        if (cached != null) {
            return cached;
        }
        // キャッシュミス or 期限切れ: DB から取得
        String timezone = userRepository.findTimezoneById(userId).orElse(DEFAULT_TIMEZONE);
        cache.put(userId, timezone);
        return timezone;
    }

    /**
     * 複数ユーザーの timezone をまとめて解決する（{@link #getTimezone} の bulk 版）。
     *
     * <p>TTL 5 分の既存キャッシュを活かしつつ、<b>キャッシュミス分だけを 1 クエリ</b>
     * （{@code UserRepository#findTimezonesByIdIn}）で取得する。per-user の {@link #getTimezone} を
     * ユーザー数だけ呼ぶと N+1 になるため、バッチ経路（F20.3 ベータ特典の activeDays 集計など）は本メソッドを使う。</p>
     *
     * <p><b>戻り値は必ず全 userId 分のエントリを含む</b>。DB に行が無い（未存在・論理削除済み）・{@code timezone} が
     * NULL/空文字のユーザーは既定の {@code Asia/Tokyo} で埋める（呼び出し側に欠損判定を強いない）。</p>
     *
     * @param userIds 対象ユーザーID群（null/空なら空 Map）
     * @return userId → timezone 文字列（全 userId 分・欠損なし）
     */
    public Map<Long, String> getTimezones(Collection<Long> userIds) {
        Map<Long, String> resolved = new LinkedHashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return resolved;
        }

        // 1. キャッシュヒット分を先に確定し、ミス分だけを bulk クエリの対象にする。
        Set<Long> missing = new LinkedHashSet<>();
        for (Long userId : userIds) {
            if (userId == null || resolved.containsKey(userId)) {
                continue;
            }
            String cached = cache.get(userId);
            if (cached != null) {
                resolved.put(userId, cached);
            } else {
                missing.add(userId);
            }
        }

        // 2. ミス分を 1 クエリで取得しキャッシュへ載せる（未存在・論理削除済みは行が返らない）。
        if (!missing.isEmpty()) {
            for (Object[] row : userRepository.findTimezonesByIdIn(missing)) {
                Long userId = ((Number) row[0]).longValue();
                String timezone = normalize((String) row[1]);
                cache.put(userId, timezone);
                resolved.put(userId, timezone);
            }
            // 3. 行が返らなかった userId は既定値で埋める（Map から欠損させない）。
            //    ※ 存在しないユーザーの既定値はキャッシュしない（後から作成された場合に 5 分間誤った値を返さないため）。
            for (Long userId : missing) {
                resolved.putIfAbsent(userId, DEFAULT_TIMEZONE);
            }
        }
        return resolved;
    }

    /** DB 由来の timezone を正規化する（NULL / 空文字は既定値）。 */
    private static String normalize(String timezone) {
        return (timezone == null || timezone.isBlank()) ? DEFAULT_TIMEZONE : timezone;
    }

    /**
     * timezone 変更時にキャッシュを即時削除する。
     * PUT /api/auth/profile 等でタイムゾーンが更新された後に呼び出すこと
     * （{@code UserService#updateProfile} が唯一の呼び出し元）。
     *
     * @param userId ユーザーID
     */
    public void evict(Long userId) {
        cache.evict(userId);
    }

    /**
     * 現在の常駐エントリ数（件数上限が効いていることを検証するために公開する）。
     *
     * @return エントリ数
     */
    public int size() {
        return cache.size();
    }
}
