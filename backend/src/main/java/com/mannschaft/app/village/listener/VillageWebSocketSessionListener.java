package com.mannschaft.app.village.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.village.service.VillageLobbyPresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * WebSocket セッション切断リスナー（F17.1 Phase 2）。
 *
 * <p>クライアントが正常・異常どちらの方法で切断しても、
 * Valkey 上の在席キーをクリーンアップして在席ブロードキャストを発火する。
 * TTL（90秒）による自然消滅の補完として機能する。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VillageWebSocketSessionListener {

    private final VillageLobbyPresenceService presenceService;
    private final StringRedisTemplate redisTemplate;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。WebSocket 切断時の村の在席状態の後始末であり、接続の生存期間に閉じる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs == null) return;

        Object userIdObj = attrs.get("userId");
        if (!(userIdObj instanceof Long userId)) return;

        // このユーザーが在席中の全村ロビーからクリーンアップ
        String activeLobbiesKey = VillageLobbyPresenceService.ACTIVE_LOBBIES_KEY_PREFIX
                + userId
                + VillageLobbyPresenceService.ACTIVE_LOBBIES_KEY_SUFFIX;

        Set<String> villageIds = redisTemplate.opsForSet().members(activeLobbiesKey);
        if (villageIds == null || villageIds.isEmpty()) return;

        for (String villageIdStr : villageIds) {
            try {
                UUID villageId = UUID.fromString(villageIdStr);
                presenceService.leave(villageId, userId);
            } catch (IllegalArgumentException e) {
                log.warn("active-lobbies に不正な villageId が含まれています: userId={} value={}", userId, villageIdStr);
            }
        }

        redisTemplate.delete(activeLobbiesKey);
        log.debug("WebSocket 切断クリーンアップ完了: userId={} villageCount={}", userId, villageIds.size());
    }
}
