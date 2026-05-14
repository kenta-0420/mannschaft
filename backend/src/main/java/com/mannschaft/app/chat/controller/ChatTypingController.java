package com.mannschaft.app.chat.controller;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.chat.dto.ChatMessageBroadcast;
import com.mannschaft.app.chat.dto.TypingPayload;
import com.mannschaft.app.chat.dto.WsTypingRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * タイピングインジケーター WebSocket コントローラー。
 *
 * <p>クライアントが {@code /app/chat.typing} に SEND すると、
 * 対象チャンネルの購読者全員に {@code /topic/channels/{channelId}} 経由で
 * タイピング中ユーザー情報をブロードキャストする。</p>
 *
 * <p>セッション属性の {@code userId} が null（未認証）の場合は無視する。
 * ユーザー表示名は {@link UserRepository} から取得し、
 * ユーザーが見つからない場合は空文字列を使用する。</p>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatTypingController {

    private static final String CHANNEL_TOPIC_PREFIX = "/topic/channels/";
    private static final String EVENT_TYPE_TYPING = "TYPING";

    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;

    /**
     * タイピングインジケーターを受信し、チャンネル購読者全員にブロードキャストする。
     *
     * @param request  タイピングリクエスト（channelId を含む）
     * @param headerAccessor STOMP セッション属性からユーザーIDを取得するためのアクセサ
     */
    @MessageMapping("/chat.typing")
    public void handleTyping(WsTypingRequest request, SimpMessageHeaderAccessor headerAccessor) {
        Object rawUserId = headerAccessor.getSessionAttributes() != null
                ? headerAccessor.getSessionAttributes().get("userId")
                : null;

        if (rawUserId == null) {
            log.debug("タイピングインジケーター: userId が未設定（未認証）のため無視する");
            return;
        }

        Long userId = (Long) rawUserId;
        Long channelId = request.channelId();

        if (channelId == null) {
            log.debug("タイピングインジケーター: channelId が null のため無視する");
            return;
        }

        String displayName = userRepository.findById(userId)
                .map(user -> user.getDisplayName())
                .orElse("");

        TypingPayload payload = new TypingPayload(userId, displayName);
        ChatMessageBroadcast broadcast = new ChatMessageBroadcast(EVENT_TYPE_TYPING, payload);

        String destination = CHANNEL_TOPIC_PREFIX + channelId;
        messagingTemplate.convertAndSend(destination, broadcast);
        log.debug("タイピングインジケーター配信: userId={}, channelId={}, destination={}", userId, channelId, destination);
    }
}
