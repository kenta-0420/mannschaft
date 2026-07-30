package com.mannschaft.app.common.i18n;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.cache.BoundedTtlCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * ユーザーの locale をキャッシュするサービス。
 * リクエストごとに DB クエリが発生しないよう TTL 5分のインメモリキャッシュを提供する。
 *
 * <p><b>常駐メモリの上限（Issue #2487 項目 1）</b>: 素の {@code ConcurrentHashMap} は件数上限も期限切れ回収も
 * 持たず、「これまでに一度でも見た全ユーザー数」に単調増加していた。姉妹キャッシュである
 * {@code UserTimezoneCache} と<b>同一方式</b>で {@link BoundedTtlCache}（件数上限つき LRU ＋
 * 参照時の期限切れ回収）に置き換えてある。上限は {@code mannschaft.cache.user-locale.max-entries} で変更できる。</p>
 */
@Service
public class UserLocaleCache {

    private final UserRepository userRepository;

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String DEFAULT_LOCALE = "ja";

    /** userId → locale（件数上限つき LRU・TTL 5分）。 */
    private final BoundedTtlCache<Long, String> cache;

    /**
     * @param userRepository ユーザーリポジトリ
     * @param maxEntries     常駐させる最大エントリ数（{@code mannschaft.cache.user-locale.max-entries}）。
     *                       既定 50,000 の根拠は {@code UserTimezoneCache} と同一（1 エントリ約 200B ＝
     *                       50,000 件で約 10MB。リクエスト駆動の hot set を丸ごと収容できる規模）。
     *                       姉妹キャッシュと方式・既定値を揃え、片方だけ別挙動にしない。
     */
    public UserLocaleCache(
            UserRepository userRepository,
            @Value("${mannschaft.cache.user-locale.max-entries:50000}") int maxEntries) {
        this.userRepository = userRepository;
        this.cache = new BoundedTtlCache<>(maxEntries, TTL);
    }

    /**
     * userId に対応する locale を返す。
     * キャッシュヒット（かつ有効期限内）の場合はキャッシュを返す。
     * キャッシュミスまたは期限切れの場合は DB から取得してキャッシュする。
     * DB に locale が存在しない（null）場合は "ja" を返す。
     *
     * @param userId ユーザーID
     * @return locale 文字列（例: "ja"）
     */
    public String getLocale(Long userId) {
        String cached = cache.get(userId);
        if (cached != null) {
            return cached;
        }
        // キャッシュミス or 期限切れ: DB から取得
        String locale = userRepository.findLocaleById(userId).orElse(DEFAULT_LOCALE);
        cache.put(userId, locale);
        return locale;
    }

    /**
     * locale 変更時にキャッシュを即時削除する。
     * PUT /api/auth/profile 成功後に呼び出すこと（{@code UserService#updateProfile} が唯一の呼び出し元）。
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
