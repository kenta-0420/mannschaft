package com.mannschaft.app.common.i18n;

import com.mannschaft.app.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ユーザーの locale をキャッシュするサービス。
 * リクエストごとに DB クエリが発生しないよう TTL 5分のインメモリキャッシュを提供する。
 */
@Service
@RequiredArgsConstructor
public class UserLocaleCache {

    private final UserRepository userRepository;

    private static final Duration TTL = Duration.ofMinutes(5);

    /** userId → キャッシュエントリ */
    private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * userId に対応する locale を返す。
     * キャッシュヒット（かつ有効期限内）の場合はキャッシュを返す。
     * キャッシュミスまたは期限切れの場合は DB から取得してキャッシュする。
     * DB に locale が存在しない（null）場合は "ja" を返す。
     */
    public String getLocale(Long userId) {
        CacheEntry entry = cache.get(userId);
        if (entry != null && !entry.isExpired()) {
            return entry.locale();
        }
        // キャッシュミス or 期限切れ: DB から取得
        String locale = userRepository.findLocaleById(userId).orElse("ja");
        cache.put(userId, new CacheEntry(locale, Instant.now().plus(TTL)));
        return locale;
    }

    /**
     * locale 変更時にキャッシュを即時削除する。
     * PUT /api/auth/profile 成功後に呼び出すこと。
     */
    public void evict(Long userId) {
        cache.remove(userId);
    }

    private record CacheEntry(String locale, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
