package com.mannschaft.app.signage;

import com.mannschaft.app.signage.websocket.SignageSocketAccessReader;
import com.mannschaft.app.signage.websocket.SignageSocketSessions;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import org.apache.tomcat.websocket.WsSession;
import jakarta.websocket.CloseReason;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.springframework.web.socket.sockjs.support.AbstractSockJsService;
import org.springframework.web.socket.sockjs.support.SockJsHttpRequestHandler;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.net.URI;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AC6/8: 実MySQLの旧REPEATABLE READと独立資格照合、実broker配送のquery数を検査する。
 * 共通fixtureだけを共有し、15ネットワーク先行試練を継承して二重実行しない。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class SignageSocketDatabaseIT extends SignageSocketIntegrationFixture {
    @Autowired SignageSocketAccessReader accessReader;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    @Autowired SignageSocketSessions socketSessions;
    @Autowired @Qualifier("applicationEventMulticaster") ApplicationEventMulticaster events;
    @Autowired @Qualifier("clientInboundChannel") ExecutorSubscribableChannel inboundChannel;

    @Test
    void AC4_DB照合中の実peer切断後にCONNECT完成してもregistryを復活させない() throws Exception {
        var heartbeats = new LinkedHashMap<AbstractSockJsService, Long>();
        for (var mapping : context.getBeansOfType(WebSocketHandlerMapping.class).values()) {
            for (Object handler : mapping.getHandlerMap().values()) {
                if (handler instanceof SockJsHttpRequestHandler sockJs
                        && sockJs.getSockJsService() instanceof AbstractSockJsService service) {
                    heartbeats.putIfAbsent(service, service.getHeartbeatTime());
                    service.setHeartbeatTime(100);
                }
            }
        }
        assertThat(heartbeats).as("実SockJSのheartbeat経路を検査する").isNotEmpty();
        AtomicReference<WebSocketSession> peer = new AtomicReference<>();
        AtomicReference<String> serverId = new AtomicReference<>();
        String marker = UUID.randomUUID().toString();
        var disconnected = new LinkedBlockingQueue<String>();
        var authorizationCompleted = new LinkedBlockingQueue<String>();
        var disconnectCompleted = new LinkedBlockingQueue<String>();
        ApplicationListener<ApplicationEvent> listener = event -> {
            if (event instanceof SessionDisconnectEvent closed) disconnected.offer(closed.getSessionId());
        };
        events.addApplicationListener(listener);
        ChannelInterceptor afterAuthorization = new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                var accessor = StompHeaderAccessor.wrap(message);
                if ((accessor.getCommand() == StompCommand.CONNECT || accessor.getCommand() == StompCommand.STOMP)
                        && marker.equals(accessor.getFirstNativeHeader("X-Test-Connection-Marker"))) {
                    serverId.compareAndSet(null, accessor.getSessionId());
                }
                return message;
            }

            @Override
            public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception exception) {
                var accessor = StompHeaderAccessor.wrap(message);
                if (accessor.getCommand() == StompCommand.CONNECT || accessor.getCommand() == StompCommand.STOMP) {
                    authorizationCompleted.offer(accessor.getSessionId());
                }
                if (accessor.getCommand() == StompCommand.DISCONNECT) {
                    disconnectCompleted.offer(accessor.getSessionId());
                }
            }
        };
        // 実CONNECTの内部IDを認可前に捕捉し、後段の成功/拒否の完了も待つ。
        inboundChannel.addInterceptor(0, afterAuthorization);
        StandardWebSocketClient delegate = new StandardWebSocketClient();
        WebSocketClient tracked = new WebSocketClient() {
            public CompletableFuture<WebSocketSession> execute(WebSocketHandler handler, String uri, Object... args) {
                return delegate.execute(handler, uri, args).thenApply(session -> { peer.set(session); return session; });
            }
            public CompletableFuture<WebSocketSession> execute(WebSocketHandler handler, WebSocketHttpHeaders headers, URI uri) {
                return delegate.execute(handler, headers, uri).thenApply(session -> { peer.set(session); return session; });
            }
        };
        var client = new WebSocketStompClient(new SockJsClient(List.of(new WebSocketTransport(tracked))));
        clients.add(client);
        Throwable original = null;
        // 試練専用MySQLの当該tableだけをlockし、実認可queryが待機する有限区間を作る。
        try { try (var locker = dataSource.getConnection(); var statement = locker.createStatement()) {
            statement.setQueryTimeout(15);
            statement.execute("LOCK TABLES signage_access_tokens WRITE");
            try {
                var headers = new StompHeaders();
                headers.set("X-Signage-Token", token.getToken());
                headers.set("X-Test-Connection-Marker", marker);
                var handshake = new WebSocketHttpHeaders();
                handshake.setOrigin("http://localhost:3000");
                var connected = client.connectAsync("ws://127.0.0.1:" + port + "/ws", handshake, headers,
                        new StompSessionHandlerAdapter() { });
                await().atMost(Duration.ofSeconds(10)).until(() -> peer.get() != null && jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.processlist WHERE id <> CONNECTION_ID() "
                                + "AND state LIKE '%lock%' AND info LIKE 'select %' AND info LIKE '%signage_access_tokens%'",
                        Integer.class) > 0);
                assertThat(serverId.get()).as("実CONNECTの内部session IDを取得済み").isNotBlank();
                assertThat(peer.get()).isInstanceOf(StandardWebSocketSession.class);
                var nativePeer = ((StandardWebSocketSession) peer.get()).getNativeSession();
                assertThat(nativePeer).as("固定Tomcat実clientで物理切断を検査する").isInstanceOf(WsSession.class);
                // 通常closeはhandshake待ちになる。実TomcatのcloseSocket=trueで所有TCPを終了する。
                var reason = new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "owned transport close");
                ((WsSession) nativePeer).doClose(reason, reason, true);
                await().atMost(Duration.ofSeconds(10)).until(() -> disconnected.contains(serverId.get()));
                await().atMost(Duration.ofSeconds(10)).until(() -> disconnectCompleted.contains(serverId.get()));
                statement.execute("UNLOCK TABLES");
                await().atMost(Duration.ofSeconds(10)).until(connected::isDone);
                await().atMost(Duration.ofSeconds(10)).until(() -> authorizationCompleted.contains(serverId.get()));
                // 実closeが資格queryより先に処理された事実を上で確認した後に、late完了後の状態を観測する。
                await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.processlist WHERE id <> CONNECTION_ID() "
                                + "AND state LIKE '%lock%' AND info LIKE 'select %' AND info LIKE '%signage_access_tokens%'",
                        Integer.class) == 0);
                var state = (java.util.Optional<?>) ReflectionTestUtils.invokeMethod(socketSessions, "session", serverId.get());
                assertThat(state).as("実peer切断後に資格stateを復活させない").isEmpty();
            } catch (Exception | AssertionError failure) {
                original = failure;
                throw failure;
            } finally {
                var cleanup = new ArrayList<Exception>();
                try { statement.execute("UNLOCK TABLES"); } catch (Exception failure) { cleanup.add(failure); }
                if (original != null) cleanup.forEach(original::addSuppressed);
                else if (!cleanup.isEmpty()) throw new AssertionError("所有lockの回収失敗", cleanup.getFirst());
            }
        } } catch (Exception | AssertionError failure) {
            original = failure;
            throw failure;
        } finally {
            var cleanup = new ArrayList<Exception>();
            try { events.removeApplicationListener(listener); } catch (Exception failure) { cleanup.add(failure); }
            try { inboundChannel.removeInterceptor(afterAuthorization); } catch (Exception failure) { cleanup.add(failure); }
            heartbeats.forEach((service, previous) -> {
                try { service.setHeartbeatTime(previous); } catch (RuntimeException failure) { cleanup.add(failure); }
            });
            if (original != null) cleanup.forEach(original::addSuppressed);
            else if (!cleanup.isEmpty()) throw new AssertionError("所有observerの回収失敗", cleanup.getFirst());
        }
    }
    @Test
    void AC6_publisherの旧RRsnapshotでも失効commit後の独立資格判定は拒否する() throws Exception {
        Inbox revoked = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        Inbox control = subscribe(connect("/ws/signage", otherToken.getToken(), null, null), emergency());
        TransactionTemplate outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            assertThat(accessReader.authenticate(token.getToken(), "127.0.0.1")).isPresent();
            assertThat(((Number) em.createNativeQuery("SELECT CASE WHEN is_active THEN 1 ELSE 0 END FROM signage_access_tokens WHERE id=:id")
                    .setParameter("id", token.getId()).getSingleResult()).intValue()).isEqualTo(1);
            CompletableFuture.runAsync(() -> mutateToken("isActive", false)).join();
            assertThat(((Number) em.createNativeQuery("SELECT CASE WHEN is_active THEN 1 ELSE 0 END FROM signage_access_tokens WHERE id=:id")
                    .setParameter("id", token.getId()).getSingleResult()).intValue())
                    .as("publisher側のRRは失効前のsnapshotを保持").isEqualTo(1);
            assertThat(accessReader.permits(token.getId(), screen.getId(), "127.0.0.1"))
                    .as("同じthread内の資格照合も旧RRへ参加しない").isFalse();
            try {
                assertDelivery(control, revoked);
            } catch (Exception e) {
                throw new AssertionError("旧RR内publisherからの配送を実STOMPで検査", e);
            }
        });
    }

    @Test
    void AC8_正常配送はsubscriptionごとに1projectionで制御と非Signageは0query() throws Exception {
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean previous = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            var session = connect("/ws", token.getToken(), null, null);
            Inbox device = subscribe(session, emergency());
            long before = projectionQueries(statistics);
            String marker = UUID.randomUUID().toString();
            publisher.publishEmergency(screen.getId(), marker);
            assertThat(device.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", marker);
            assertThat(projectionQueries(statistics) - before).isEqualTo(1);
            before = projectionQueries(statistics);
            device.subscription.unsubscribe();
            await().atMost(Duration.ofSeconds(10)).until(() -> !registered(emergency(), device.id));
            assertNonSignageDelivery();
            assertThat(projectionQueries(statistics) - before).isZero();
        } finally {
            statistics.setStatisticsEnabled(previous);
        }
    }

    private long projectionQueries(org.hibernate.stat.Statistics statistics) {
        return Arrays.stream(statistics.getQueries()).filter(query -> query.contains("SignageSocketAccess("))
                .mapToLong(query -> statistics.getQueryStatistics(query).getExecutionCount()).sum();
    }

}
