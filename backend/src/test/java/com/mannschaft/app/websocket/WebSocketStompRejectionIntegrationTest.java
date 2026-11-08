package com.mannschaft.app.websocket;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.websocket.StompErrorCode;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.lang.NonNull;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.ConnectionLostException;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261104-0337（B 案・BE）実接続の結合テスト。
 *
 * <p>実 STOMP スタック（RANDOM_PORT・実 Security フィルタ・実 {@code WebSocketAuthChannelInterceptor}・
 * 実購読 Interceptor 3 種・実 {@code StompErrorCodeErrorHandler}）に実 STOMP クライアント（生 WebSocket）で
 * 接続し、<b>クライアントが受信する ERROR フレームのヘッダ {@code x-error-code}</b> を検証する。
 * DB は Testcontainers MySQL（基底の singleton container）。認可サービス・DB はモックしない。</p>
 *
 * <ul>
 *   <li>0337-4: 未認証 UNAUTHENTICATED／権限なし FORBIDDEN／宛先不正 INVALID_DESTINATION を 3 Interceptor で別コード</li>
 *   <li>0337-8: 許可外 Origin・同一サイト別 Origin のハンドシェイク拒否</li>
 *   <li>0337-9: 無効 Bearer＋有効 Cookie／Bearer と Cookie が別ユーザー／他人の /user 通知を受信しない</li>
 *   <li>0337-10: 匿名 CONNECT は従来どおり成立し、match の購読可否は MatchVisibilityResolver に委譲される
 *       （現状、匿名は常に不可のため UNAUTHENTICATED で拒否され FORBIDDEN にはならない）</li>
 * </ul>
 *
 * <p>実 HTTP / WebSocket の接続契約を検査するため RANDOM_PORT で WebEnvironment を上書きし、
 * {@code @DirtiesContext(AFTER_CLASS)} で専用 context を閉じる（TEST_CONVENTION.md §3.1.2）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261104-0337 STOMP 認証ソース・拒否コード・Origin 制限の結合テスト")
class WebSocketStompRejectionIntegrationTest extends AbstractMySqlIntegrationTest {

    /** 許可オリジン（application.yml の mannschaft.allowed-origins 既定に含まれる）。 */
    private static final String ALLOWED_ORIGIN = "http://localhost:3000";

    private static final long USER_A = 991_301L;
    private static final long USER_B = 991_302L;
    private static final long OUTSIDER = 991_303L;
    /**
     * 無効 Bearer＋有効 Cookie の拒否テスト専用のユーザー。他テストの接続（USER_A 名義など）が
     * SimpUserRegistry に残っていても、この断定に混入しないよう他では使わない。
     */
    private static final long USER_REJECTED = 991_304L;

    @LocalServerPort
    private int port;

    @Autowired
    private AuthTokenService authTokenService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

    @Autowired
    private ChatChannelRepository chatChannelRepository;

    @Autowired
    private ChatChannelMemberRepository chatChannelMemberRepository;

    private final List<WebSocketStompClient> clients = new ArrayList<>();

