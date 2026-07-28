package com.mannschaft.app.common.timezone;

import com.mannschaft.app.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ユーザーの timezone をキャッシュするサービス。
 *
 * <p>{@link UserLocaleCache} と同じパターン。
 * リクエストごとに DB クエリが発生しないよう TTL 5分のインメモリキャッシュを提供する。
 * 単体取得は {@code UserRepository#findTimezoneById}、bulk 取得（{@link #getTimezones}）は
 * {@code UserRepository#findTimezonesByIdIn} を用いる。</p>
 */
@Service
@RequiredArgsConstructor
public class UserTimezoneCache {

    private final UserRepository userRepository;

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String DEFAULT_TIMEZONE = "Asia/Tokyo";

    /** userId → キャッシュエントリ */
    private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();

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
        CacheEntry entry = cache.get(userId);
        if (entry != null && !entry.isExpired()) {
            return entry.timezone();
        }
        // キャッシュミス or 期限切れ: DB から取得
        String timezone = userRepository.findTimezoneById(userId).orElse(DEFAULT_TIMEZONE);
        cache.put(userId, new CacheEntry(timezone, Instant.now().plus(TTL)));
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
            CacheEntry entry = cache.get(userId);
            if (entry != null && !entry.isExpired()) {
                resolved.put(userId, entry.timezone());
            } else {
                missing.add(userId);
            }
        }

        // 2. ミス分を 1 クエリで取得しキャッシュへ載せる（未存在・論理削除済みは行が返らない）。
        if (!missing.isEmpty()) {
            Instant expiresAt = Instant.now().plus(TTL);
            for (Object[] row : userRepository.findTimezonesByIdIn(missing)) {
                Long userId = ((Number) row[0]).longValue();
                String timezone = normalize((String) row[1]);
                cache.put(userId, new CacheEntry(timezone, expiresAt));
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
     * PUT /api/auth/profile 等でタイムゾーンが更新された後に呼び出すこと。
     *
     * @param userId ユーザーID
     */
    public void evict(Long userId) {
        cache.remove(userId);
    }

    private record CacheEntry(String timezone, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
