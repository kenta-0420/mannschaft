package com.mannschaft.app.match.live;

import com.mannschaft.app.match.service.MatchAccessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F08.10 / 07 §J.3 {@link MatchLiveSubscriptionInterceptor} の購読認可 UT（純 UT・test-first）。
 *
 * <p>検証観点（07 §J.6 テスト方針）:</p>
 * <ul>
 *   <li>match live 宛先の SUBSCRIBE で {@code canView=true} → 許可 / {@code canView=false} → 拒否（他テナント含む）。</li>
 *   <li>未認証（session userId=null）＋PUBLIC 試合 → 許可 / 未認証＋非PUBLIC → 拒否（{@code canView(null, ...)} へ委譲）。</li>
 *   <li>match live 以外の宛先（chat/lobby/corkboard）→ 素通し（{@code canView} を呼ばない＝既存topic非破壊）。</li>
 *   <li>SUBSCRIBE 以外のコマンド（CONNECT/SEND）→ 素通し。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchLiveSubscriptionInterceptor 購読認可 UT (F08.10 / 07 §J.3)")
class MatchLiveSubscriptionInterceptorTest {

    private static final UUID MATCH_ID = UUID.fromString("01890000-0000-7000-8000-000000000001");
    private static final UUID OTHER_TENANT_MATCH_ID = UUID.fromString("01890000-0000-7000-8000-000000000002");
    private static final long VIEWER_USER_ID = 42L;

    private static final String LIVE_DESTINATION = "/topic/matches/" + MATCH_ID + "/live";

    @Mock
    private MatchAccessService matchAccessService;

    @InjectMocks
    private MatchLiveSubscriptionInterceptor interceptor;

    @Mock
    private MessageChannel channel;

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    /** SUBSCRIBE フレームのメッセージを組み立てる（session 属性に userId をセット可能）。 */
    private Message<byte[]> subscribeMessage(String destination, Long sessionUserId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        Map<String, Object> sessionAttributes = new HashMap<>();
        if (sessionUserId != null) {
            sessionAttributes.put("userId", sessionUserId);
        }
        accessor.setSessionAttributes(sessionAttributes);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> message(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setSessionAttributes(new HashMap<>());
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    // ─────────────────────────────────────────────
    // 購読認可（canView 委譲）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("canView=true → 購読許可（メッセージはそのまま通過）")
    void canViewTrueで許可() {
        when(matchAccessService.canView(eq(VIEWER_USER_ID), eq(MATCH_ID))).thenReturn(true);
        Message<byte[]> msg = subscribeMessage(LIVE_DESTINATION, VIEWER_USER_ID);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verify(matchAccessService).canView(VIEWER_USER_ID, MATCH_ID);
    }

    @Test
    @DisplayName("canView=false → 購読拒否（MessagingException）")
    void canViewFalseで拒否() {
        when(matchAccessService.canView(eq(VIEWER_USER_ID), eq(MATCH_ID))).thenReturn(false);
        Message<byte[]> msg = subscribeMessage(LIVE_DESTINATION, VIEWER_USER_ID);

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    @DisplayName("他テナントの matchId → canView=false に委譲され購読拒否（IDOR/越境遮断）")
    void 他テナントで拒否() {
        String otherDest = "/topic/matches/" + OTHER_TENANT_MATCH_ID + "/live";
        // canView は親 matches をテナント取得してから判定するため、他テナントは false を返す。
        when(matchAccessService.canView(eq(VIEWER_USER_ID), eq(OTHER_TENANT_MATCH_ID))).thenReturn(false);
        Message<byte[]> msg = subscribeMessage(otherDest, VIEWER_USER_ID);

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                .isInstanceOf(MessagingException.class);
        verify(matchAccessService).canView(VIEWER_USER_ID, OTHER_TENANT_MATCH_ID);
    }

    @Test
    @DisplayName("未認証（userId=null）＋PUBLIC 試合 → canView(null,...)=true で許可")
    void 未認証PUBLICで許可() {
        when(matchAccessService.canView(isNull(), eq(MATCH_ID))).thenReturn(true);
        Message<byte[]> msg = subscribeMessage(LIVE_DESTINATION, null);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verify(matchAccessService).canView(null, MATCH_ID);
    }

    @Test
    @DisplayName("未認証（userId=null）＋非PUBLIC 試合 → canView(null,...)=false で拒否")
    void 未認証非PUBLICで拒否() {
        when(matchAccessService.canView(isNull(), eq(MATCH_ID))).thenReturn(false);
        Message<byte[]> msg = subscribeMessage(LIVE_DESTINATION, null);

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                .isInstanceOf(MessagingException.class);
        verify(matchAccessService).canView(null, MATCH_ID);
    }

    // ─────────────────────────────────────────────
    // 宛先判定（match live 以外は素通し＝既存topic非破壊）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("chat トピックの SUBSCRIBE → 素通し（canView を呼ばない）")
    void chat宛先は素通し() {
        Message<byte[]> msg = subscribeMessage("/topic/chat/channels/123", VIEWER_USER_ID);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verifyNoInteractions(matchAccessService);
    }

    @Test
    @DisplayName("lobby トピックの SUBSCRIBE → 素通し（canView を呼ばない）")
    void lobby宛先は素通し() {
        Message<byte[]> msg = subscribeMessage("/topic/lobby/presence", VIEWER_USER_ID);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verifyNoInteractions(matchAccessService);
    }

    @Test
    @DisplayName("corkboard トピックの SUBSCRIBE → 素通し（canView を呼ばない）")
    void corkboard宛先は素通し() {
        Message<byte[]> msg = subscribeMessage("/topic/corkboard/teams/5", VIEWER_USER_ID);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verifyNoInteractions(matchAccessService);
    }

    @Test
    @DisplayName("matches 配下でも /live 以外（メタ等）の宛先は素通し")
    void matchesでもliveでなければ素通し() {
        Message<byte[]> msg = subscribeMessage("/topic/matches/" + MATCH_ID + "/meta", VIEWER_USER_ID);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verifyNoInteractions(matchAccessService);
    }

    @Test
    @DisplayName("matchId が UUID でない live 宛先は本機能対象外として素通し（canView を呼ばない）")
    void 不正matchIdは素通し() {
        Message<byte[]> msg = subscribeMessage("/topic/matches/not-a-uuid/live", VIEWER_USER_ID);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verifyNoInteractions(matchAccessService);
    }

    // ─────────────────────────────────────────────
    // コマンド判定（SUBSCRIBE 以外は素通し）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("CONNECT コマンド → 素通し（canView を呼ばない）")
    void CONNECTは素通し() {
        Message<byte[]> msg = message(StompCommand.CONNECT, null);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verifyNoInteractions(matchAccessService);
    }

    @Test
    @DisplayName("SEND コマンド（match live 宛先でも）→ 素通し（購読のみ認可対象）")
    void SENDは素通し() {
        Message<byte[]> msg = message(StompCommand.SEND, LIVE_DESTINATION);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verify(matchAccessService, never()).canView(any(), any());
    }

    @Test
    @DisplayName("destination が null の SUBSCRIBE → 素通し（NPE 回避）")
    void destinationなしSUBSCRIBEは素通し() {
        Message<byte[]> msg = subscribeMessage(null, VIEWER_USER_ID);

        assertThatCode(() -> interceptor.preSend(msg, channel)).doesNotThrowAnyException();
        verifyNoInteractions(matchAccessService);
    }
}
