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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * LINE Webhookイベント処理サービス。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LineWebhookService {

    /** HMAC アルゴリズム（LINE Messaging API 仕様）。 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final LineBotConfigRepository lineBotConfigRepository;
    private final LineMessageLogRepository lineMessageLogRepository;
    private final LineMessagingApiClient lineMessagingApiClient;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    /**
     * X-Line-Signature 署名検証モード（フィーチャーフラグによる段階導入）。
     *
     * <ul>
     *   <li>{@code off} — 検証を一切行わない（従来挙動）。</li>
     *   <li>{@code log-only} — 照合し、不一致なら WARN ログのみ。処理は継続する（移行期間中の既定）。</li>
     *   <li>{@code enforce} — 不一致・ヘッダ欠落なら WARN ログを出して以降の処理をスキップ（200 返却）。</li>
     * </ul>
     */
    @Value("${mannschaft.line.signature-verify.mode:log-only}")
    private String signatureVerifyMode;

    /**
     * Webhookイベントを処理する。
     */
    @Transactional
    public void handleWebhook(String webhookSecret, String signature, String requestBody) {
        LineBotConfigEntity config = lineBotConfigRepository.findByWebhookSecret(webhookSecret)
                .orElseThrow(() -> new BusinessException(LineErrorCode.LINE_003));

        if (!config.getIsActive()) {
            return;
        }

        // X-Line-Signature（channel secret の HMAC-SHA256）検証。
        // enforce モードで不一致・ヘッダ欠落の場合は、ログ保存も含めて以降の処理を
        // スキップし 200 を返却する（LINE は 200 以外を無限リトライするため 4xx は返さない）。
        if (!verifySignature(config, signature, requestBody)) {
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

    /**
     * X-Line-Signature を channel secret の HMAC-SHA256 で検証する。
     *
     * @return 後続処理（ログ保存・イベント処理）を継続してよい場合 {@code true}。
     *         enforce モードで署名不一致・ヘッダ欠落の場合のみ {@code false}。
     */
    private boolean verifySignature(LineBotConfigEntity config, String signature, String requestBody) {
        if ("off".equalsIgnoreCase(signatureVerifyMode)) {
            return true;
        }

        boolean enforce = "enforce".equalsIgnoreCase(signatureVerifyMode);

        if (signature == null || signature.isBlank()) {
            log.warn("LINE Webhook: X-Line-Signature ヘッダが欠落しています: botConfigId={}, mode={}",
                    config.getId(), signatureVerifyMode);
            // enforce では処理スキップ、log-only では継続
            return !enforce;
        }

        String expected;
        try {
            byte[] channelSecret = encryptionService.decryptBytes(config.getChannelSecretEnc());
            expected = computeSignature(channelSecret, requestBody);
        } catch (Exception e) {
            log.warn("LINE Webhook: 署名計算に失敗しました（channel secret 復号 or HMAC）: botConfigId={}",
                    config.getId(), e);
            // 計算自体に失敗した場合、enforce では安全側に倒して処理スキップ
            return !enforce;
        }

        // タイミング攻撃を避けるため定数時間比較を行う
        boolean matched = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));

        if (!matched) {
            log.warn("LINE Webhook: X-Line-Signature 署名が一致しません: botConfigId={}, mode={}",
                    config.getId(), signatureVerifyMode);
            return !enforce;
        }

        return true;
    }

    /**
     * リクエストボディの HMAC-SHA256 署名を Base64 エンコードして返す。
     *
     * @param channelSecret channel secret のバイト列（HMAC 鍵）
     * @param requestBody   生のリクエストボディ
     */
    private String computeSignature(byte[] channelSecret, String requestBody) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(channelSecret, HMAC_ALGORITHM));
        byte[] digest = mac.doFinal(requestBody.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
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
