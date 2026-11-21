package com.mannschaft.app.signage;

import com.mannschaft.app.signage.entity.SignageScreenEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * CMP-261114-0713: 実TCP・実MySQL・実SimpleBrokerで掲示購読と失効後配送を検証する先行試練。
 *
 * <p>WebSocket接続契約のため共通MySQL基底のWebEnvironmentだけをRANDOM_PORTへ上書きする。
 * 認可・token/画面Repository・Publisherは実物。共通基底のRedis代替は外部依存だけである。
 * 正例への同一marker配送を必ず対照にし、接続失敗や全配送停止を拒否成功と扱わない。
 * HTTP表示tokenの既存契約はSignageScopeContractITが別に固定する。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("掲示WS: 端末capabilityと既存接続の配送認可")
class SignageWebSocketAuthorizationIT extends SignageSocketIntegrationFixture {

    @Test
    void AC2_他scopeJWTだけでは緊急本文を受信しない() throws Exception {
        Inbox control = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        Inbox denied = subscribe(connect("/ws/signage", null,
                jwtTokens.issueAccessToken(920001L, List.of("MEMBER")), null), emergency());
        assertDelivery(control, denied);
    }

    @Test
    void AC1_query内の端末tokenだけでは購読できない() throws Exception {
        Inbox control = subscribe(connect("/ws/signage", token.getToken(), null, null), emergency());
        Inbox denied = subscribe(connect("/ws?signage_token=" + token.getToken(), null, null, null), emergency());
        assertDelivery(control, denied);
    }

    @Test
    void AC1_SUBSCRIBEheaderへtokenを載せても資格を取得できない() throws Exception {
        Inbox control = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        StompSession deniedSession = connect("/ws/signage", null, null, null);
        StompHeaders headers = new StompHeaders();
        headers.set("X-Signage-Token", token.getToken());
        headers.setId(UUID.randomUUID().toString());
        headers.setDestination(emergency());
        Inbox denied = new Inbox();
        deniedSession.subscribe(headers, denied);
        await().atMost(Duration.ofSeconds(10)).until(() -> !deniedSession.isConnected()
                || registered(emergency(), headers.getId()));
        assertDelivery(control, denied);
    }

    @Test
    void AC4_有効tokenでもprefix外wildcardから本文を受信しない() throws Exception {
        Inbox control = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        Inbox denied = subscribe(connect("/ws", token.getToken(), null, null), "/topic/**");
        assertDelivery(control, denied);
    }

    @Test
    void AC4_unsubscribe後に同じidをwildcardで再利用しても資格を引き継がない() throws Exception {
        Inbox control = subscribe(connect("/ws", otherToken.getToken(), null, null), emergency());
        StompSession session = connect("/ws/signage", token.getToken(), null, null);
        Inbox exact = subscribe(session, emergency());
        exact.subscription.unsubscribe();
        await().atMost(Duration.ofSeconds(10)).until(() -> !registered(emergency(), exact.id));
        Inbox wildcard = subscribe(session, "/topic/**", exact.id);
        assertDelivery(control, wildcard);
    }

    @Test
    void AC6_失効commit後は既存接続へ配送せず別tokenへ配送する() throws Exception {
        Inbox revoked = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        Inbox control = subscribe(connect("/ws/signage", otherToken.getToken(), null, null), emergency());
        String before = UUID.randomUUID().toString();
        publisher.publishEmergency(screen.getId(), before);
        assertThat(revoked.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", before);
        assertThat(control.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", before);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var entity = tokens.findById(token.getId()).orElseThrow();
            entity.deactivate();
            tokens.saveAndFlush(entity);
        });
        assertDelivery(control, revoked);
    }

    @Test
    void AC2_tokenなしは匿名と同scope相当JWTと他scope相当JWTでも拒否する() throws Exception {
        Inbox control = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        assertDelivery(control, subscribe(connect("/ws/signage", null, null, null), emergency()));
        for (Long actor : List.of(910001L, 910002L, 920001L)) {
            var jwt = jwtTokens.issueAccessToken(actor, List.of("MEMBER"));
            assertDelivery(control, subscribe(connect("/ws", null, jwt, null), emergency()));
        }
    }

    @Test
    void AC2_端末token保有者はJWT所属に依存せず両endpointで更新も受信する() throws Exception {
        for (String endpoint : List.of("/ws", "/ws/signage")) {
            for (String jwt : List.of("", jwtTokens.issueAccessToken(910001L, List.of("MEMBER")),
                    jwtTokens.issueAccessToken(920001L, List.of("ADMIN")))) {
                Inbox inbox = subscribe(connect(endpoint, token.getToken(), jwt.isEmpty() ? null : jwt, null),
                        "/topic/signage/" + screen.getId() + "/update");
                publisher.publishSlideUpdate(screen.getId());
                assertThat(inbox.messages.poll(10, TimeUnit.SECONDS)).containsEntry("type", "SLIDE_UPDATE");
            }
        }
    }

