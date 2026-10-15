package com.mannschaft.app.line.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.line.LineErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * LINE Messaging API クライアント。
 * Push Message / Reply Message の送信を担当する。
 */
@Slf4j
@Component
public class LineMessagingApiClient {

    private static final String PUSH_URL = "https://api.line.me/v2/bot/message/push";
    private static final String REPLY_URL = "https://api.line.me/v2/bot/message/reply";

    private final RestClient restClient;

    public LineMessagingApiClient() {
        this.restClient = RestClient.create();
    }

    /**
     * Push Message を送信する。
     *
     * @param channelAccessToken チャネルアクセストークン
     * @param to                 送信先ユーザーID
     * @param text               メッセージテキスト
     * @return LINE API のレスポンスから取得した requestId（x-line-request-id ヘッダー）
     */
    public String pushMessage(String channelAccessToken, String to, String text) {
        Map<String, Object> body = Map.of(
                "to", to,
                "messages", List.of(Map.of("type", "text", "text", text))
        );
        try {
            var response = restClient.post()
                    .uri(PUSH_URL)
                    .header("Authorization", "Bearer " + channelAccessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            String requestId = response.getHeaders().getFirst("x-line-request-id");
            log.debug("LINE Push Message送信完了: to={}, requestId={}", to, requestId);
            return requestId;
        } catch (Exception e) {
            log.error("LINE Push Message送信失敗: to={}", to, e);
            throw new BusinessException(LineErrorCode.LINE_004, e);
        }
    }

    /**
     * Reply Message を送信する。
     *
     * @param channelAccessToken チャネルアクセストークン
     * @param replyToken         リプライトークン
     * @param text               メッセージテキスト
     */
    public void replyMessage(String channelAccessToken, String replyToken, String text) {
        Map<String, Object> body = Map.of(
                "replyToken", replyToken,
                "messages", List.of(Map.of("type", "text", "text", text))
        );
        try {
            restClient.post()
                    .uri(REPLY_URL)
                    .header("Authorization", "Bearer " + channelAccessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("LINE Reply Message送信完了");
        } catch (Exception e) {
            log.warn("LINE Reply Message送信失敗（リプライトークン期限切れの可能性）", e);
        }
    }
}
