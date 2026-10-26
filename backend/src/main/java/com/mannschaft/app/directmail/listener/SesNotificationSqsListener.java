package com.mannschaft.app.directmail.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.directmail.dto.SesNotificationRequest;
import com.mannschaft.app.directmail.service.SesWebhookService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * SES バウンス/苦情/開封通知を SQS から受信するリスナー（F09.6 Phase 8a）。
 *
 * <p>経路: AWS SES → SNS Topic → SQS Queue → 本リスナー。
 * 旧来の HTTP webhook（{@code POST /api/v1/webhooks/ses}）を廃止し、SQS 内部認証
 * （AWS SigV4 + キューアクセスポリシーで送信元 SNS Topic を限定）に切り替えた。
 * これにより偽造バウンス注入・SubscribeURL の SSRF を構造的に排除し、
 * SNS 署名検証コードを不要にする（設計書 F09.6 §SES バウンス/苦情 Webhook フロー）。</p>
 *
 * <p>受信メッセージは SNS が SQS に配送する <b>SNS エンベロープ JSON</b>。
 * SES 通知本体は {@code Message} フィールドに JSON <b>文字列</b>として格納される
 * （SNS の raw message delivery 無効時のデフォルト形式）。本リスナーは
 * エンベロープ → SES 通知本体の二段でパースし、既存の業務ロジック
 * {@link SesWebhookService#handleNotification(SesNotificationRequest)} へ委譲する。
 * 業務ロジックは HTTP 時代と完全に同一で、入口のみ差し替えている。</p>
 *
 * <p>SQS 経由では SNS の {@code SubscriptionConfirmation} は発生しない
 * （HTTPS サブスクリプション特有の確認フロー）。SQS 購読の確立は terraform で
 * 行うため、リスナー側に SubscriptionConfirmation 処理は持たない。</p>
 *
 * <p>プロファイル制御: {@code mannschaft.ses.sqs.queue-name} が設定されている環境
 * （prod / staging）でのみ Bean を登録する。local / test / openapi-gen は当該プロパティが
 * 空のため本リスナーは起動せず、実 SQS への接続を試みない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mannschaft.ses.sqs.queue-name")
public class SesNotificationSqsListener {

    private final SesWebhookService sesWebhookService;
    private final ObjectMapper objectMapper;

    /**
     * SQS から SES 通知メッセージ（SNS エンベロープ）を受信して処理する。
     *
     * <p>キュー名は {@code mannschaft.ses.sqs.queue-name}（環境変数
     * {@code SES_NOTIFICATION_QUEUE_NAME}）から SpEL で解決する。</p>
     *
     * @param rawMessage SQS メッセージ本文（SNS エンベロープ JSON 文字列）
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "唯一の SQS 入口であり、スキップやドロップは正常終了として ACK され、バウンス・苦情通知が復旧不能に消失する")
    @SqsListener("${mannschaft.ses.sqs.queue-name}")
    public void onMessage(String rawMessage) {
        SesNotificationRequest request;
        try {
            request = parse(rawMessage);
        } catch (Exception e) {
            // パース不能なメッセージはログに残して握りつぶさず再スロー。
            // SQS の可視性タイムアウト経過後に再配信され、最終的に DLQ へ送られる。
            log.error("SES SQS メッセージのパースに失敗しました: body={}", rawMessage, e);
            throw new IllegalStateException("SES SQS メッセージのパースに失敗しました", e);
        }
        if (request == null) {
            // 処理対象外（SES 通知でない、または通知種別が判定不能）。再配信不要のため正常終了。
            log.warn("SES SQS メッセージが SES 通知として解釈できませんでした: body={}", rawMessage);
            return;
        }
        sesWebhookService.handleNotification(request);
    }

    /**
     * SNS エンベロープ JSON から SES 通知本体を取り出し {@link SesNotificationRequest} に変換する。
     *
     * <p>SNS エンベロープ（raw message delivery 無効時）:
     * <pre>{ "Type": "Notification", "Message": "&lt;SES 通知 JSON 文字列&gt;", "TopicArn": "..." }</pre>
     * raw message delivery 有効時は本文そのものが SES 通知 JSON となるため、
     * {@code Message} フィールドが無い場合は本文を直接 SES 通知 JSON とみなす。</p>
     *
     * <p>SES 通知本体（bounce 例）:
     * <pre>{ "notificationType": "Bounce", "bounce": { "bounceType": "Permanent" },
     *        "mail": { "messageId": "..." } }</pre></p>
     *
     * @return 変換結果。SES 通知として解釈できない場合は {@code null}
     */
    SesNotificationRequest parse(String rawMessage) throws Exception {
        JsonNode envelope = objectMapper.readTree(rawMessage);

        // SNS エンベロープなら Message フィールド（文字列）に SES 通知 JSON が入る。
        // raw message delivery 有効時は envelope 自体が SES 通知 JSON。
        JsonNode sesNode;
        if (envelope.hasNonNull("Message")) {
            sesNode = objectMapper.readTree(envelope.get("Message").asText());
        } else {
            sesNode = envelope;
        }

        String notificationType = text(sesNode, "notificationType");
        if (notificationType == null) {
            // eventType を使う SES Event Publishing 形式にも一応対応する
            notificationType = text(sesNode, "eventType");
        }
        if (notificationType == null) {
            return null;
        }

        // messageId は mail.messageId に格納される
        String messageId = null;
        JsonNode mail = sesNode.get("mail");
        if (mail != null && mail.hasNonNull("messageId")) {
            messageId = mail.get("messageId").asText();
        }

        // bounceType は bounce.bounceType（Permanent / Transient / Undetermined）
        String bounceType = null;
        JsonNode bounce = sesNode.get("bounce");
        if (bounce != null && bounce.hasNonNull("bounceType")) {
            bounceType = bounce.get("bounceType").asText();
        }

        return new SesNotificationRequest(
                envelope.hasNonNull("Type") ? envelope.get("Type").asText() : "Notification",
                messageId,
                notificationType,
                bounceType,
                null,
                null,
                envelope.hasNonNull("TopicArn") ? envelope.get("TopicArn").asText() : null,
                null);
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
