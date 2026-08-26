package com.mannschaft.app.websocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import com.mannschaft.app.auth.service.AuthTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.lang.NonNull;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §7.2 Principal 未配線の欠陥実証（AC-5・red 先行）。
 *
 * <p>{@code WebSocketAuthChannelInterceptor} は CONNECT 時に {@code sessionAttributes.put("userId", ...)} のみで
 * {@code accessor.setUser(...)}（STOMP Principal 確立）をしていない（§2.3・実コード裏取り済み）。その結果:</p>
 * <ol>
 *   <li>{@link SimpUserRegistry} に接続ユーザーが登録されない。</li>
 *   <li>{@code convertAndSendToUser("/user/{userId}/queue/...")} が解決先 0 件で、<b>単一ノードでもユーザー宛通知が誰にも届かない</b>。</li>
 * </ol>
 *
 * <h3>原因の切り分け（憶測修正禁止・§7.2）</h3>
 * <p>まず「{@code /queue} プレフィックスがブローカーに登録済み」を独立にアサートし、
 * §2.1 の {@code configureMessageBroker} 順序依存が red の原因かどうかを切り分ける。その上でユーザー宛配信の
 * red を評価する。</p>
 *
 * <p><b>【実証所見・隊 1 への申し送り】</b> 本 red テストの初回実走で、この切り分けアサートが<b>実際に失敗</b>した
 * （ブローカー登録プレフィックスが {@code ["/topic"]} のみで {@code /queue} が欠落）。すなわち設計 §2.1 が「潜在的脆弱性」と
 * 警告した {@code configureMessageBroker} 二重呼び出しの順序依存は<b>実在</b>し、少なくとも本ブート順序では
 * {@code SignageWebSocketConfig}（{@code /topic} のみ）が後勝ちして {@code /queue} がブローカーから落ちている。
 * この状態では Principal 配線以前に {@code convertAndSendToUser}（{@code /queue} 宛）が構造的に成立しない。
 * 出陣（隊 1）は §2.1 のブローカー設定を {@code WebSocketConfig} へ<b>一本化</b>（{@code SignageWebSocketConfig} から
 * {@code configureMessageBroker} 削除）した上で、§2.3 の {@link StompPrincipal} {@code setUser} を行うこと。
 * 両者が揃って初めて本テストの全アサートが green 化する。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.websocket.WebSocketPrincipalWiringIntegrationTest#isDockerAvailable")
@DisplayName("§7.2 AC-5: STOMP Principal 未配線の欠陥実証（red 先行）")
class WebSocketPrincipalWiringIntegrationTest {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(false)
            .withTmpFs(Map.of("/var/lib/mysql", "rw"));

    static {
        if (isDockerAvailable()) {
            MYSQL.start();
        }
    }

    /** Redis は本テストの対象外のため Mock 化（キャッシュ・presence 等の外部依存を遮断）。 */
    @MockitoBean
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    AuthTokenService authTokenService;

    @Autowired
    SimpMessagingTemplate messagingTemplate;

    @Autowired
    SimpUserRegistry simpUserRegistry;

    @Autowired
    SimpleBrokerMessageHandler simpleBrokerMessageHandler;

    private WebSocketStompClient stompClient;

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @AfterEach
    void shutdown() {
        if (stompClient != null) {
            stompClient.stop();
        }
    }

    @Test
    @DisplayName("AC-5: CONNECT 後 SimpUserRegistry に登録され、convertAndSendToUser が到達する（現行は Principal 未配線 → red）")
    void userDestinationDelivery_requiresPrincipalWiring() throws Exception {
        long userId = 90001L;
        String jwt = authTokenService.issueAccessToken(userId, List.of("USER"));

        // ── 切り分け（green のはず）: /queue プレフィックスがブローカーに登録済みであること ──
        assertThat(simpleBrokerMessageHandler.getDestinationPrefixes())
                .as("/queue がブローカーに登録済みであること（§2.1 順序依存が red 原因でないことの切り分け）")
                .contains("/queue");

        // ── 実 STOMP CONNECT（Authorization ヘッダで JWT を渡す）──
        BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
        StompSession session = connect(jwt);
        session.subscribe("/user/queue/notifications", new StompFrameHandler() {
            @Override
            public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
                return Object.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                inbox.offer(payload);
            }
        });

        // ── AC-5 (1): SimpUserRegistry に接続ユーザーが登録される（Principal 未配線 → 登録されず null → red）──
        boolean registered = awaitUntil(
                () -> simpUserRegistry.getUser(String.valueOf(userId)) != null, Duration.ofSeconds(3));
        assertThat(registered)
                .as("CONNECT 後 SimpUserRegistry.getUser(userId) が非 null になること（Principal 配線後に成立）")
                .isTrue();

        // ── 同一ソース一致: Principal 名（getName）が JWT sub 由来の userId 文字列と一致すること（§2.3 是正設計 5）──
        assertThat(simpUserRegistry.getUser(String.valueOf(userId)))
                .as("登録された SimpUser の名前が userId 文字列（JWT sub 由来）であること")
                .isNotNull()
                .satisfies(u -> assertThat(u.getName()).isEqualTo(String.valueOf(userId)));

        // ── session.subscribe は非同期であり、SUBSCRIBE フレームがサーバのブローカーに登録される前に
        //    convertAndSendToUser が走ると宛先解決 0 件でメッセージが握りつぶされる（CI 高負荷時に顕在化する flaky の実根治）。
        //    SimpUserRegistry 経由でサーバ側に当該購読が実際に登録されたことを確認してから送信する。
        //    「/user/queue/notifications」の購読は Spring の実装により destination が /user プレフィックス無しで
        //    保持される場合があるため、実装差に強い endsWith 判定で照合する。
        boolean subscribed = awaitUntil(() -> {
            var simpUser = simpUserRegistry.getUser(String.valueOf(userId));
            if (simpUser == null) {
                return false;
            }
            for (SimpSession simpSession : simpUser.getSessions()) {
                for (SimpSubscription subscription : simpSession.getSubscriptions()) {
                    if (subscription.getDestination() != null
                            && subscription.getDestination().endsWith("/queue/notifications")) {
                        return true;
                    }
                }
            }
            return false;
        }, Duration.ofSeconds(10));
        assertThat(subscribed)
                .as("/user/queue/notifications 購読がサーバ側（SimpUserRegistry）に登録されること"
                        + "（購読完了前の送信は宛先解決 0 件で握りつぶされるため、送信前に必ず確認する）")
                .isTrue();

        // ── AC-5 (2): convertAndSendToUser が当該ユーザーに到達する（解決先 0 件 → 未達 → red）──
        messagingTemplate.convertAndSendToUser(
                String.valueOf(userId), "/queue/notifications", Map.of("type", "TEST", "message", "hello"));

        Object received = inbox.poll(10, TimeUnit.SECONDS);
        assertThat(received)
                .as("ユーザー宛通知が接続中のクライアントに届くこと（現行は Principal 未配線で解決先 0 件 → 未達 red）")
                .isNotNull();
    }

    private StompSession connect(String jwt) throws Exception {
        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        stompClient = new WebSocketStompClient(new SockJsClient(transports));
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        // ハンドシェイクのオリジン検査（WebSocketConfig の allowed-origins 既定）を満たす
        handshakeHeaders.add("Origin", "http://localhost:8080");

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + jwt);

        String url = "http://localhost:" + port + "/ws";
        return stompClient
                .connectAsync(url, handshakeHeaders, connectHeaders, new StompSessionHandlerAdapter() { })
                .get(5, TimeUnit.SECONDS);
    }

    private boolean awaitUntil(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }
}
