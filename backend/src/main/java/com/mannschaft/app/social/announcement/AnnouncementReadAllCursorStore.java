package com.mannschaft.app.social.announcement;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * read-all の走査位置を<b>サーバ側だけ</b>で保持する小さなストア（ALIC-1 r6）。
 *
 * <p>read-all は 1 リクエストで走査する範囲に上限（500 件 × 20 チャンク）がある。上限までに課金 HIDDEN が並び続けて
 * 走査が終わらなかったとき、次の read-all を同じ位置から再開できるよう、直前の要求で取得した（課金で除外する前の）
 * 最後の feed ID を Valkey に保存する。API の応答・一覧・ヘッダーには位置を出さない（HIDDEN の告知の実在 ID を
 * 外へ出さないため）。クライアントは位置を見ることも渡すこともできない。</p>
 *
 * <ul>
 *   <li>キー: {@code announcement:read-all:cursor:{userId}:{scopeType}:{scopeId}}（利用者×スコープで分かれる）</li>
 *   <li>値: feed ID の 10 進文字列、TTL 15 分。走査を最後まで終えたらキーを消す</li>
 *   <li>Valkey の読み書きが失敗したら WARN を出して<b>先頭から走査</b>する（結果は正しく、進み方が遅れるだけ。
 *       保存の失敗で要求そのものは失敗させない）。位置は出発点を進めるだけで、読める範囲を広げない</li>
 * </ul>
 *
 * <p>トランザクションを持たない（呼び出し元の {@link AnnouncementReadCommandService} も持たない）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnnouncementReadAllCursorStore {

    static final Duration TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;

    /** 保存された走査位置（直前の要求で取得した最後の feed ID）。無い・読めない・壊れているときは null（先頭から）。 */
    Long load(Long userId, AnnouncementScopeType scopeType, Long scopeId) {
        String key = key(userId, scopeType, scopeId);
        try {
            String raw = redisTemplate.opsForValue().get(key);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            long id = Long.parseLong(raw.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            log.warn("read-all の走査位置が壊れているため先頭から走査します key={}", key, e);
            return null;
        } catch (RuntimeException e) {
            // Valkey の障害（接続断・タイムアウト・シリアライズ失敗など）。位置は進み方を速めるだけの補助なので、
            // 結果の正しさを保つ先頭からの走査に倒す。握りつぶさず WARN を残す。
            log.warn("read-all の走査位置を読めないため先頭から走査します key={}", key, e);
            return null;
        }
    }

    /** 走査位置を保存する（TTL 15 分）。失敗しても要求は失敗させず WARN を残す。 */
    void save(Long userId, AnnouncementScopeType scopeType, Long scopeId, long lastFetchedFeedId) {
        String key = key(userId, scopeType, scopeId);
        try {
            redisTemplate.opsForValue().set(key, Long.toString(lastFetchedFeedId), TTL);
        } catch (RuntimeException e) {
            log.warn("read-all の走査位置を保存できません（次回は先頭から走査します）key={}", key, e);
        }
    }

    /** 走査位置を消す（走査を最後まで終えたとき）。失敗しても要求は失敗させず WARN を残す。 */
    void clear(Long userId, AnnouncementScopeType scopeType, Long scopeId) {
        String key = key(userId, scopeType, scopeId);
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException e) {
            log.warn("read-all の走査位置を消せません（TTL で失効します）key={}", key, e);
        }
    }

    static String key(Long userId, AnnouncementScopeType scopeType, Long scopeId) {
        return "announcement:read-all:cursor:" + userId + ":" + scopeType.name() + ":" + scopeId;
    }
}
