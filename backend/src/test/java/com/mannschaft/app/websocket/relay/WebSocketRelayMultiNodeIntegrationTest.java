package com.mannschaft.app.websocket.relay;

import com.mannschaft.app.MannschaftApplication;
import com.mannschaft.app.auth.service.AuthTokenService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.lang.NonNull;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §7.1.2 2 コンテキスト @SpringBootTest — 実 STOMP スタックでのマルチノード relay 到達を検証する
 * （AC-1 実到達 / AC-2 ユーザー宛・非漏洩 / AC-7 サイネージ）。
 *
 * <p>単一 JVM 内で {@code RANDOM_PORT} のフルコンテキストを<b>2 つ</b>起動し、<b>1 個の Valkey Testcontainer を共有</b>する
 * （§7.1.2）。1 つ目はフレームワーク管理（{@code @DynamicPropertySource}）、2 つ目は {@link SpringApplicationBuilder} で
 * <b>明示起動・明示 close</b>し TestContext キャッシュに残さない。{@code @DirtiesContext(AFTER_CLASS)} で後始末する。
 * MySQL は tmpfs 共有（{@code project_testcontainers_mysql_tmpfs_fix}）。nodeId は起動時 UUID のため両コンテキストで自然に異なる（§4.4）。</p>
 *
 * <h3>red 駆動（skeleton は relay Bean 未生成 = ノード間ファンアウトなし）</h3>
 * <ul>
 *   <li><b>AC-1 実到達</b>: ノード B 発 {@code convertAndSend("/topic/...")} をノード A 接続クライアントが受信（relay 無し → 未達 red）。</li>
 *   <li><b>AC-7 サイネージ</b>: {@code /topic/signage/.../update} も同様に跨ノード到達（red）。</li>
 *   <li><b>AC-2 ユーザー宛・非漏洩</b>: ユーザー A を<b>ノード B のみ</b>に接続し、<b>ノード A から</b> A 宛送信 → A（ノード B）に届き、
 *       ノード A 上の別ユーザー C には届かない（relay + Principal 未実装 → 未達 red。両セッションが別ノードであることは別ポート接続で構造的に保証）。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.websocket.relay.WebSocketRelayMultiNodeIntegrationTest#isDockerAvailable")
