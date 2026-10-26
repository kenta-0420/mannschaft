package com.mannschaft.app.line.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
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
 *
 * <p>認可（認可根治戦役 Wave2 トランシェ2C）: BOT 設定は webhookSecret・チャネル資格情報を扱う
 * 管理機能のため、閲覧・変更とも {@code checkAdminOrAbove} で保護する（先行 #2259 webhook ドメインの
 * トークン管理と同方針）。BOT 設定はスコープと 1:1 で path に entity ID を持たないため、
 * path scope での認可＝entity 由来 scope での認可となり、ID ベースの BOLA は構造上存在しない。
 * 設定不在は LINE_001（GlobalExceptionHandler で 404 マッピング）。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LineBotConfigService {

    private final LineBotConfigRepository lineBotConfigRepository;
    private final LineMessageLogRepository lineMessageLogRepository;
    private final LineMapper lineMapper;
    private final EncryptionService encryptionService;
    private final LineMessagingApiClient lineMessagingApiClient;
    private final AccessControlService accessControlService;

    /**
     * BOT設定を取得する。
     *
     * <p>webhookSecret を含むためADMIN/DEPUTY_ADMINのみ閲覧可（応答内のwebhookSecretは
     * {@link LineMapper#maskSecret(String)} でprefixマスクされる）。</p>
     *
     * @param actorUserId 操作者ユーザーID（認可検証用）
     */
    public LineBotConfigResponse getConfig(ScopeType scopeType, Long scopeId, Long actorUserId) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType.name());
        LineBotConfigEntity entity = findByScope(scopeType, scopeId);
        return lineMapper.toLineBotConfigResponse(entity);
    }

    /**
     * BOT設定を作成する。
     */
    @Transactional
    public LineBotConfigResponse create(ScopeType scopeType, Long scopeId, Long userId,
                                         CreateLineBotConfigRequest request) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());
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
     *
     * @param actorUserId 操作者ユーザーID（認可検証用）
     */
    @Transactional
    public LineBotConfigResponse update(ScopeType scopeType, Long scopeId, Long actorUserId,
                                         UpdateLineBotConfigRequest request) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType.name());
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
     *
     * @param actorUserId 操作者ユーザーID（認可検証用）
     */
    @Transactional
    public void delete(ScopeType scopeType, Long scopeId, Long actorUserId) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType.name());
        LineBotConfigEntity entity = findByScope(scopeType, scopeId);
        entity.softDelete();
    }

    /**
     * テストメッセージを送信する（ログ記録のみ、実際のLINE API呼び出しは将来実装）。
     *
     * @param actorUserId 操作者ユーザーID（認可検証用）
     */
    @Transactional
    public void sendTestMessage(ScopeType scopeType, Long scopeId, Long actorUserId,
                                TestMessageRequest request) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType.name());
        LineBotConfigEntity config = findByScope(scopeType, scopeId);

        LineMessageLogEntity log = LineMessageLogEntity.builder()
                .lineBotConfigId(config.getId())
                .direction(MessageDirection.OUTBOUND)
                .messageType(LineMessageType.TEXT)
                .lineUserId(request.getLineUserId())
                .contentSummary(request.getMessage())
                .build();

        String channelAccessToken = new String(
                encryptionService.decryptBytes(config.getChannelAccessTokenEnc()));
        String requestId = lineMessagingApiClient.pushMessage(
                channelAccessToken, request.getLineUserId(), request.getMessage());
        log.markSent(requestId);
        lineMessageLogRepository.save(log);
    }

    /**
     * メッセージ履歴を取得する。
     *
     * <p>LINE 利用者 ID・メッセージ本文要約を含むためADMIN/DEPUTY_ADMINのみ閲覧可。</p>
     *
     * @param actorUserId 操作者ユーザーID（認可検証用）
     */
    public Page<LineMessageLogResponse> getMessageLogs(ScopeType scopeType, Long scopeId,
                                                        Long actorUserId, Pageable pageable) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType.name());
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
     * 文字列をAES-256-GCMで暗号化し、バイト列を返す。
     */
    private byte[] encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        return encryptionService.encryptBytes(
                plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
