package com.mannschaft.app.signage.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * デジタルサイネージ WebSocket 配信コンポーネント。
 * STOMP ブローカー経由で接続中サイネージクライアントへメッセージを即時配信する。
 */
@Component
@RequiredArgsConstructor
public class SignageWebSocketPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 指定画面の全接続クライアントへ緊急メッセージを配信する。
     * トピック: /topic/signage/{screenId}/emergency
     *
     * @param screenId 画面ID
     * @param message  緊急メッセージ本文
     */
    public void publishEmergency(Long screenId, String message) {
        String destination = "/topic/signage/" + screenId + "/emergency";
        Map<String, Object> payload = Map.of(
                "message", message,
                "timestamp", Instant.now()
        );
        messagingTemplate.convertAndSend(destination, payload);
    }

    /**
     * 指定画面の全接続クライアントへスライド更新通知を配信する。
     * トピック: /topic/signage/{screenId}/update
     *
     * @param screenId 画面ID
     */
    public void publishSlideUpdate(Long screenId) {
        String destination = "/topic/signage/" + screenId + "/update";
        Map<String, Object> payload = Map.of(
                "type", "SLIDE_UPDATE",
                "timestamp", Instant.now()
        );
        messagingTemplate.convertAndSend(destination, payload);
    }
}