    private final List<Connection> connections = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // client.stop() だけでは確立済みの STOMP セッションは閉じず、サーバ側の SimpUserRegistry に
        // 次のテストまで残る（同一 context を共有するため他テストの断定へ混入する）。明示的に DISCONNECT する。
        // 1件の失敗で後続の切断と clients.stop() が中断しないよう、全件を片付けきってから失敗をまとめて投げる。
        RuntimeException failure = null;
        for (Connection connection : connections) {
            CompletableFuture<StompSession> future = connection.session();
            // 拒否された CONNECT の接続 Future は、サーバの ERROR→切断で ConnectionLostException により例外完了する。
            // getNow/join は例外完了だと CompletionException を投げるため、正常完了した（＝確立した）接続だけを対象にする。
            if (!future.isDone() || future.isCompletedExceptionally()) {
                continue;
            }
            StompSession session = future.join();
            if (session.isConnected()) {
                try {
                    disconnectUnlessServerAlreadyClosed(connection, session);
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        }
        connections.clear();
        clients.forEach(WebSocketStompClient::stop);
        clients.clear();
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * DISCONNECT する。ただしサーバがすでに閉じた（閉じつつある）セッションは後片付けの対象外とする。
     *
     * <p>拒否 ERROR の後、サーバは非同期に接続を閉じる。{@code isConnected()} が true のまま {@code disconnect()} を
     * 呼ぶと、閉じたセッションへの送信失敗（{@link MessageDeliveryException}／{@link IllegalStateException}）になる競合がある。
     * しかも {@code disconnect()} は先に closing 状態へ入るため、その後はサーバ切断の通知
     * （{@link ConnectionLostException}）が handler へ届かず、失敗後の {@code isConnected()} も常に false で証拠にならない。
     * そこで DISCONNECT を送る<strong>前</strong>に、サーバ切断の積極的な証拠で判定する。サーバが ERROR を送っている
     * 接続に限り、{@link ConnectionLostException} の受信を期限内に確認できたら、サーバが閉じたものとして DISCONNECT しない。
     * 確認できなければ通常どおり DISCONNECT し、その失敗は握りつぶさず表に出す。</p>
     */
    private static void disconnectUnlessServerAlreadyClosed(Connection connection, StompSession session) {
        ErrorCapturingHandler handler = connection.handler();
        if (handler.error.isDone()) {
            try {
                handler.connectionLost.get(10, TimeUnit.SECONDS);
                return;
            } catch (Exception notConfirmed) {
                // サーバ切断を確認できない。通常どおり DISCONNECT して結果をそのまま表に出す
            }
        }
        session.disconnect();
    }

    /**
     * サーバ側の接続確立イベントを記録する試験用リスナー。
     *
     * <p>{@link SimpUserRegistry} への登録は {@link SessionConnectedEvent}（CONNECTED フレーム送出時）でのみ行われ、
     * {@link SessionConnectEvent} は CONNECT が inbound チャンネルを通過した後にしか発行されない。
     * 拒否した CONNECT についてこれらが一度も発行されないことを断定するために置く。</p>
     */
    @TestConfiguration
    static class ConnectEventRecorder {

        static final Queue<String> CONNECT_EVENTS = new ConcurrentLinkedQueue<>();

        @EventListener
        public void onConnect(SessionConnectEvent event) {
            CONNECT_EVENTS.add("CONNECT:" + (event.getUser() == null ? null : event.getUser().getName()));
        }

        @EventListener
        public void onConnected(SessionConnectedEvent event) {
            CONNECT_EVENTS.add("CONNECTED:" + (event.getUser() == null ? null : event.getUser().getName()));
        }
    }

    // ─── 接続ヘルパー ────────────────────────────────────────────

    /** サーバから届いた ERROR フレームのヘッダと、その後の接続断を捕捉するセッションハンドラ。 */
    private static final class ErrorCapturingHandler extends StompSessionHandlerAdapter {
        final CompletableFuture<StompHeaders> error = new CompletableFuture<>();
        final CompletableFuture<Throwable> closed = new CompletableFuture<>();
        /** サーバ切断（{@link ConnectionLostException}）を受けたときだけ完了する。他の transport error では完了しない。 */
        final CompletableFuture<Throwable> connectionLost = new CompletableFuture<>();

        @Override
        public void handleFrame(@NonNull StompHeaders headers, Object payload) {
            // セッションハンドラの handleFrame へ届くのは ERROR フレーム
            error.complete(headers);
        }

        @Override
        public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
            // ERROR 送出後にサーバが接続を閉じると ConnectionLostException 等でここに来る
            closed.complete(exception);
            if (exception instanceof ConnectionLostException) {
                connectionLost.complete(exception);
            }
        }
    }

    private record Connection(CompletableFuture<StompSession> session, ErrorCapturingHandler handler) {
        StompSession await() throws Exception {
            return session.get(60, TimeUnit.SECONDS);
        }

        StompHeaders awaitError() throws Exception {
            return handler.error.get(60, TimeUnit.SECONDS);
        }

        Throwable awaitClosed() throws Exception {
            return handler.closed.get(60, TimeUnit.SECONDS);
        }
    }

    /**
     * 生 WebSocket クライアント。Tomcat WebSocket クライアントのハンドシェイク IO タイムアウト既定（5 秒）は
     * 高負荷の CI／開発機で偽陰性を生むため延ばす（判定ロジックには影響しない）。
     */
    private static StandardWebSocketClient webSocketClient() {
        StandardWebSocketClient client = new StandardWebSocketClient();
        client.setUserProperties(Map.<String, Object>of("org.apache.tomcat.websocket.IO_TIMEOUT_MS", "60000"));
        return client;
    }

    /**
     * 生 WebSocket で STOMP 接続を開始する。
     *
     * @param origin        ハンドシェイクの Origin（null なら付けない）
     * @param cookieJwt     ハンドシェイクの access_token Cookie（null なら付けない）
     * @param authorization CONNECT の Authorization ヘッダ値（null なら付けない）
     */
    private Connection open(String origin, String cookieJwt, String authorization) {
        WebSocketStompClient client = new WebSocketStompClient(webSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        clients.add(client);

        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        if (origin != null) {
            handshake.add("Origin", origin);
        }
        if (cookieJwt != null) {
            handshake.add("Cookie", "access_token=" + cookieJwt);
        }
        StompHeaders connect = new StompHeaders();
        if (authorization != null) {
            connect.add("Authorization", authorization);
        }
        ErrorCapturingHandler handler = new ErrorCapturingHandler();
        CompletableFuture<StompSession> future =
                client.connectAsync("ws://localhost:" + port + "/ws/websocket", handshake, connect, handler);
        Connection connection = new Connection(future, handler);
        connections.add(connection);
        return connection;
    }

    private String jwt(long userId) {
        return authTokenService.issueAccessToken(userId, List.of("USER"));
    }

    /** SUBSCRIBE して拒否 ERROR フレームのヘッダを待つ。 */
    private StompHeaders subscribeExpectingError(Connection connection, String destination) throws Exception {
        StompSession session = connection.await();
        session.subscribe(destination, new StompSessionHandlerAdapter() { });
        return connection.awaitError();
    }

    private Long throwawayChannelWithMember(long memberUserId) {
        ChatChannelEntity channel = chatChannelRepository.save(ChatChannelEntity.builder()
                .channelType(ChannelType.TEAM_PRIVATE)
                .teamId(991_900L)
                .name("CMP-0337-" + UUID.randomUUID())
                .isPrivate(true)
                .createdBy(memberUserId)
                .build());
        chatChannelMemberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(channel.getId())
                .userId(memberUserId)
                .role(ChannelMemberRole.MEMBER)
                .build());
        return channel.getId();
    }

    private static StompFrameHandler collecting(BlockingQueue<Object> sink) {
        return new StompFrameHandler() {
            @Override
            public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
                return Object.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                sink.offer(payload != null ? payload : "NULL");
            }
        };
    }

