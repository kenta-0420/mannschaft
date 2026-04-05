package com.mannschaft.app.line.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.line.LineErrorCode;
import com.mannschaft.app.line.LineMessageType;
import com.mannschaft.app.line.MessageDirection;
import com.mannschaft.app.line.entity.LineBotConfigEntity;
import com.mannschaft.app.line.entity.LineMessageLogEntity;
import com.mannschaft.app.line.repository.LineBotConfigRepository;
import com.mannschaft.app.line.repository.LineMessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LINE Webhookイベント処理サービス。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LineWebhookService {

    private final LineBotConfigRepository lineBotConfigRepository;
    private final LineMessageLogRepository lineMessageLogRepository;
    private final LineMessagingApiClient lineMessagingApiClient;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    /**
     * Webhookイベントを処理する。
     */
    @Transactional
    public void handleWebhook(String webhookSecret, String requestBody) {
        LineBotConfigEntity config = lineBotConfigRepository.findByWebhookSecret(webhookSecret)
                .orElseThrow(() -> new BusinessException(LineErrorCode.LINE_003));

        if (!config.getIsActive()) {
            return;
        }

        // Webhookイベントをログに記録
        LineMessageLogEntity log = LineMessageLogEntity.builder()
                .lineBotConfigId(config.getId())
                .direction(MessageDirection.INBOUND)
                .messageType(LineMessageType.WEBHOOK_EVENT)
                .contentSummary(truncate(requestBody, 500))
                .build();
        log.updateStatus(com.mannschaft.app.line.MessageStatus.DELIVERED);

        lineMessageLogRepository.save(log);

        // イベント種別に応じた処理
        processEvents(config, requestBody);
    }

    private void processEvents(LineBotConfigEntity config, String requestBody) {
        try {
            JsonNode root = objectMapper.readTree(requestBody);
            JsonNode events = root.get("events");
            if (events == null || !events.isArray()) {
                return;
            }

            String channelAccessToken = new String(
                    encryptionService.decryptBytes(config.getChannelAccessTokenEnc()));

            for (JsonNode event : events) {
                String type = event.has("type") ? event.get("type").asText() : "";
                String userId = event.has("source") && event.get("source").has("userId")
                        ? event.get("source").get("userId").asText() : null;

                switch (type) {
                    case "message" -> handleMessageEvent(config, channelAccessToken, event, userId);
                    case "follow" -> log.info("LINEフォローイベント: botConfigId={}, userId={}",
                            config.getId(), userId);
                    case "unfollow" -> log.info("LINEアンフォローイベント: botConfigId={}, userId={}",
                            config.getId(), userId);
                    case "postback" -> log.info("LINEポストバックイベント: botConfigId={}, data={}",
                            config.getId(), event.has("postback") ? event.get("postback").get("data").asText() : "");
                    default -> log.debug("未対応のLINEイベント種別: type={}", type);
                }
            }
        } catch (Exception e) {
            log.warn("Webhookイベントの処理中にエラー: botConfigId={}", config.getId(), e);
        }
    }

    private void handleMessageEvent(LineBotConfigEntity config, String channelAccessToken,
                                     JsonNode event, String userId) {
        String replyToken = event.has("replyToken") ? event.get("replyToken").asText() : null;
        JsonNode message = event.get("message");
        String messageText = message != null && message.has("text") ? message.get("text").asText() : "";

        // メッセージ受信ログ
        LineMessageLogEntity inboundLog = LineMessageLogEntity.builder()
                .lineBotConfigId(config.getId())
                .direction(MessageDirection.INBOUND)
                .messageType(LineMessageType.TEXT)
                .lineUserId(userId)
                .contentSummary(truncate(messageText, 500))
                .build();
        inboundLog.updateStatus(com.mannschaft.app.line.MessageStatus.DELIVERED);
        lineMessageLogRepository.save(inboundLog);

        // 自動応答（有効な場合）
        if (replyToken != null && config.getNotificationEnabled()) {
            lineMessagingApiClient.replyMessage(channelAccessToken, replyToken,
                    "メッセージを受け付けました。");
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
