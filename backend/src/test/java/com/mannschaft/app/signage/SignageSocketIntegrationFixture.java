package com.mannschaft.app.signage;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.signage.entity.SignageAccessTokenEntity;
import com.mannschaft.app.signage.entity.SignageScreenEntity;
import com.mannschaft.app.signage.repository.SignageAccessTokenRepository;
import com.mannschaft.app.signage.repository.SignageScreenRepository;
import com.mannschaft.app.signage.websocket.SignageWebSocketPublisher;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 掲示WSの実TCP試練で共有する所有fixture。全fixtureをcommitし、全client停止後に所有行だけ回収する。 */
abstract class SignageSocketIntegrationFixture extends AbstractMySqlIntegrationTest {
    @Autowired SignageScreenRepository screens;
    @Autowired SignageAccessTokenRepository tokens;
    @Autowired SignageWebSocketPublisher publisher;
    @Autowired SimpleBrokerMessageHandler broker;
    @Autowired SimpMessagingTemplate messaging;
    @Autowired TestRestTemplate rest;
    @Autowired AuthTokenService jwtTokens;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;
    @PersistenceContext EntityManager em;

    final List<WebSocketStompClient> clients = new ArrayList<>();
    final List<StompSession> sessions = new ArrayList<>();
    final List<Long> tokenIds = new ArrayList<>();
    final List<Long> screenIds = new ArrayList<>();
    final List<Long> ownedUsers = new ArrayList<>();
    Long teamId;
    Long organizationId;
    SignageScreenEntity screen;
    SignageAccessTokenEntity token;
    SignageAccessTokenEntity otherToken;