    // ─── 0337-4: 3 Interceptor の拒否コード ─────────────────────────


    @Test
    @DisplayName("chat: 匿名の購読 → UNAUTHENTICATED（message は従来文言）")
    void chat未認証() throws Exception {
        Long channelId = throwawayChannelWithMember(USER_A);
        StompHeaders error = subscribeExpectingError(open(null, null, null), "/topic/channels/" + channelId);

        assertThat(error.getFirst(StompErrorCode.HEADER)).isEqualTo("UNAUTHENTICATED");
        assertThat(error.getFirst("message")).isEqualTo("チャネルの購読権限がありません");
    }

    @Test
    @DisplayName("chat: 認証済み非メンバーの購読 → FORBIDDEN")
    void chat権限なし() throws Exception {
        Long channelId = throwawayChannelWithMember(USER_A);
        StompHeaders error = subscribeExpectingError(
                open(null, null, "Bearer " + jwt(OUTSIDER)), "/topic/channels/" + channelId);

        assertThat(error.getFirst(StompErrorCode.HEADER)).isEqualTo("FORBIDDEN");
        assertThat(error.getFirst("message")).isEqualTo("チャネルの購読権限がありません");
    }

    @Test
    @DisplayName("chat: channelId が数値範囲外 → INVALID_DESTINATION")
    void chat宛先不正() throws Exception {
        StompHeaders error = subscribeExpectingError(
                open(null, null, "Bearer " + jwt(OUTSIDER)), "/topic/channels/99999999999999999999999");

        assertThat(error.getFirst(StompErrorCode.HEADER)).isEqualTo("INVALID_DESTINATION");
    }

    @Test
    @DisplayName("緊急休業: 匿名の購読 → UNAUTHENTICATED")
    void 緊急休業未認証() throws Exception {
        StompHeaders error = subscribeExpectingError(
                open(null, null, null), "/topic/teams/991900/emergency-closures/1/confirmations");

        assertThat(error.getFirst(StompErrorCode.HEADER)).isEqualTo("UNAUTHENTICATED");
        assertThat(error.getFirst("message")).isEqualTo("臨時休業確認状況の購読権限がありません");
    }

