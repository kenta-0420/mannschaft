package com.mannschaft.app.config.websocket;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CMP-261110-2011 AC-26/30の実AppContext停止・破棄試練。
 * MOCK基底の実App、認証、broker、既存schedulerを使い、transport送信境界だけを制御する。
 * 実TCP資格はWebSocketTerminalLifecycleITが別に担う。
 * before-methodでfresh context、after-methodで確実に破棄し、contextはtest method引数で取得する。
 * constructor/instance fieldへ管理contextやそのBeanを保存せず、Dirties後の古い参照を使わない。
 * 実AppContextの停止・破棄を機序unitと別に検査し、起動・fixture失敗は有効redとしない。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class WebSocketTerminalContextShutdownIT extends AbstractMySqlIntegrationTest {
    private static final long BOUND_SECONDS = 5;
    private static final AtomicLong USER_IDS = new AtomicLong(992_600L);

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void 実AppContextStopはGOINGAWAYを優先し予約とsessionを片付ける(
            @Autowired ConfigurableApplicationContext context) throws Exception {
        verifyShutdown(context, false);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void 実AppContextCloseはGOINGAWAYを優先し予約とsessionを片付ける(
            @Autowired ConfigurableApplicationContext context) throws Exception {
        verifyShutdown(context, true);
    }

    private void verifyShutdown(ConfigurableApplicationContext context, boolean close) throws Exception {
        assertThat(context.containsBean("subProtocolWebSocketHandler"))
                .as("既存standard Handler Beanが実AppContextに存在する").isTrue();
        Object actualHandler = context.getBean("subProtocolWebSocketHandler");
        assertThat(actualHandler).isInstanceOf(SubProtocolWebSocketHandler.class);
        assertThat(actualHandler).as("終端拒否のcustom Handlerが実配線されている")
                .isInstanceOf(TerminalCloseSubProtocolWebSocketHandler.class);

        // 以降は停止前に実Bean参照を確保。context.close後にgetBeanを呼ばない。
        SubProtocolWebSocketHandler handler = (SubProtocolWebSocketHandler) actualHandler;
        ThreadPoolTaskScheduler scheduler = context.getBean("messageBrokerTaskScheduler", ThreadPoolTaskScheduler.class);
        assertThat(context.getBean("messageBrokerSockJsTaskScheduler")).isSameAs(scheduler);
        AuthTokenService tokens = context.getBean(AuthTokenService.class);
        SimpMessagingTemplate messaging = context.getBean(SimpMessagingTemplate.class);
        SimpUserRegistry users = context.getBean(SimpUserRegistry.class);
        SimpleBrokerMessageHandler broker = context.getBean(SimpleBrokerMessageHandler.class);
        stubEmptyExternalLobbyMemberships(context.getBean(StringRedisTemplate.class));

        long userId = USER_IDS.incrementAndGet();
        String topic = "/topic/stomp-context-cleanup/" + UUID.randomUUID();
        ControlledTransport transport = new ControlledTransport(handler);
        AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
        Thread shutdownThread = null;
        try {
            handler.afterConnectionEstablished(transport.delegate);
            handler.handleMessage(transport.delegate, frame("CONNECT\naccept-version:1.2\nAuthorization:Bearer "
                    + tokens.issueAccessToken(userId, List.of("USER")) + "\n\n"));
            await().atMost(BOUND_SECONDS, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(transport.commands).contains("CONNECTED");
                assertThat(users.getUser(String.valueOf(userId))).isNotNull();
            });
            handler.handleMessage(transport.delegate, frame("SUBSCRIBE\nid:context-stop\ndestination:" + topic + "\n\n"));
            await().atMost(BOUND_SECONDS, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(broker.getSubscriptionRegistry().findSubscriptions(publication(topic)))
                            .containsKey(transport.id));

            transport.blockMessages.set(true);
            messaging.convertAndSend(topic, "controlled-outbound");
            assertThat(transport.sendEntered.await(BOUND_SECONDS, TimeUnit.SECONDS))
                    .as("実Handlerの送信がtransport境界へ到達した").isTrue();
            Set<Runnable> previousTasks = new HashSet<>(scheduler.getScheduledThreadPoolExecutor().getQueue());
            handler.handleMessage(transport.delegate, frame("SUBSCRIBE\nid:invalid\ndestination:"
                    + "/topic/teams/99999999999999999999999/emergency-closures/1/confirmations\n\n"));
            await().atMost(BOUND_SECONDS, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(transport.attributes.get(FatalStompSessionGate.FATAL_SESSION_ATTRIBUTE)).isEqualTo(true));
            List<Runnable> added = new ArrayList<>(scheduler.getScheduledThreadPoolExecutor().getQueue());
            added.removeAll(previousTasks);
            assertThat(added).as("実production schedulerに期限が1件だけ予約された").hasSize(1);
            assertThat(added.get(0)).isInstanceOf(ScheduledFuture.class);
            ScheduledFuture<?> deadline = (ScheduledFuture<?>) added.get(0);
            assertThat(deadline.isCancelled()).isFalse();
            assertThat(transport.closeCount.get()).as("停止前はinflight送信のため物理closeを保留").isZero();

            shutdownThread = new Thread(() -> {
                try {
                    if (close) context.close();
                    else context.stop();
                } catch (Throwable failure) {
                    shutdownFailure.set(failure);
                }
            }, "stomp-owned-context-" + (close ? "close" : "stop"));
            shutdownThread.start();
            try {
                assertThat(transport.physicalClose.await(BOUND_SECONDS, TimeUnit.SECONDS))
                        .as("context終了のGOING_AWAYはblocked送信より先に物理closeする").isTrue();
                assertThat(transport.closeStatus.get()).isEqualTo(CloseStatus.GOING_AWAY);
                assertThat(deadline.isCancelled()).as("他status優先で保留期限を取消す").isTrue();
            } finally {
                transport.releaseSend.countDown();
                shutdownThread.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));
            }
            assertThat(shutdownThread.isAlive()).as("所有shutdown thread残存なし").isFalse();
            assertThat(shutdownFailure.get()).isNull();
            assertThat(transport.sendFailure.get()).as("transport制御のfixture失敗なし").isNull();
            assertThat(transport.closeCount.get()).isEqualTo(1);
            assertThat(handler.isRunning()).isFalse();
            assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).doesNotContain((Runnable) deadline);
            assertThat(users.getUser(String.valueOf(userId))).isNull();
            assertThat(broker.getSubscriptionRegistry().findSubscriptions(publication(topic)))
                    .doesNotContainKey(transport.id);
            if (close) {
                assertThat(context.isActive()).isFalse();
                assertThat(scheduler.getScheduledExecutor().isShutdown()).isTrue();
            } else {
                assertThat(context.isActive()).as("stopはcontext破棄ではない").isTrue();
                assertThat(context.isRunning()).isFalse();
            }
        } finally {
            transport.releaseSend.countDown();
            if (shutdownThread != null) {
                shutdownThread.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));
                assertThat(shutdownThread.isAlive()).as("finallyでも所有thread残存なし").isFalse();
            }
            transport.closeIfStillOpen();
        }
    }

    private static void stubEmptyExternalLobbyMemberships(StringRedisTemplate redis) {
        @SuppressWarnings("unchecked")
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.members(anyString())).thenReturn(Set.of());
    }

    private static TextMessage frame(String value) {
        return new TextMessage(value + '\0');
    }

    private static Message<byte[]> publication(String topic) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(topic);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** 実App Beanを置換せず、物理送信とtransport close callbackだけを制御する。 */
    private static final class ControlledTransport {
        final String id = "context-stop-" + UUID.randomUUID();
        final WebSocketSession delegate = mock(WebSocketSession.class);
        final Map<String, Object> attributes = new ConcurrentHashMap<>();
        final List<String> commands = new CopyOnWriteArrayList<>();
        final AtomicBoolean open = new AtomicBoolean(true);
        final AtomicBoolean blockMessages = new AtomicBoolean();
        final CountDownLatch sendEntered = new CountDownLatch(1);
        final CountDownLatch releaseSend = new CountDownLatch(1);
        final CountDownLatch physicalClose = new CountDownLatch(1);
        final AtomicInteger closeCount = new AtomicInteger();
        final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        final AtomicReference<Throwable> sendFailure = new AtomicReference<>();

        ControlledTransport(SubProtocolWebSocketHandler handler) throws Exception {
            when(delegate.getId()).thenReturn(id);
            when(delegate.getAttributes()).thenReturn(attributes);
            when(delegate.getUri()).thenReturn(URI.create("ws://localhost/ws/websocket"));
            when(delegate.getAcceptedProtocol()).thenReturn("v12.stomp");
            when(delegate.getTextMessageSizeLimit()).thenReturn(64 * 1024);
            when(delegate.getBinaryMessageSizeLimit()).thenReturn(64 * 1024);
            when(delegate.isOpen()).thenAnswer(call -> open.get());
            doAnswer(call -> {
                TextMessage message = call.getArgument(0);
                String command = message.getPayload().split("\n", 2)[0];
                commands.add(command);
                if (blockMessages.get() && command.equals("MESSAGE")) {
                    sendEntered.countDown();
                    if (!releaseSend.await(2 * BOUND_SECONDS, TimeUnit.SECONDS)) {
                        IOException failure = new IOException("fixture transport release missing");
                        sendFailure.set(failure);
                        throw failure;
                    }
                }
                return null;
            }).when(delegate).sendMessage(any());
            doAnswer(call -> {
                CloseStatus status = call.getArgument(0);
                closeCount.incrementAndGet();
                closeStatus.compareAndSet(null, status);
                if (open.compareAndSet(true, false)) {
                    try {
                        handler.afterConnectionClosed(delegate, status);
                    } finally {
                        physicalClose.countDown();
                    }
                }
                return null;
            }).when(delegate).close(any(CloseStatus.class));
        }

        void closeIfStillOpen() throws IOException {
            if (open.get()) delegate.close(CloseStatus.NORMAL);
        }
    }
}