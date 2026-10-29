package com.mannschaft.app.notification.moduleevent;

import com.mannschaft.app.moduleevent.ModuleEventMySqlTest;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
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
    @Autowired NotificationDispatchService dispatch;
    @Autowired @Qualifier("brokerChannel") AbstractMessageChannel broker;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 現在許可の配信後に降格退会tenant不適格へ変わったら再配送しない(boolean batch) {
        var row = notice("DELAYED");
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "ja"));
        NotificationEntity notification = notification("MODULE_EVENT_NOTICE", row.getNoticeRef());
        AtomicInteger sent = new AtomicInteger();
        ChannelInterceptor observer = observer(sent);
        broker.addInterceptor(observer);
        try {
            send(notification, batch);
            assertThat(sent.get()).as("許可検体が実 broker に届くことを先に確認").isEqualTo(1);
            sent.set(0);
            state(row).recipients = List.of();
            send(notification, batch);
            assertThat(sent.get()).as("現在拒否された相手へ WebSocket を送らない").isZero();
        } finally { broker.removeInterceptor(observer); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void Mのnull参照と未接続をfailclosedにする(boolean batch) {
        AtomicInteger sent = new AtomicInteger();
        ChannelInterceptor observer = observer(sent);
        broker.addInterceptor(observer);
        try {
            send(notification("MODULE_EVENT_NOTICE", null), batch);
            send(notification("MODULE_EVENT_NOTICE", Long.MAX_VALUE), batch);
            assertThat(sent.get()).isZero();
        } finally { broker.removeInterceptor(observer); }
    }

    @Test
    void 既存SYSTEM通知の未解決source互換性を維持する() {
        AtomicInteger sent = new AtomicInteger();
        ChannelInterceptor observer = observer(sent);
        broker.addInterceptor(observer);
        try { send(notification("SYSTEM", null), false); assertThat(sent.get()).isEqualTo(1); }
        finally { broker.removeInterceptor(observer); }
    }

    private void send(NotificationEntity notification, boolean batch) {
        // 非同期の待ち時間を否定証明に使わない。実 service target を同期実行し全実依存を通す。
        NotificationDispatchService target = AopTestUtils.getUltimateTargetObject(dispatch);
        if (batch) target.dispatchBatch(List.of(notification)); else target.dispatch(notification);
    }

    private ChannelInterceptor observer(AtomicInteger sent) {
        return new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                if (String.valueOf(message.getHeaders().get("simpDestination")).endsWith("/queue/notifications")) {
                    sent.incrementAndGet();
                }
                return message;
            }
        };
    }

    private NotificationEntity notification(String sourceType, Long sourceId) {
        return NotificationEntity.builder().id(1L).userId(USER_A).notificationType("MODULE_EVENT_NOTICE")
                .priority(NotificationPriority.NORMAL).title("通知接続試練").body("固定検体")
                .sourceType(sourceType).sourceId(sourceId).scopeType(NotificationScopeType.TEAM).scopeId(8201L)
                .isRead(false).createdAt(java.time.LocalDateTime.now()).build();
    }
}
