package com.mannschaft.app.chat.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * WebSocket STOMP 経由のメッセージ送信リクエスト。
 * <p>
 * {@code @MessageMapping("/chat.send")} ハンドラで受け取る。
 * </p>
 */
@Getter
@Setter
@NoArgsConstructor
public class WsSendMessageRequest {

    /** 送信先チャンネルID */
    private Long channelId;

    /** メッセージ本文 */
    private String body;

    /** 返信先メッセージID（スレッド返信の場合のみ設定） */
    private Long parentId;
}
