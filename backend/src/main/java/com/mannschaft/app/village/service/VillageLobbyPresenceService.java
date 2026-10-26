package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.LobbyPresenceResponse;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 村ロビー在席インジケーターのサービス（F17.1 Phase 2）。
 *
 * <p>Valkey TTL ベースの在席管理 + STOMP ブロードキャストを担当する。</p>
 *
 * <p>キー設計:</p>
 * <ul>
 *   <li>{@code mannschaft:village:{villageId}:lobby:presence:{userId}} — TTL 90秒、value はニックネーム</li>
 *   <li>{@code mannschaft:user:{userId}:active-lobbies} — SET&lt;villageId文字列&gt;、TTL 24時間</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VillageLobbyPresenceService {

    private final StringRedisTemplate redisTemplate;
    private final UserVillageNicknameRepository nicknameRepository;
    private final VillageMembershipRepository membershipRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public static final String PRESENCE_KEY_PREFIX = "mannschaft:village:";
    public static final String PRESENCE_KEY_SUFFIX = ":lobby:presence:";
    public static final String ACTIVE_LOBBIES_KEY_PREFIX = "mannschaft:user:";
    public static final String ACTIVE_LOBBIES_KEY_SUFFIX = ":active-lobbies";
    static final long PRESENCE_TTL_SECONDS = 90L;
    static final long ACTIVE_LOBBIES_TTL_SECONDS = 86400L;

    // ========== キー生成ヘルパ ==========

    private String presenceKey(UUID villageId, Long userId) {
        return PRESENCE_KEY_PREFIX + villageId + PRESENCE_KEY_SUFFIX + userId;
    }

    private String activeLobbiesKey(Long userId) {
        return ACTIVE_LOBBIES_KEY_PREFIX + userId + ACTIVE_LOBBIES_KEY_SUFFIX;
    }

    // ========== 公開メソッド ==========

    /**
     * ロビーに参加する。村メンバーでない場合は何もしない。
     */
    public void join(UUID villageId, Long userId) {
        if (!isUserVillageMember(villageId, userId)) {
            log.debug("在席 join スキップ（非メンバー）: villageId={} userId={}", villageId, userId);
            return;
        }

        String nickname = resolveNickname(userId, villageId);
        redisTemplate.opsForValue().set(presenceKey(villageId, userId), nickname, PRESENCE_TTL_SECONDS, TimeUnit.SECONDS);
        redisTemplate.opsForSet().add(activeLobbiesKey(userId), villageId.toString());
        redisTemplate.expire(activeLobbiesKey(userId), ACTIVE_LOBBIES_TTL_SECONDS, TimeUnit.SECONDS);

        broadcast(villageId);
        log.debug("在席 join: villageId={} userId={} nickname={}", villageId, userId, nickname);
    }

    /**
     * ハートビート。キーが存在する場合のみ TTL をリセットする。
     * 頻度が高いためブロードキャストは行わない。
     */
    public void heartbeat(UUID villageId, Long userId) {
        Boolean exists = redisTemplate.hasKey(presenceKey(villageId, userId));
        if (Boolean.TRUE.equals(exists)) {
            redisTemplate.expire(presenceKey(villageId, userId), PRESENCE_TTL_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * ロビーから退室する。
     */
    public void leave(UUID villageId, Long userId) {
        redisTemplate.delete(presenceKey(villageId, userId));
        redisTemplate.opsForSet().remove(activeLobbiesKey(userId), villageId.toString());
        broadcast(villageId);
        log.debug("在席 leave: villageId={} userId={}", villageId, userId);
    }

    /**
     * 現在の在席状態を取得する（REST 用）。
     *
     * @throws BusinessException 呼び出しユーザーが村メンバーでない場合
     */
    public LobbyPresenceResponse getPresence(UUID villageId, Long actorUserId) {
        if (!isUserVillageMember(villageId, actorUserId)) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }
        return buildPresenceResponse(villageId);
    }

    /**
     * ブロードキャストの公開版（リスナーから呼び出し用）。
     */
    public void broadcastIfPresent(UUID villageId) {
        broadcast(villageId);
    }

    // ========== 内部メソッド ==========

    private void broadcast(UUID villageId) {
        LobbyPresenceResponse response = buildPresenceResponse(villageId);
        messagingTemplate.convertAndSend("/topic/villages/" + villageId + "/lobby/presence", response);
    }

    private LobbyPresenceResponse buildPresenceResponse(UUID villageId) {
        String pattern = PRESENCE_KEY_PREFIX + villageId + PRESENCE_KEY_SUFFIX + "*";
        Set<String> keys = redisTemplate.keys(pattern);

        if (keys == null || keys.isEmpty()) {
            return LobbyPresenceResponse.of(List.of());
        }

        List<LobbyPresenceResponse.PresenceMember> members = new ArrayList<>();
        for (String key : keys) {
            // キー末尾の userId を抽出
            String suffix = PRESENCE_KEY_PREFIX + villageId + PRESENCE_KEY_SUFFIX;
            String userIdStr = key.substring(suffix.length());
            try {
                Long userId = Long.parseLong(userIdStr);
                String nickname = redisTemplate.opsForValue().get(key);
                if (nickname != null) {
                    members.add(new LobbyPresenceResponse.PresenceMember(userId, nickname));
                }
            } catch (NumberFormatException e) {
                log.warn("在席キーから userId を抽出できませんでした: key={}", key);
            }
        }

        return LobbyPresenceResponse.of(members);
    }

    private String resolveNickname(Long userId, UUID villageId) {
        // 村固有ニックネーム → 全村共通ニックネーム → 空文字の順でフォールバック
        return nicknameRepository.findByUserIdAndVillageId(userId, villageId)
                .or(() -> nicknameRepository.findByUserIdAndVillageIdIsNull(userId))
                .map(n -> n.getNickname())
                .orElse("");
    }

    private boolean isUserVillageMember(UUID villageId, Long userId) {
        return membershipRepository
                .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        villageId, VillageSubjectType.USER, userId)
                .filter(m -> m.getBannedAt() == null)
                .isPresent();
    }
}