@DisplayName("§7.1.2 2 コンテキスト 実 STOMP マルチノード relay（AC-1 実到達 / AC-2 / AC-7）")
// 環境注記: デフォルト（PER_METHOD）のテストインスタンスライフサイクルでは、静的 @BeforeAll は
// SpringExtension による Node A の ApplicationContext 準備（postProcessTestInstance）より先に実行される。
// そのため startNodeB() 実行時点では Node A の ddl-auto=create によるスキーマ作成が未完了で、
// Node B（ddl-auto=none）がテーブル未作成エラーで起動失敗する。PER_CLASS に変更すると
// テストインスタンス生成・SpringExtension のコンテキスト準備が @BeforeAll より先に走るため、
// Node A のスキーマ作成完了後に Node B が起動する順序を保証できる。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketRelayMultiNodeIntegrationTest {

    // 環境注記: Wait.forListeningPort() は docker exec 経由の内部ポート確認を伴うが、
    // 開発機の Docker TCP プロキシ環境では exec のストリームハイジャックが正しく中継されず
    // ContainerLaunchException（内部チェックのみタイムアウト）が発生する（外部からの TCP 到達性は問題ない）。
    // ログメッセージ待機（docker logs 経由・exec 不要）に切り替えて回避する（CI の素の Docker でも問題なく動作）。
    @SuppressWarnings("resource")
    static final GenericContainer<?> VALKEY = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections tcp.*\\n", 1)
                    .withStartupTimeout(Duration.ofSeconds(120)));

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(false)
            .withTmpFs(Map.of("/var/lib/mysql", "rw"));

    static {
        if (isDockerAvailable()) {
            VALKEY.start();
            MYSQL.start();
        }
    }

    /** 2 つ目のノード（明示起動・明示 close）。 */
    private static ConfigurableApplicationContext nodeBContext;
    private static int nodeBPort;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        // ノード A（フレームワーク管理）: 共有 Valkey + 共有 MySQL + relay ON
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getFirstMappedPort());
        registry.add("mannschaft.websocket.relay.enabled", () -> "true");
    }

    @LocalServerPort
    int nodeAPort;

    @Autowired
    SimpMessagingTemplate templateA;

    @Autowired
    AuthTokenService authTokenService;

    private final List<WebSocketStompClient> clients = new ArrayList<>();

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void startNodeB() {
        if (!isDockerAvailable()) {
            return;
        }
        // 2 つ目のコンテキストを明示起動（同一 Valkey / 同一 MySQL・スキーマはノード A が作成済みのため ddl-auto=none）
        // 環境注記: SpringApplicationBuilder#properties(...) は最低優先度（defaultProperties）で登録されるため、
        // application.yml の明示的な server.port: 8080 に上書きされ PortInUseException になる。
        // コマンドライン引数（--server.port=0 等）は Spring Boot の設定ソース優先順位が最も高く、
        // application.yml の値を確実に上書きできるため run(String...) 引数として渡す。
        nodeBContext = new SpringApplicationBuilder(MannschaftApplication.class)
                .profiles("test")
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword(),
                        "--spring.jpa.hibernate.ddl-auto=none",
                        "--spring.data.redis.host=" + VALKEY.getHost(),
                        "--spring.data.redis.port=" + VALKEY.getFirstMappedPort(),
                        "--mannschaft.websocket.relay.enabled=true");
        nodeBPort = Integer.parseInt(nodeBContext.getEnvironment().getProperty("local.server.port", "0"));
    }

    @AfterAll
    static void stopNodeB() {
        if (nodeBContext != null) {
            nodeBContext.close();
        }
    }

    @AfterEach
    void stopClients() {
        for (WebSocketStompClient c : clients) {
            c.stop();
        }
        clients.clear();
    }

    @Test
    @DisplayName("前提: 2 コンテキストが別ポート（別ノード）で起動していること（AC-2 の偽陰性防止）")
    void twoNodesAreDistinct() {
        assertThat(nodeBPort).isPositive();
        assertThat(nodeBPort)
                .as("ノード A とノード B が別ポート＝別ノードであること（同一ノードだと relay を通らず偽陰性の緑になる・§7.4.2）")
                .isNotEqualTo(nodeAPort);
    }

    @Test
    @DisplayName("AC-1: ノード B 発の /topic ブロードキャストがノード A 接続クライアントに到達する（relay 無し → red）")
    void broadcast_crossNode_reaches() throws Exception {
        BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
        StompSession sessionA = connect(nodeAPort, null);
        sessionA.subscribe("/topic/channels/999", frameHandler(inbox));
        Thread.sleep(300); // SUBSCRIBE 伝播待ち

        // ノード B（別コンテキスト）から配信
        SimpMessagingTemplate templateB = nodeBContext.getBean(SimpMessagingTemplate.class);
        templateB.convertAndSend("/topic/channels/999", Map.of("type", "MESSAGE_CREATED", "text", "跨ノード"));

        assertThat(inbox.poll(4, TimeUnit.SECONDS))
                .as("ノード B 発のブロードキャストがノード A のクライアントに届くこと（relay 実装後に成立）")
                .isNotNull();
    }

    @Test
    @DisplayName("AC-7: ノード B 発の /topic/signage/.../update がノード A 接続クライアントに到達する（red）")
    void signageBroadcast_crossNode_reaches() throws Exception {
        BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
        StompSession sessionA = connect(nodeAPort, null);
        sessionA.subscribe("/topic/signage/screen-1/update", frameHandler(inbox));
        Thread.sleep(300);

        SimpMessagingTemplate templateB = nodeBContext.getBean(SimpMessagingTemplate.class);
        templateB.convertAndSend("/topic/signage/screen-1/update", Map.of("type", "SLIDE_UPDATE"));

        assertThat(inbox.poll(4, TimeUnit.SECONDS))
                .as("サイネージ topic も単一ブローカーマージにより relay 対象となり跨ノード到達すること（§2.1 / AC-7）")
                .isNotNull();
    }

    @Test
    @DisplayName("AC-2: ユーザー宛がノードを跨いで到達し、他ユーザーには漏洩しない（relay + Principal 未実装 → red）")
    void userDestination_crossNode_reachesTarget_notLeakToOthers() throws Exception {
        long userA = 90101L;
        long userC = 90102L;

        // ユーザー A は「ノード B のみ」に接続する
        BlockingQueue<Object> inboxA = new LinkedBlockingQueue<>();
        StompSession sessionA = connect(nodeBPort, authTokenService.issueAccessToken(userA, List.of("USER")));
        sessionA.subscribe("/user/queue/notifications", frameHandler(inboxA));

        // ユーザー C は「ノード A」に接続する（漏洩監視）
        BlockingQueue<Object> inboxC = new LinkedBlockingQueue<>();
        StompSession sessionC = connect(nodeAPort, authTokenService.issueAccessToken(userC, List.of("USER")));
        sessionC.subscribe("/user/queue/notifications", frameHandler(inboxC));
        Thread.sleep(300);

        // ノード A から A 宛に送信（A はノード A に居ないため relay 必須）
        templateA.convertAndSendToUser(String.valueOf(userA), "/queue/notifications",
                Map.of("type", "TEST", "message", "A 宛"));

        assertThat(inboxA.poll(4, TimeUnit.SECONDS))
                .as("ユーザー A（ノード B 接続）に、ノード A 発のユーザー宛通知が到達すること（relay + Principal 実装後に成立）")
                .isNotNull();
        assertThat(inboxC.poll(1, TimeUnit.SECONDS))
                .as("別ユーザー C には漏洩しないこと")
                .isNull();
    }

    // ───────────────────────── ヘルパ ─────────────────────────

    private StompFrameHandler frameHandler(BlockingQueue<Object> inbox) {
        return new StompFrameHandler() {
            @Override
            public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
                return Object.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                inbox.offer(payload);
            }
        };
    }

    private StompSession connect(int port, String jwtOrNull) throws Exception {
        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        WebSocketStompClient client = new WebSocketStompClient(new SockJsClient(transports));
        client.setMessageConverter(new MappingJackson2MessageConverter());
        clients.add(client);

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", "http://localhost:8080");

        StompHeaders connectHeaders = new StompHeaders();
        if (jwtOrNull != null) {
            connectHeaders.add("Authorization", "Bearer " + jwtOrNull);
        }

        String url = "http://localhost:" + port + "/ws";
        return client.connectAsync(url, handshakeHeaders, connectHeaders, new StompSessionHandlerAdapter() { })
                .get(5, TimeUnit.SECONDS);
    }
}
