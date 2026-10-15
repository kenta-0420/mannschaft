package com.mannschaft.app.chat.controller;

import com.mannschaft.app.chat.dto.SendMessageRequest;
import com.mannschaft.app.chat.dto.WsSendMessageRequest;
import com.mannschaft.app.chat.service.ChatMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

/**
 * F04.2: WebSocket STOMP 経由のチャットメッセージ操作を受け付ける。
 * <p>
 * REST API とは別に、STOMP プロトコルでメッセージ送信を行うエンドポイントを提供する。
 * 配信は {@link com.mannschaft.app.chat.service.ChatMessagePublisher} が担う。
 * </p>
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final ChatMessageService chatMessageService;

    /**
     * WebSocket 経由でメッセージを送信する。
     * <p>
     * クライアントは {@code /app/chat.send} に SEND フレームを送信する。
     * 認証セッション属性から userId を取得するため、HandshakeInterceptor での設定が必要。
     * userId が設定されていない場合（未認証）は黙って処理をスキップする。
     * </p>
     *
     * @param request  WebSocket 送信リクエスト
     * @param accessor STOMP メッセージヘッダーアクセサ（セッション属性取得用）
     */
    @MessageMapping("/chat.send")
    public void handleSendMessage(WsSendMessageRequest request, SimpMessageHeaderAccessor accessor) {
        Long userId = (Long) accessor.getSessionAttributes().get("userId");
        if (userId == null) {
            log.warn("WebSocket メッセージ送信: userId が未設定のため処理をスキップ (channelId={})", request.getChannelId());
            return;
        }

        SendMessageRequest sendRequest = new SendMessageRequest(
                request.getBody(),
                request.getParentId(),
                null,
                null
        );

        chatMessageService.sendMessage(request.getChannelId(), sendRequest, userId);
        log.debug("WebSocket メッセージ送信完了: channelId={}, userId={}", request.getChannelId(), userId);
    }
}
