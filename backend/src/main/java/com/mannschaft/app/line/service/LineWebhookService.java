package com.mannschaft.app.line.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.line.LineErrorCode;
import com.mannschaft.app.line.LineMessageType;
import com.mannschaft.app.line.MessageDirection;
import com.mannschaft.app.line.entity.LineBotConfigEntity;
import com.mannschaft.app.line.entity.LineMessageLogEntity;
import com.mannschaft.app.line.repository.LineBotConfigRepository;
import com.mannschaft.app.line.repository.LineMessageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LINE Webhookイベント処理サービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LineWebhookService {

    private final LineBotConfigRepository lineBotConfigRepository;
    private final LineMessageLogRepository lineMessageLogRepository;

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

        // TODO: イベント種別に応じた処理（メッセージ応答、フォロー/アンフォロー等）
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
