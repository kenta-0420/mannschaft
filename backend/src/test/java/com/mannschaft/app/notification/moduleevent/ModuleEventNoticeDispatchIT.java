package com.mannschaft.app.notification.moduleevent;

import com.mannschaft.app.moduleevent.ModuleEventMySqlTest;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.AbstractMessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.test.util.AopTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 実 dispatch の単発/バッチ共通関所と実 broker channel までを検査する。外部 Push は購読なし。 */
@ModuleEventMySqlTest
@Import(ModuleEventNoticeNotificationFixtures.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventNoticeDispatchIT extends ModuleEventNoticeNotificationTestSupport {
    private static final java.util.concurrent.atomic.AtomicLong IDS = new java.util.concurrent.atomic.AtomicLong(9_100_000L);
    @Autowired NotificationDispatchService dispatch;
    @Autowired @Qualifier("brokerChannel") AbstractMessageChannel broker;
    @Autowired ObjectMapper mapper;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 実SPIの現在判定が許可から拒否へ変わったら再配送しない(boolean batch) {
        var row = notice("DELAYED");
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "ja"));
        NotificationEntity notification = notification("MODULE_EVENT_NOTICE", row.getNoticeRef());
        assertThat(observedSend(notification, batch)).as("許可検体が実 broker に届くことを先に確認").isEqualTo(1);
        state(row).recipients = List.of();
        assertThat(observedSend(notification, batch)).as("実 SPI が現在拒否した相手へ WebSocket を送らない").isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void Mのnull参照と未接続をfailclosedにする(boolean batch) {
        assertThat(observedSend(notification("MODULE_EVENT_NOTICE", null), batch)).isZero();
        assertThat(observedSend(notification("MODULE_EVENT_NOTICE", Long.MAX_VALUE), batch)).isZero();
    }

    @Test
    void 既存SYSTEM通知の未解決source互換性を維持する() {
        assertThat(observedSend(notification("SYSTEM", null), false)).isEqualTo(1);
    }

    private void send(NotificationEntity notification, boolean batch) {
        // 非同期の待ち時間を否定証明に使わない。実 service target を同期実行し全実依存を通す。
        NotificationDispatchService target = AopTestUtils.getUltimateTargetObject(dispatch);
        if (batch) target.dispatchBatch(List.of(notification)); else target.dispatch(notification);
    }

    private int observedSend(NotificationEntity expected, boolean batch) {
        AtomicInteger sent = new AtomicInteger();
        var observationFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        ChannelInterceptor observer = new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                String destination = "/user/" + expected.getUserId() + "/queue/notifications";
                if (!destination.equals(message.getHeaders().get("simpDestination"))) return message;
                try {
                    Object payload = message.getPayload();
                    var json = payload instanceof byte[] bytes ? mapper.readTree(bytes)
                            : payload instanceof String text ? mapper.readTree(text) : mapper.valueToTree(payload);
                    Long sourceId = json.path("sourceId").isNull() || json.path("sourceId").isMissingNode()
                            ? null : json.path("sourceId").asLong();
                    if (json.path("id").asLong() == expected.getId()
                            && json.path("userId").asLong() == expected.getUserId()
                            && expected.getSourceType().equals(json.path("sourceType").asText())
                            && java.util.Objects.equals(expected.getSourceId(), sourceId)) sent.incrementAndGet();
                } catch (Throwable failure) {
                    observationFailure.set(failure);
                }
                return message;
            }
        };
        broker.addInterceptor(observer);
        try {
            send(expected, batch);
            if (observationFailure.get() != null) {
                throw new IllegalStateException("自己 broker payload の観測に失敗", observationFailure.get());
            }
            return sent.get();
        } finally { broker.removeInterceptor(observer); }
    }

    private NotificationEntity notification(String sourceType, Long sourceId) {
        return NotificationEntity.builder().id(IDS.incrementAndGet()).userId(USER_A).notificationType("MODULE_EVENT_NOTICE")
                .priority(NotificationPriority.NORMAL).title("通知接続試練").body("固定検体")
                .sourceType(sourceType).sourceId(sourceId).scopeType(NotificationScopeType.TEAM).scopeId(8201L)
                .isRead(false).createdAt(java.time.LocalDateTime.now()).build();
    }
}
