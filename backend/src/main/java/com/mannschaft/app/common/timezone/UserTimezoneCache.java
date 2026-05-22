package com.mannschaft.app.common.timezone;

import com.mannschaft.app.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ユーザーの timezone をキャッシュするサービス。
 *
 * <p>{@link UserLocaleCache} と同じパターン。
 * リクエストごとに DB クエリが発生しないよう TTL 5分のインメモリキャッシュを提供する。
 * UserRepository の {@code findTimezoneById} は既存メソッドを流用する（追加不要）。</p>
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
