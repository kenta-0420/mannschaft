package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 組織告知一覧の「続きの位置」を<b>サーバ側だけ</b>で保持する継続トークンのストア（CMP-261011-0035。read-all の
 * {@link AnnouncementReadAllCursorStore} と同じ方針: 走査位置はサーバだけが持つ）。
 *
 * <p>一覧の {@code nextCursor} は告知の実在 ID ではなく、ここで発行する乱数のトークン（{@code Long}、1 以上 2^53−1 以下で
 * JavaScript の安全整数に収まる）にする。告知 ID をカーソルにすると、読めない告知（宛先外・課金 HIDDEN）の実在 ID が
 * 空ページの継続位置として外へ出たり、任意の ID を渡して応答の違いから存在を判別されたりするため。</p>
 *
 * <ul>
 *   <li>キー: {@code announcement:org-feed:cursor:{userId}:{orgId}:{token}}（利用者×組織に結び付く。他人・他組織のトークンは引けない）</li>
 *   <li>値: 発行時点の最後の走査行の {@code pinned|createdAt|feedId}。TTL 30 分。読んでも消さない（同じ要求の再送を許す）</li>
 *   <li>不明・期限切れ・他人・他組織・形式不正のトークンはすべて「無効」（呼び出し側が同一の 400 にする）</li>
 *   <li>Valkey の障害は<b>握りつぶさない</b>。WARN を残し、発行・読み出しとも {@link AnnouncementErrorCode#ANNOUNCE_011}（503）で
 *       失敗させる（続きを黙って隠さない）</li>
 * </ul>
 *
 * <p>トランザクションを持たない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrgAnnouncementFeedCursorStore {

    static final Duration TTL = Duration.ofMinutes(30);

    /** JavaScript の Number.MAX_SAFE_INTEGER。 */
    static final long MAX_TOKEN = (1L << 53) - 1;

    private static final int MAX_ISSUE_ATTEMPTS = 5;

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom random = new SecureRandom();

    /**
     * 位置を保存して継続トークンを発行する。
     *
     * @throws BusinessException Valkey に保存できないとき（{@link AnnouncementErrorCode#ANNOUNCE_011}）
     */
    long issue(Long userId, Long orgId, AnnouncementFeedQueryRepository.OrgFeedPosition position) {
        String value = (position.pinned() ? "1" : "0") + "|" + position.createdAt() + "|" + position.feedId();
        try {
            for (int attempt = 0; attempt < MAX_ISSUE_ATTEMPTS; attempt++) {
                long token = 1 + random.nextLong(MAX_TOKEN);
                Boolean stored = redisTemplate.opsForValue().setIfAbsent(key(userId, orgId, token), value, TTL);
                if (Boolean.TRUE.equals(stored)) {
                    return token;
                }
            }
            log.warn("組織告知一覧の継続トークンを発行できません（衝突が続きました）orgId={}", orgId);
        } catch (DataAccessException | SerializationException e) {
            log.warn("組織告知一覧の継続トークンを保存できません orgId={}", orgId, e);
        }
        throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_011);
    }

    /**
     * 継続トークンから位置を引く。無効なトークン（不明・期限切れ・他人・他組織・壊れた値）は empty。
     *
     * @throws BusinessException Valkey から読めないとき（{@link AnnouncementErrorCode#ANNOUNCE_011}）
     */
    Optional<AnnouncementFeedQueryRepository.OrgFeedPosition> resolve(Long userId, Long orgId, long token) {
        if (token < 1 || token > MAX_TOKEN) {
            return Optional.empty();
        }
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(key(userId, orgId, token));
        } catch (DataAccessException | SerializationException e) {
            log.warn("組織告知一覧の継続トークンを読めません orgId={}", orgId, e);
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_011);
        }
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String[] parts = raw.split("\\|", 3);
        try {
            if (parts.length != 3) {
                throw new IllegalArgumentException("bad cursor value");
            }
            return Optional.of(new AnnouncementFeedQueryRepository.OrgFeedPosition(
                    "1".equals(parts[0]), LocalDateTime.parse(parts[1]), Long.parseLong(parts[2])));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            log.warn("組織告知一覧の継続トークンの値が壊れています orgId={}", orgId, e);
            return Optional.empty();
        }
    }

    static String key(Long userId, Long orgId, long token) {
        return "announcement:org-feed:cursor:" + userId + ":" + orgId + ":" + token;
    }
}