    @Test
    @DisplayName("緊急休業: Cookie 認証済みだが当該チーム管理者でない → FORBIDDEN（リロード後の権限なしと同じ経路）")
    void 緊急休業権限なし() throws Exception {
        StompHeaders error = subscribeExpectingError(
                open(ALLOWED_ORIGIN, jwt(OUTSIDER), null), "/topic/teams/991900/emergency-closures/1/confirmations");

        assertThat(error.getFirst(StompErrorCode.HEADER)).isEqualTo("FORBIDDEN");
        assertThat(error.getFirst("message")).isEqualTo("臨時休業確認状況の購読権限がありません");
    }

    @Test
    @DisplayName("緊急休業: teamId が数値範囲外 → INVALID_DESTINATION")
    void 緊急休業宛先不正() throws Exception {
        StompHeaders error = subscribeExpectingError(open(null, null, "Bearer " + jwt(OUTSIDER)),
                "/topic/teams/99999999999999999999999/emergency-closures/1/confirmations");

        assertThat(error.getFirst(StompErrorCode.HEADER)).isEqualTo("INVALID_DESTINATION");
    }

    @Test
    @DisplayName("match: 認証済みで閲覧不可 → FORBIDDEN")
    void match権限なし() throws Exception {
        StompHeaders error = subscribeExpectingError(open(null, null, "Bearer " + jwt(OUTSIDER)),
                "/topic/matches/" + UUID.randomUUID() + "/live");

        assertThat(error.getFirst(StompErrorCode.HEADER)).isEqualTo("FORBIDDEN");
        assertThat(error.getFirst("message")).isEqualTo("ライブ観戦の購読権限がありません");
    }

    @Test
    @DisplayName("0337-10: match: 匿名購読は実 MatchVisibilityResolver（匿名は常に不可）に委譲され、FORBIDDEN ではなく UNAUTHENTICATED で拒否")
    void match未認証() throws Exception {
        StompHeaders error = subscribeExpectingError(open(null, null, null),
                "/topic/matches/" + UUID.randomUUID() + "/live");

        assertThat(error.getFirst(StompErrorCode.HEADER)).isEqualTo("UNAUTHENTICATED");
    }

    // ─── 0337-8: CSWSH（Origin 制限） ──────────────────────────────


    // 任意の例外（タイムアウト・サーバ障害）で偽緑にならないよう、ハンドシェイクの HTTP ステータスそのものを
    // 生ソケットで読み取って断定する。許可 Origin が 101 になる対照と必ず対にする。

    @Test
    @DisplayName("許可外 Origin（別サイト）からのハンドシェイクは Cookie があっても HTTP 403 で拒否される")
    void 別サイトOriginは403() throws Exception {
        assertThat(handshakeStatus("https://attacker.example", jwt(USER_A)))
                .as("許可外 Origin のアップグレード要求は 403").isEqualTo(403);
    }

    @Test
    @DisplayName("同一サイト別 Origin（同ホスト別ポート）からのハンドシェイクも HTTP 403 で拒否される")
    void 同一サイト別Originは403() throws Exception {
        assertThat(handshakeStatus("http://localhost:3999", jwt(USER_A)))
                .as("同一サイトでも許可リスト外の Origin は 403").isEqualTo(403);
    }

    @Test
    @DisplayName("許可 Origin からのハンドシェイクは HTTP 101 で成立し、STOMP 接続も確立する（対照）")
    void 許可Originは101で成立() throws Exception {
        assertThat(handshakeStatus(ALLOWED_ORIGIN, jwt(USER_A)))
                .as("許可 Origin のアップグレード要求は 101 Switching Protocols").isEqualTo(101);

        StompSession session = open(ALLOWED_ORIGIN, jwt(USER_A), null).await();
        assertThat(session.isConnected()).isTrue();
        // 接続確立イベント記録の対照: 成立した接続ではサーバが CONNECTED 送出前に SessionConnectedEvent を発行する
        assertThat(ConnectEventRecorder.CONNECT_EVENTS)
                .as("成立した接続は Cookie のユーザー名義で SessionConnectedEvent が記録される（記録器の対照）")
                .contains("CONNECTED:" + USER_A);
    }

    /**
     * 生ソケットで WebSocket アップグレード要求（{@code /ws/websocket}）を送り、応答ステータスコードを返す。
     * Origin・Cookie は任意に指定できる（ブラウザからの越境接続を再現）。
     */
    private int handshakeStatus(String origin, String cookieJwt) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(60_000);
            String request = "GET /ws/websocket HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Origin: " + origin + "\r\n"
                    + "Cookie: access_token=" + cookieJwt + "\r\n"
                    + "\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = in.readLine();
            assertThat(statusLine).as("ステータス行が返ること").isNotNull().startsWith("HTTP/1.1 ");
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    // ─── 0337-9: 認証ソースの優先順位 ─────────────────────────────


