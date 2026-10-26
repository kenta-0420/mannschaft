package com.mannschaft.app.directmail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.directmail.dto.SesNotificationRequest;
import com.mannschaft.app.directmail.listener.SesNotificationSqsListener;
import com.mannschaft.app.directmail.service.SesWebhookService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link SesNotificationSqsListener} の単体テスト（F09.6 Phase 8a）。
 *
 * <p>SNS エンベロープ JSON のパースと、SES 通知本体（bounce / complaint）の
 * {@link SesWebhookService#handleNotification(SesNotificationRequest)} への正しい委譲を検証する。
 * 業務ロジック自体は {@code SesWebhookServiceTest} が担保する（本テストは入口の変換責務に限定）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SesNotificationSqsListener 単体テスト")
class SesNotificationSqsListenerTest {

    @Mock
    private SesWebhookService sesWebhookService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SesNotificationSqsListener listener() {
        return new SesNotificationSqsListener(sesWebhookService, objectMapper);
    }

    /** SNS エンベロープに SES 通知 JSON 文字列を Message として包む。 */
    private String snsEnvelope(String sesNotificationJson) {
        return """
                {
                  "Type": "Notification",
                  "TopicArn": "arn:aws:sns:ap-northeast-1:123456789012:mannschaft-ses",
                  "Message": %s
                }
                """.formatted(objectMapper.valueToTree(sesNotificationJson).toString());
    }

    @Nested
    @DisplayName("onMessage（SNS エンベロープ経由）")
    class OnMessage {

        @Test
        @DisplayName("Bounce(Permanent) 通知を messageId/notificationType/bounceType に正しく変換して委譲する")
        void bounce_permanent_委譲() {
            String ses = """
                    {
                      "notificationType": "Bounce",
                      "bounce": { "bounceType": "Permanent" },
                      "mail": { "messageId": "msg-abc-123" }
                    }
                    """;

            listener().onMessage(snsEnvelope(ses));

            ArgumentCaptor<SesNotificationRequest> captor =
                    ArgumentCaptor.forClass(SesNotificationRequest.class);
            verify(sesWebhookService).handleNotification(captor.capture());
            SesNotificationRequest req = captor.getValue();
            assertThat(req.getNotificationType()).isEqualTo("Bounce");
            assertThat(req.getMessageId()).isEqualTo("msg-abc-123");
            assertThat(req.getBounceType()).isEqualTo("Permanent");
        }

        @Test
        @DisplayName("Complaint 通知を messageId/notificationType に正しく変換して委譲する")
        void complaint_委譲() {
            String ses = """
                    {
                      "notificationType": "Complaint",
                      "complaint": { "complainedRecipients": [ { "emailAddress": "x@example.com" } ] },
                      "mail": { "messageId": "msg-complaint-9" }
                    }
                    """;

            listener().onMessage(snsEnvelope(ses));

            ArgumentCaptor<SesNotificationRequest> captor =
                    ArgumentCaptor.forClass(SesNotificationRequest.class);
            verify(sesWebhookService).handleNotification(captor.capture());
            SesNotificationRequest req = captor.getValue();
            assertThat(req.getNotificationType()).isEqualTo("Complaint");
            assertThat(req.getMessageId()).isEqualTo("msg-complaint-9");
            assertThat(req.getBounceType()).isNull();
        }

        @Test
        @DisplayName("raw message delivery（Message ラップなし）の SES 通知も直接パースして委譲する")
        void rawMessageDelivery_委譲() {
            // SNS エンベロープに包まれていない素の SES 通知 JSON
            String rawSes = """
                    {
                      "notificationType": "Bounce",
                      "bounce": { "bounceType": "Transient" },
                      "mail": { "messageId": "msg-raw-1" }
                    }
                    """;

            listener().onMessage(rawSes);

            ArgumentCaptor<SesNotificationRequest> captor =
                    ArgumentCaptor.forClass(SesNotificationRequest.class);
            verify(sesWebhookService).handleNotification(captor.capture());
            assertThat(captor.getValue().getMessageId()).isEqualTo("msg-raw-1");
            assertThat(captor.getValue().getBounceType()).isEqualTo("Transient");
        }

        @Test
        @DisplayName("notificationType を持たないメッセージは委譲せず正常終了する（再配信不要）")
        void notificationTypeなし_委譲なし() {
            String notSes = """
                    { "foo": "bar" }
                    """;

            listener().onMessage(notSes);

            verifyNoInteractions(sesWebhookService);
        }

        @Test
        @DisplayName("パース不能なメッセージは例外を再スローする（SQS 再配信 → DLQ 行き）")
        void パース不能_再スロー() {
            String broken = "{ this is not json";

            assertThatThrownBy(() -> listener().onMessage(broken))
                    .isInstanceOf(IllegalStateException.class);

            verify(sesWebhookService, never()).handleNotification(org.mockito.ArgumentMatchers.any());
        }
    }
}
