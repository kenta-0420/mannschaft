package com.mannschaft.app.chat.ws;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.lang.NonNull;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-11【red 先行・欠陥実証】チャット購読 SUBSCRIBE 認可の欠落（既存 IDOR）を実証する統合テスト。
 *
 * <p>設計書 {@code docs/architecture/websocket_external_broker_valkey.md} §2.6 / AC-11。
 * 現行 origin/main の WebSocket STOMP には SUBSCRIBE 認可インターセプタが
 * {@code MatchLiveSubscriptionInterceptor}・{@code EmergencyClosureSubscriptionInterceptor} の
 * 2 destination にしか無く、<b>チャット {@code /topic/channels/{channelId}} には購読認可が存在しない</b>。
 * その結果、認証済みユーザーであれば <b>自分がメンバーでない任意の channelId を購読でき、
 * 他チームのチャット本文を受信できる</b>（既存 IDOR）。</p>
 *
 * <h3>本テストの red/green 期待</h3>
 * <ul>
 *   <li><b>{@link #非メンバーはチャネル購読を拒否され本文を受信できない()}（red・欠陥実証）</b>:
 *       非メンバーの認証済みユーザーが SUBSCRIBE すると<b>拒否される（ERROR フレーム/購読不成立）</b>ことを期待する。
 *       <b>現行コードでは購読が成立しメッセージ本文まで受信できてしまうため、このテストは失敗する（＝欠陥の実証）。</b>
 *       是正実装（{@code ChatChannelSubscriptionInterceptor}・隊 6）投入後に green 化する。</li>
 *   <li><b>{@link #メンバーはチャネル購読が成立し本文を受信できる()}（非回帰・現行 green）</b>:
 *       チャネルメンバーは購読が成立しメッセージ本文を受信できる。
 *       現行コードでも green のはず（認可が無いので当然通る）で、是正後も green を維持すべき非回帰条件。</li>
 * </ul>
 *
 * <h3>テスト品質方針（モック禁止）</h3>
 * <ul>
 *   <li>{@code @SpringBootTest(webEnvironment = RANDOM_PORT)} で<b>実 STOMP スタック</b>
 *       （SimpleBroker + 実 Security フィルタ + 実 {@code WebSocketAuthChannelInterceptor}）を起動。</li>
 *   <li>実 STOMP クライアント（{@link WebSocketStompClient} + 生 WebSocket・SockJS 未使用）で購読・受信を検証。</li>
 *   <li>CONNECT の JWT は実 {@link AuthTokenService#issueAccessToken} で発行（認証確立も実経路）。</li>
 *   <li>DB は Testcontainers MySQL（基底 {@link AbstractMySqlIntegrationTest} の singleton container・tmpfs）。
 *       DB・認可・自 Bean はモックしない。</li>
 *   <li>seed 汚染回避（{@code feedback_authz_e2e_seed_membership_pollution}）のため、
 *       チャネル・メンバー・userId は<b>本テスト内で使い捨て新規作成</b>する（大きな userId で seed と衝突回避）。</li>
 * </ul>
 *
 * <p>基底の共有 MOCK コンテキストとは別に RANDOM_PORT コンテキストを起動するため、
 * {@code @DirtiesContext(AFTER_CLASS)} で TestContext キャッシュ分裂の波及を防ぐ（設計書 §7.1.2）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("AC-11 チャット購読 SUBSCRIBE 認可（red 先行・IDOR 実証）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ChatChannelSubscriptionAuthzIntegrationTest extends AbstractMySqlIntegrationTest {

    /** メンバー（購読成立すべきユーザー）。seed と衝突しない大きな値を使う。 */
    private static final long MEMBER_USER_ID = 990_101L;

    /** 非メンバー（購読拒否されるべき攻撃者ユーザー）。channel には一切属さない。 */
    private static final long NON_MEMBER_USER_ID = 990_102L;

    @LocalServerPort
    private int port;

    @Autowired
    private AuthTokenService authTokenService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private ChatChannelRepository chatChannelRepository;

    @Autowired
    private ChatChannelMemberRepository chatChannelMemberRepository;

    private WebSocketStompClient stompClient;

    /** 使い捨てチャネル ID（テストごとに新規採番）。 */
    private Long channelId;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        // 配信側（Boot の STOMP Jackson 変換）と噛み合わせるため Jackson を使う。
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        // 使い捨てチャネルを新規作成し、メンバー行は MEMBER_USER_ID のみ登録する。
        // 非メンバー（NON_MEMBER_USER_ID）には membership 行を作らない＝購読権限が無いことを表す。
        ChatChannelEntity channel = chatChannelRepository.save(
                ChatChannelEntity.builder()
                        .channelType(ChannelType.TEAM_PRIVATE)
                        .teamId(990_900L)
                        .name("AC-11-throwaway-" + UUID.randomUUID())
                        .isPrivate(true)
                        .createdBy(MEMBER_USER_ID)
                        .build());
        this.channelId = channel.getId();

        chatChannelMemberRepository.save(
                ChatChannelMemberEntity.builder()
                        .channelId(channelId)
                        .userId(MEMBER_USER_ID)
                        .role(ChannelMemberRole.MEMBER)
                        .build());
    }

    @AfterEach
    void tearDown() {
        if (stompClient != null) {
            stompClient.stop();
        }
    }

    /**
     * 【red・欠陥実証】非メンバーの認証済みユーザーが {@code /topic/channels/{channelId}} を SUBSCRIBE すると
     * 拒否される（ERROR フレーム/購読不成立）べきであり、本文を受信できてはならない。
     *
     * <p><b>現行 origin/main では失敗する</b>: チャット購読認可が無いため SUBSCRIBE が成立し、
     * 配信された他チームのチャット本文を非メンバーが受信できてしまう（IDOR の実証）。</p>
     */
    @Test
    @DisplayName("red: 非メンバーはチャネル購読を拒否され本文を受信できない（現行はIDORで失敗する）")
    void 非メンバーはチャネル購読を拒否され本文を受信できない() throws Exception {
        String destination = "/topic/channels/" + channelId;
        String token = authTokenService.issueAccessToken(NON_MEMBER_USER_ID, List.of("USER"));

        BlockingQueue<Object> received = new LinkedBlockingQueue<>();
        CountDownLatch subscriptionErrorLatch = new CountDownLatch(1);

        StompSession session = connect(token, subscriptionErrorLatch);
        session.subscribe(destination, collectingHandler(received));

        // SUBSCRIBE フレームがブローカーへ登録されるのを待ってから配信する。
        Thread.sleep(700);

        String probe = "IDOR-PROBE-" + UUID.randomUUID();
        messagingTemplate.convertAndSend(destination,
                Map.of("type", "MESSAGE_CREATED", "data", Map.of("id", 9990001L, "text", probe)));

        // 【主アサート】非メンバーは他チームのチャット本文を受信できてはならない。
        // 現行コードでは受信できてしまう（poll が非 null）ため、この isNull() が失敗して red になる。
        Object leaked = received.poll(3, TimeUnit.SECONDS);
        assertThat(leaked)
                .as("非メンバーが他チームのチャット本文を受信できてはならない（既存IDOR・AC-11）。"
                        + "現行main は購読認可が無く受信できてしまうため red。受信値=%s", leaked)
                .isNull();

        // 【補強アサート】SUBSCRIBE 自体が拒否（ERROR フレーム/購読不成立）されるべき。
        // 現行コードでは拒否されずエラーが発生しないため、この await も false となり red を補強する。
        boolean rejected = subscriptionErrorLatch.await(2, TimeUnit.SECONDS);
        assertThat(rejected)
                .as("非メンバーの SUBSCRIBE は拒否される（ERROR フレーム/購読不成立）べき。"
                        + "現行main は認可インターセプタが無く拒否されないため red")
                .isTrue();
    }

    /**
     * 【非回帰・現行 green】チャネルメンバーは購読が成立し、配信されたチャット本文を受信できる。
     *
     * <p>現行 origin/main でも認可が無い＝当然通るため green。是正実装（隊 6）投入後も
     * 「メンバーは通す」ことを担保する非回帰条件。</p>
     */
    @Test
    @DisplayName("green(非回帰): メンバーはチャネル購読が成立し本文を受信できる")
    void メンバーはチャネル購読が成立し本文を受信できる() throws Exception {
        String destination = "/topic/channels/" + channelId;
        String token = authTokenService.issueAccessToken(MEMBER_USER_ID, List.of("USER"));

        BlockingQueue<Object> received = new LinkedBlockingQueue<>();
        CountDownLatch subscriptionErrorLatch = new CountDownLatch(1);

        StompSession session = connect(token, subscriptionErrorLatch);
        session.subscribe(destination, collectingHandler(received));

        // SUBSCRIBE フレームがブローカーへ登録されるのを待ってから配信する。
        Thread.sleep(700);

        String probe = "MEMBER-OK-" + UUID.randomUUID();
        messagingTemplate.convertAndSend(destination,
                Map.of("type", "MESSAGE_CREATED", "data", Map.of("id", 9990002L, "text", probe)));

        Object payload = received.poll(5, TimeUnit.SECONDS);
        assertThat(payload)
                .as("メンバーは購読が成立し配信本文を受信できる（非回帰）")
                .isNotNull();
        assertThat(subscriptionErrorLatch.getCount())
                .as("メンバーの購読は拒否されない")
                .isEqualTo(1L);
    }

    /**
     * 実 STOMP クライアントで CONNECT する。JWT は CONNECT フレームの Authorization ネイティブヘッダで送る
     * （{@code WebSocketAuthChannelInterceptor} が読み取り session userId を確定する実経路）。
     * SUBSCRIBE 拒否（ERROR フレーム）を検知したら {@code errorLatch} を落とす。
     */
    private StompSession connect(String token, CountDownLatch errorLatch) throws Exception {
        String url = "ws://localhost:" + port + "/ws/websocket";
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        StompSessionHandlerAdapter handler = new StompSessionHandlerAdapter() {
            @Override
            public void handleException(@NonNull StompSession session, StompCommand command,
                                        @NonNull StompHeaders headers, @NonNull byte[] payload,
                                        @NonNull Throwable exception) {
                // SUBSCRIBE 認可拒否は MessagingException → ERROR フレームとしてここに届く。
                errorLatch.countDown();
            }

            @Override
            public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
                // ERROR フレーム後のセッションクローズもここに来うる。拒否のシグナルとして扱う。
                errorLatch.countDown();
            }
        };

        return stompClient
                .connectAsync(url, new WebSocketHttpHeaders(), connectHeaders, handler)
                .get(5, TimeUnit.SECONDS);
    }

    /** 受信フレームをキューに積むだけのハンドラ。ペイロードは Jackson で {@code Map} に復元される。 */
    private StompFrameHandler collectingHandler(BlockingQueue<Object> sink) {
        return new StompFrameHandler() {
            @Override
            @NonNull
            public Type getPayloadType(@NonNull StompHeaders headers) {
                return Object.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                sink.offer(payload != null ? payload : "NULL");
            }
        };
    }
}
