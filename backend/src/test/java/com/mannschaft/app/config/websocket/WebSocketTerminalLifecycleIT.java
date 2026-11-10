package com.mannschaft.app.config.websocket;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.chat.ws.ChatChannelSubscriptionInterceptor;
import com.mannschaft.app.common.websocket.StompErrorCode;
import com.mannschaft.app.config.WebSocketAuthChannelInterceptor;
import com.mannschaft.app.match.live.MatchLiveSubscriptionInterceptor;
import com.mannschaft.app.reservation.ws.EmergencyClosureSubscriptionInterceptor;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.AbstractMessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * CMP-261110-2011 AC-26/28/29の正式実構成・実TCP試練。
 * 実Security、AuthTokenService、購読認可、実MySQL基底、broker、SimpUserRegistryを使用する。
 * 実TCPが契約対象なのでRANDOM_PORT、専用contextはAFTER_CLASSで閉じる（TEST_CONVENTION §3.1.2）。
 * localは容量条件未成立により未実行。起動/fixture失敗を有効redとして数えない。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class WebSocketTerminalLifecycleIT extends AbstractMySqlIntegrationTest {
    private static final long WAIT_SECONDS = 60;
    private static final AtomicLong USER_IDS = new AtomicLong(992_400L);
    private final ApplicationContext context;
    private final AuthTokenService tokens;
    private final SimpUserRegistry users;
    private final SimpleBrokerMessageHandler broker;
    private final MessageChannel inbound;
    @LocalServerPort private int port;

    @Autowired
    WebSocketTerminalLifecycleIT(ApplicationContext context, AuthTokenService tokens, SimpUserRegistry users,
                                 SimpleBrokerMessageHandler broker,
                                 @Qualifier("clientInboundChannel") MessageChannel inbound) {
        this.context = context;
        this.tokens = tokens;
        this.users = users;
        this.broker = broker;
        this.inbound = inbound;
    }

    @Test
    void 実構成はcustomHandlerとgate先行を持ち既存認証認可順序を保つ() {
        assertThat(context.getBeansOfType(TerminalCloseWebSocketBrokerConfig.class)).hasSize(1);
        var handlers = context.getBeansOfType(SubProtocolWebSocketHandler.class).values();
        assertThat(handlers).singleElement().isInstanceOf(TerminalCloseSubProtocolWebSocketHandler.class);
        var gates = context.getBeansOfType(FatalStompSessionGate.class).values();
        assertThat(gates).hasSize(1);
        FatalStompSessionGate gate = gates.iterator().next();
        List<ChannelInterceptor> interceptors = ((AbstractMessageChannel) inbound).getInterceptors();
        WebSocketAuthChannelInterceptor auth = context.getBean(WebSocketAuthChannelInterceptor.class);
        assertThat(interceptors).contains(gate).containsSubsequence(auth,
                context.getBean(MatchLiveSubscriptionInterceptor.class),
                context.getBean(EmergencyClosureSubscriptionInterceptor.class),
                context.getBean(ChatChannelSubscriptionInterceptor.class));
        assertThat(interceptors.indexOf(gate)).isLessThan(interceptors.indexOf(auth));
    }

    @Test
    void 両SockJSBaseのhandlerMappingを維持する() {
        var paths = context.getBeansOfType(SimpleUrlHandlerMapping.class).values().stream()
                .flatMap(mapping -> mapping.getHandlerMap().keySet().stream()).toList();
        assertThat(paths).contains("/ws/**", "/ws/signage/**");
    }

    @Test
    void 両TCPTransportで実CONNECTとPrincipal登録が成立する() throws Exception {
        for (String transport : List.of("/ws/websocket", "/ws/signage/websocket")) {
            long userId = USER_IDS.incrementAndGet();
            try (Connection connection = connect(transport, userId)) {
                assertThat(connection.session.isConnected()).isTrue();
                await().atMost(WAIT_SECONDS, TimeUnit.SECONDS)
                        .untilAsserted(() -> assertThat(users.getUser(String.valueOf(userId))).isNotNull());
            }
        }
    }

    @Test
    void fatal拒否後に実broker購読とSimpUserRegistryが片付く() throws Exception {
        long userId = USER_IDS.incrementAndGet();
        String topic = "/topic/stomp-terminal-cleanup/" + UUID.randomUUID();
        try (Connection connection = connect("/ws/websocket", userId)) {
            String serverSession = subscribeAndObserve(connection, userId, topic);
            connection.session.subscribe("/topic/teams/99999999999999999999999/emergency-closures/1/confirmations",
                    new StompSessionHandlerAdapter() { });
            StompHeaders error = connection.handler.error.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(error.getFirst(StompErrorCode.HEADER)).isEqualTo("INVALID_DESTINATION");
            assertCleanedUp(connection, userId, serverSession, topic);
        }
    }

    @Test
    void fatal属性付きsyntheticDISCONNECTも実brokerとsessionを片付ける() throws Exception {
        long userId = USER_IDS.incrementAndGet();
        String topic = "/topic/stomp-terminal-cleanup/" + UUID.randomUUID();
        try (Connection connection = connect("/ws/websocket", userId)) {
            String serverSession = subscribeAndObserve(connection, userId, topic);
            SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.DISCONNECT);
            accessor.setSessionId(serverSession);
            accessor.setUser(() -> String.valueOf(userId));
            Map<String, Object> attributes = new ConcurrentHashMap<>();
            attributes.put(FatalStompSessionGate.FATAL_SESSION_ATTRIBUTE, true);
            accessor.setSessionAttributes(attributes);
            assertThat(inbound.send(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders())))
                    .as("内部cleanupのDISCONNECTをfatal gateが拒否しない").isTrue();
            assertCleanedUp(connection, userId, serverSession, topic);
        }
    }

    private String subscribeAndObserve(Connection connection, long userId, String topic) {
        String user = String.valueOf(userId);
        await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(users.getUser(user)).isNotNull());
        connection.session.subscribe(topic, new StompSessionHandlerAdapter() { });
        await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(users.getUser(user).getSessions()).hasSize(1));
        String serverSession = users.getUser(user).getSessions().iterator().next().getId();
        await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(broker.getSubscriptionRegistry().findSubscriptions(publication(topic)))
                        .containsKey(serverSession));
        return serverSession;
    }

    private void assertCleanedUp(Connection connection, long userId, String serverSession, String topic) {
        await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(connection.handler.closed.isDone()).isTrue();
            assertThat(users.getUser(String.valueOf(userId))).isNull();
            assertThat(broker.getSubscriptionRegistry().findSubscriptions(publication(topic)))
                    .doesNotContainKey(serverSession);
        });
    }

    private static Message<byte[]> publication(String topic) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(topic);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Connection connect(String path, long userId) throws Exception {
        StandardWebSocketClient transport = new StandardWebSocketClient();
        transport.setUserProperties(Map.<String, Object>of("org.apache.tomcat.websocket.IO_TIMEOUT_MS", "60000"));
        WebSocketStompClient client = new WebSocketStompClient(transport);
        Capture handler = new Capture();
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + tokens.issueAccessToken(userId, List.of("USER")));
        try {
            StompSession session = client.connectAsync("ws://localhost:" + port + path,
                    new WebSocketHttpHeaders(), connect, handler).get(WAIT_SECONDS, TimeUnit.SECONDS);
            return new Connection(client, session, handler);
        } catch (Exception | Error failure) {
            client.stop();
            throw failure;
        }
    }

    private record Connection(WebSocketStompClient client, StompSession session, Capture handler)
            implements AutoCloseable {
        @Override public void close() {
            try {
                if (!handler.closed.isDone() && session.isConnected()) session.disconnect();
            } finally {
                client.stop();
            }
        }
    }

    private static final class Capture extends StompSessionHandlerAdapter {
        final CompletableFuture<StompHeaders> error = new CompletableFuture<>();
        final CompletableFuture<Throwable> closed = new CompletableFuture<>();
        @Override public void handleFrame(@NonNull StompHeaders headers, Object payload) { error.complete(headers); }
        @Override public void handleTransportError(@NonNull StompSession session, @NonNull Throwable failure) {
            closed.complete(failure);
        }
        @Override public void handleException(@NonNull StompSession session, StompCommand command,
                                              @NonNull StompHeaders headers, @NonNull byte[] payload,
                                              @NonNull Throwable failure) {
            error.completeExceptionally(failure);
        }
    }
}
