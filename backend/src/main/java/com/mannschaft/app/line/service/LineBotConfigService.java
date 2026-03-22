package com.mannschaft.app.line.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.line.LineErrorCode;
import com.mannschaft.app.line.LineMapper;
import com.mannschaft.app.line.LineMessageType;
import com.mannschaft.app.line.MessageDirection;
import com.mannschaft.app.line.ScopeType;
import com.mannschaft.app.line.dto.CreateLineBotConfigRequest;
import com.mannschaft.app.line.dto.LineBotConfigResponse;
import com.mannschaft.app.line.dto.LineMessageLogResponse;
import com.mannschaft.app.line.dto.TestMessageRequest;
import com.mannschaft.app.line.dto.UpdateLineBotConfigRequest;
import com.mannschaft.app.line.entity.LineBotConfigEntity;
import com.mannschaft.app.line.entity.LineMessageLogEntity;
import com.mannschaft.app.line.repository.LineBotConfigRepository;
import com.mannschaft.app.line.repository.LineMessageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LINE BOT設定サービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LineBotConfigService {

    private final LineBotConfigRepository lineBotConfigRepository;
    private final LineMessageLogRepository lineMessageLogRepository;
    private final LineMapper lineMapper;

    /**
     * BOT設定を取得する。
     */
    public LineBotConfigResponse getConfig(ScopeType scopeType, Long scopeId) {
        LineBotConfigEntity entity = findByScope(scopeType, scopeId);
        return lineMapper.toLineBotConfigResponse(entity);
    }

    /**
     * BOT設定を作成する。
     */
    @Transactional
    public LineBotConfigResponse create(ScopeType scopeType, Long scopeId, Long userId,
                                         CreateLineBotConfigRequest request) {
        if (lineBotConfigRepository.existsByScopeTypeAndScopeId(scopeType, scopeId)) {
            throw new BusinessException(LineErrorCode.LINE_002);
        }

        LineBotConfigEntity entity = LineBotConfigEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .channelId(request.getChannelId())
                .channelSecretEnc(encrypt(request.getChannelSecret()))
                .channelAccessTokenEnc(encrypt(request.getChannelAccessToken()))
                .webhookSecret(request.getWebhookSecret())
                .botUserId(request.getBotUserId())
                .notificationEnabled(request.getNotificationEnabled() != null
                        ? request.getNotificationEnabled() : true)
                .configuredBy(userId)
                .build();

        LineBotConfigEntity saved = lineBotConfigRepository.save(entity);
        return lineMapper.toLineBotConfigResponse(saved);
    }

    /**
     * BOT設定を更新する。
     */
    @Transactional
    public LineBotConfigResponse update(ScopeType scopeType, Long scopeId,
                                         UpdateLineBotConfigRequest request) {
        LineBotConfigEntity entity = findByScope(scopeType, scopeId);
        entity.update(
                request.getChannelId(),
                encrypt(request.getChannelSecret()),
                encrypt(request.getChannelAccessToken()),
                request.getWebhookSecret(),
                request.getBotUserId(),
                request.getNotificationEnabled() != null
                        ? request.getNotificationEnabled() : entity.getNotificationEnabled()
        );
        return lineMapper.toLineBotConfigResponse(entity);
    }

    /**
     * BOT設定を論理削除する。
     */
    @Transactional
    public void delete(ScopeType scopeType, Long scopeId) {
        LineBotConfigEntity entity = findByScope(scopeType, scopeId);
        entity.softDelete();
    }

    /**
     * テストメッセージを送信する（ログ記録のみ、実際のLINE API呼び出しは将来実装）。
     */
    @Transactional
    public void sendTestMessage(ScopeType scopeType, Long scopeId, TestMessageRequest request) {
        LineBotConfigEntity config = findByScope(scopeType, scopeId);

        LineMessageLogEntity log = LineMessageLogEntity.builder()
                .lineBotConfigId(config.getId())
                .direction(MessageDirection.OUTBOUND)
                .messageType(LineMessageType.TEXT)
                .lineUserId(request.getLineUserId())
                .contentSummary(request.getMessage())
                .build();

        // TODO: LINE Messaging API 呼び出し実装
        log.markSent(null);
        lineMessageLogRepository.save(log);
    }

    /**
     * メッセージ履歴を取得する。
     */
    public Page<LineMessageLogResponse> getMessageLogs(ScopeType scopeType, Long scopeId,
                                                        Pageable pageable) {
        LineBotConfigEntity config = findByScope(scopeType, scopeId);
        return lineMessageLogRepository
                .findByLineBotConfigIdOrderByCreatedAtDesc(config.getId(), pageable)
                .map(lineMapper::toLineMessageLogResponse);
    }

    private LineBotConfigEntity findByScope(ScopeType scopeType, Long scopeId) {
        return lineBotConfigRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(LineErrorCode.LINE_001));
    }

    /**
     * 暗号化処理（将来AES-256-GCM実装予定、現時点ではプレースホルダー）。
     */
    private byte[] encrypt(String plainText) {
        // TODO: AES-256-GCM 暗号化実装
        return plainText != null ? plainText.getBytes() : new byte[0];
    }
}