    @BeforeEach
    void fixture() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (Long actor : List.of(910001L, 910002L, 920001L)) {
                assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM users WHERE id=:id")
                        .setParameter("id", actor).getSingleResult()).longValue()).isZero();
                MembershipTestHelper.insertActiveUser(em, actor);
                ownedUsers.add(actor);
            }
            teamId = TeamOrgFixtureHelper.insertTeam(em, "掲示WS専用チーム", "sg-ws-" + UUID.randomUUID().toString().substring(0, 24));
            organizationId = TeamOrgFixtureHelper.insertOrganization(em, "掲示WS別組織", "sg-ws-" + UUID.randomUUID().toString().substring(0, 24));
            MembershipTestHelper.insertMembership(em, 910001L, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, 910002L, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, 910002L, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, 920001L, ScopeType.ORGANIZATION, organizationId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, 920001L, "ADMIN", null, organizationId);
            screen = screens.saveAndFlush(SignageScreenEntity.builder()
                    .scopeType("TEAM").scopeId(teamId).name("専用掲示WS試練")
                    .createdBy(910002L).build());
            screenIds.add(screen.getId());
            token = issueToken(screen.getId());
            otherToken = issueToken(screen.getId());
        });
    }

    SignageAccessTokenEntity issueToken(Long screenId) {
        var saved = tokens.saveAndFlush(SignageAccessTokenEntity.builder().screenId(screenId)
                .name("専用capability").token(UUID.randomUUID().toString()).createdBy(910001L).build());
        tokenIds.add(saved.getId());
        return saved;
    }

    @AfterEach
    void cleanup() {
        List<RuntimeException> cleanupFailures = new ArrayList<>();
        for (StompSession session : sessions) {
            try { if (session.isConnected()) session.disconnect(); }
            catch (RuntimeException failure) { cleanupFailures.add(failure); }
        }
        for (WebSocketStompClient client : clients) {
            try { client.stop(); }
            catch (RuntimeException failure) { cleanupFailures.add(failure); }
        }
        try { new TransactionTemplate(transactions).executeWithoutResult(status -> {
            if (!screenIds.isEmpty()) em.createNativeQuery("DELETE FROM signage_emergency_messages WHERE screen_id IN (:ids)")
                    .setParameter("ids", screenIds).executeUpdate();
            if (!tokenIds.isEmpty()) em.createNativeQuery("DELETE FROM signage_access_tokens WHERE id IN (:ids)")
                    .setParameter("ids", tokenIds).executeUpdate();
            if (!screenIds.isEmpty()) em.createNativeQuery("DELETE FROM signage_screens WHERE id IN (:ids)")
                    .setParameter("ids", screenIds).executeUpdate();
            for (String table : List.of("user_roles", "memberships")) {
                if (!ownedUsers.isEmpty()) em.createNativeQuery("DELETE FROM " + table + " WHERE user_id IN (:ids)")
                        .setParameter("ids", ownedUsers).executeUpdate();
            }
            if (!ownedUsers.isEmpty()) em.createNativeQuery("DELETE FROM users WHERE id IN (:ids)")
                    .setParameter("ids", ownedUsers).executeUpdate();
            if (teamId != null) em.createNativeQuery("DELETE FROM teams WHERE id=:id")
                    .setParameter("id", teamId).executeUpdate();
            if (organizationId != null) em.createNativeQuery("DELETE FROM organizations WHERE id=:id")
                    .setParameter("id", organizationId).executeUpdate();
        }); } catch (RuntimeException failure) { cleanupFailures.add(failure); }
        assertThat(cleanupFailures).as("全client停止と所有DB回収を試み、失敗を元の試験失敗と併記する").isEmpty();
    }

    void assertNonSignageDelivery() throws Exception {
        String destination = "/topic/contract-signage-control/" + UUID.randomUUID();
        Inbox inbox = subscribe(connect("/ws", null, null, null), destination);
        String marker = UUID.randomUUID().toString();
        messaging.convertAndSend(destination, Map.of("message", marker));
        assertThat(inbox.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", marker);
    }

    Inbox deniedConnectionOrSubscription(String capability, String fakeIp) throws Exception {
        try {
            return subscribe(connect("/ws", capability, null, fakeIp), emergency());
        } catch (ExecutionException deniedConnect) {
            // 同一endpointの正当端末へmarkerを配送する呼出側の対照と組で評価する。
            return new Inbox();
        }
    }

    void mutateToken(String property, Object value) {
        if (!List.of("isActive", "expiredAt", "allowedIps").contains(property)) throw new IllegalArgumentException();
        new TransactionTemplate(transactions).executeWithoutResult(status -> em.createQuery(
                "update SignageAccessTokenEntity set " + property + "=:value where id=:id")
                .setParameter("value", value).setParameter("id", token.getId()).executeUpdate());
    }

    String emergency() {
        return "/topic/signage/" + screen.getId() + "/emergency";
    }

    void assertDelivery(Inbox control, Inbox denied) throws Exception {
        String marker = UUID.randomUUID().toString();
        publisher.publishEmergency(screen.getId(), marker);
        assertThat(control.messages.poll(10, TimeUnit.SECONDS)).as("同一markerが正当端末へ届く")
                .containsEntry("message", marker);
        assertThat(denied.messages.poll(400, TimeUnit.MILLISECONDS)).as("無資格配送は0件").isNull();
    }

    StompSession connect(String endpoint, String capability, String jwt, String fakeIp) throws Exception {
        var client = new WebSocketStompClient(new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        client.setMessageConverter(new MappingJackson2MessageConverter());
        clients.add(client);
        var handshake = new WebSocketHttpHeaders();
        handshake.setOrigin("http://localhost:3000");
        if (fakeIp != null) {
            handshake.set("X-Forwarded-For", fakeIp);
            handshake.set("X-Real-IP", fakeIp);
        }
        var headers = new StompHeaders();
        if (capability != null) headers.set("X-Signage-Token", capability);
        if (jwt != null) headers.set("Authorization", "Bearer " + jwt);
        var session = client.connectAsync("ws://127.0.0.1:" + port + endpoint,
                handshake, headers, new StompSessionHandlerAdapter() { }).get(30, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    Inbox subscribe(StompSession session, String destination) {
        return subscribe(session, destination, UUID.randomUUID().toString());
    }

    Inbox subscribe(StompSession session, String destination, String id) {
        Inbox inbox = new Inbox();
        StompHeaders headers = new StompHeaders();
        headers.setId(id);
        headers.setDestination(destination);
        inbox.id = id;
        inbox.subscription = session.subscribe(headers, inbox);
        await().atMost(Duration.ofSeconds(10)).until(() -> !session.isConnected() || registered(destination, id));
        return inbox;
    }

    boolean registered(String destination, String subscriptionId) {
        Message<byte[]> message = MessageBuilder.withPayload(new byte[0])
                .setHeader(SimpMessageHeaderAccessor.MESSAGE_TYPE_HEADER, SimpMessageType.MESSAGE)
                .setHeader(SimpMessageHeaderAccessor.DESTINATION_HEADER, destination).build();
        return broker.getSubscriptionRegistry().findSubscriptions(message).values().stream()
                .anyMatch(ids -> ids.contains(subscriptionId));
    }

    /** 実STOMPの受信本文だけを保持する。資格情報・接続headerは記録しない。 */
    static class Inbox implements StompFrameHandler {
        final BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        String id;
        StompSession.Subscription subscription;

        @Override
        public Type getPayloadType(StompHeaders headers) { return Map.class; }

        @Override
        @SuppressWarnings("unchecked")
        public void handleFrame(StompHeaders headers, Object payload) {
            messages.offer((Map<String, Object>) payload);
        }
    }
}