    @Test
    void AC2_TEAMtokenで別画面とORGANIZATION画面を購読できない() throws Exception {
        var other = new TransactionTemplate(transactions).execute(status -> {
            var entity = screens.saveAndFlush(SignageScreenEntity.builder()
                .scopeType("ORGANIZATION").scopeId(organizationId).name("別scope試練")
                    .createdBy(910001L).build());
            screenIds.add(entity.getId());
            return issueToken(entity.getId());
        });
        String destination = "/topic/signage/" + other.getScreenId() + "/emergency";
        Inbox control = subscribe(connect("/ws", other.getToken(), null, null), destination);
        Inbox denied = subscribe(connect("/ws/signage", token.getToken(), null, null), destination);
        String marker = UUID.randomUUID().toString();
        publisher.publishEmergency(other.getScreenId(), marker);
        assertThat(control.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", marker);
        assertThat(denied.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void AC3_不在と空と失効と期限切れのtokenは拒否する() throws Exception {
        Inbox control = subscribe(connect("/ws", otherToken.getToken(), null, null), emergency());
        for (String value : List.of("", UUID.randomUUID().toString())) {
            assertDelivery(control, deniedConnectionOrSubscription(value, null));
        }
        mutateToken("isActive", false);
        assertDelivery(control, deniedConnectionOrSubscription(token.getToken(), null));
        mutateToken("isActive", true);
        mutateToken("expiredAt", LocalDateTime.now().minusDays(1));
        assertDelivery(control, deniedConnectionOrSubscription(token.getToken(), null));
    }

    @Test
    void AC3_直接peerのIPを許可し偽装forwardedheaderを信用しない() throws Exception {
        Inbox control = subscribe(connect("/ws", otherToken.getToken(), null, null), emergency());
        mutateToken("allowedIps", "[\"127.0.0.0/8\"]");
        Inbox loopback = subscribe(connect("/ws/signage", token.getToken(), null, "203.0.113.1"), emergency());
        String marker = UUID.randomUUID().toString();
        publisher.publishEmergency(screen.getId(), marker);
        assertThat(control.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", marker);
        assertThat(loopback.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", marker);
        mutateToken("allowedIps", "[\"203.0.113.1/32\"]");
        assertDelivery(control, deniedConnectionOrSubscription(token.getToken(), "203.0.113.1"));
        assertDelivery(control, loopback);
    }

    @Test
    void AC6_接続後の期限到達と画面停止と削除は次配送と再接続を拒否する() throws Exception {
        var otherScreenToken = new TransactionTemplate(transactions).execute(status -> {
            var entity = screens.saveAndFlush(SignageScreenEntity.builder().scopeType("ORGANIZATION")
                    .scopeId(organizationId).name("停止試練の配送対照").createdBy(920001L).build());
            screenIds.add(entity.getId());
            return issueToken(entity.getId());
        });
        String otherDestination = "/topic/signage/" + otherScreenToken.getScreenId() + "/emergency";
        Inbox otherScreen = subscribe(connect("/ws", otherScreenToken.getToken(), null, null), otherDestination);
        Inbox established = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        Inbox control = subscribe(connect("/ws/signage", otherToken.getToken(), null, null), emergency());
        mutateToken("expiredAt", LocalDateTime.now().minusSeconds(1));
        assertDelivery(control, established);
        assertDelivery(control, deniedConnectionOrSubscription(token.getToken(), null));
        mutateToken("expiredAt", null);
        for (String column : List.of("isActive", "deletedAt")) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                var entity = screens.findById(screen.getId()).orElseThrow();
                if (column.equals("isActive")) entity.deactivate(); else entity.softDelete();
                screens.saveAndFlush(entity);
            });
            String marker = UUID.randomUUID().toString();
            publisher.publishEmergency(screen.getId(), marker);
            assertThat(established.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
            assertThat(control.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
            Inbox reconnect = deniedConnectionOrSubscription(token.getToken(), null);
            publisher.publishEmergency(screen.getId(), "stopped-screen-probe");
            assertThat(reconnect.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
            String otherMarker = UUID.randomUUID().toString();
            publisher.publishEmergency(otherScreenToken.getScreenId(), otherMarker);
            assertThat(otherScreen.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", otherMarker);
            // 無関係topicへの対照配送で、サーバ/接続そのものの停止と区別する。
            assertNonSignageDelivery();
            new TransactionTemplate(transactions).executeWithoutResult(status -> em.createNativeQuery(
                    "UPDATE signage_screens SET is_active=1, deleted_at=NULL WHERE id=:id")
                    .setParameter("id", screen.getId()).executeUpdate());
        }
    }

    @Test
    void AC5_clientSENDは匿名JWT有効device全てで偽装できない() throws Exception {
        Inbox control = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        List<StompSession> senders = List.of(connect("/ws", null, null, null),
                connect("/ws/signage", null, jwtTokens.issueAccessToken(910001L, List.of("MEMBER")), null),
                connect("/ws", token.getToken(), null, null));
        for (StompSession sender : senders) {
            sender.send(emergency(), Map.of("message", "client-forged"));
            assertThat(control.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
            String marker = UUID.randomUUID().toString();
            publisher.publishEmergency(screen.getId(), marker);
            assertThat(control.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", marker);
        }
    }

    @Test
    void AC5_正規管理RESTは画面ADMINだけが実配信できる() throws Exception {
        Inbox device = subscribe(connect("/ws/signage", token.getToken(), null, null), emergency());
        String marker = UUID.randomUUID().toString();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokens.issueAccessToken(910002L, List.of("MEMBER")));
        var response = rest.postForEntity("/api/v1/signage/screens/" + screen.getId() + "/emergency",
                new HttpEntity<>(Map.of("message", marker), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(device.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", marker);
    }

    @Test
    void AC7_不正宛先とoverflowとsuffixは購読拒否し他topicを維持する() throws Exception {
        Inbox control = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        for (String destination : List.of("/topic/signage/no-id/emergency",
                "/topic/signage/999999999999999999999999999/emergency", emergency() + "/extra",
                "/topic/signage/*/emergency", "/topic/signage/{id}/update")) {
            var session = connect("/ws/signage", token.getToken(), null, null);
            subscribe(session, destination);
            await().atMost(Duration.ofSeconds(10)).until(() -> !session.isConnected());
            String marker = UUID.randomUUID().toString();
            publisher.publishEmergency(screen.getId(), marker);
            assertThat(control.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", marker);
        }
        assertNonSignageDelivery();
    }

}