    @Test
    @DisplayName("無効 Bearer＋有効 Cookie → Cookie へ黙って切り替えず CONNECT を UNAUTHENTICATED で拒否")
    void 無効Bearerと有効Cookieは拒否() throws Exception {
        Connection connection = open(ALLOWED_ORIGIN, jwt(USER_REJECTED), "Bearer not-a-valid-jwt");

        StompHeaders error = connection.awaitError();
        assertThat(error.getFirst(StompErrorCode.HEADER)).isEqualTo("UNAUTHENTICATED");

        // サーバは ERROR 送出後に接続を閉じる。閉じ終わるまで待ってから「一度も登録されなかった」ことを断定する
        // （登録は SessionConnectedEvent でのみ起こるため、接続確立イベントの不在で時間窓が無いことを示す）。
        assertThat(connection.awaitClosed()).as("ERROR の後にサーバが接続を閉じる").isNotNull();
        String cookieUser = String.valueOf(USER_REJECTED);
        assertThat(ConnectEventRecorder.CONNECT_EVENTS)
                .as("拒否した CONNECT は SessionConnectEvent／SessionConnectedEvent を一度も発行しない")
                .doesNotContain("CONNECT:" + cookieUser, "CONNECTED:" + cookieUser);
        assertThat(simpUserRegistry.getUser(cookieUser))
                .as("拒否された接続は Cookie のユーザー名義で登録されない").isNull();
    }

    @Test
    @DisplayName("Bearer(A) と Cookie(B) が別ユーザー → A 名義で確立し、B 宛 /user 通知は受信しない")
    void Bearer優先で他人の通知を受信しない() throws Exception {
        Long channelOfA = throwawayChannelWithMember(USER_A);
        Long channelOfB = throwawayChannelWithMember(USER_B);
        Connection connection = open(ALLOWED_ORIGIN, jwt(USER_B), "Bearer " + jwt(USER_A));
        StompSession session = connection.await();

        // session userId が A であること: A のチャネルは購読でき、B のチャネルは購読できない（後段で検証）
        BlockingQueue<Object> userInbox = new LinkedBlockingQueue<>();
        session.subscribe("/user/queue/notifications", collecting(userInbox));
        BlockingQueue<Object> channelInbox = new LinkedBlockingQueue<>();
        session.subscribe("/topic/channels/" + channelOfA, collecting(channelInbox));

        // B 宛を先に流してから A 宛が届くまで送り続ける（購読登録の非同期待ち）
        messagingTemplate.convertAndSendToUser(String.valueOf(USER_B), "/queue/notifications",
                Map.of("to", "B"));
        Object received = null;
        for (int i = 0; i < 150 && received == null; i++) {
            messagingTemplate.convertAndSendToUser(String.valueOf(USER_A), "/queue/notifications",
                    Map.of("to", "A"));
            received = userInbox.poll(200, TimeUnit.MILLISECONDS);
        }
        assertThat(received).as("Bearer のユーザー A 宛の通知は届く").isNotNull();
        messagingTemplate.convertAndSendToUser(String.valueOf(USER_B), "/queue/notifications",
                Map.of("to", "B"));
        Thread.sleep(1000);
        List<Object> rest = new ArrayList<>();
        rest.add(received);
        userInbox.drainTo(rest);
        assertThat(rest).as("Cookie 側ユーザー B 宛の通知は受信しない")
                .allSatisfy(payload -> assertThat(payload.toString()).doesNotContain("to=B"));

        assertThat(simpUserRegistry.getUser(String.valueOf(USER_B)))
                .as("Cookie 側ユーザー B の名義では登録されない").isNull();

        messagingTemplate.convertAndSend("/topic/channels/" + channelOfA, Map.of("text", "for-A"));
        assertThat(channelInbox.poll(20, TimeUnit.SECONDS))
                .as("session userId=A なので A のチャネル購読は成立する").isNotNull();

        session.subscribe("/topic/channels/" + channelOfB, new StompSessionHandlerAdapter() { });
        StompHeaders error = connection.awaitError();
        assertThat(error.getFirst(StompErrorCode.HEADER))
                .as("session userId は B ではないので B のチャネルは FORBIDDEN").isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("0337-10: 資格情報なしの CONNECT は従来どおり成立する（匿名接続許容）")
    void 匿名CONNECTは成立() throws Exception {
        StompSession session = open(null, null, null).await();

        assertThat(session.isConnected()).isTrue();
    }
}
