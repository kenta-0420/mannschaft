package com.mannschaft.app.reservation.ws;

import com.mannschaft.app.common.AccessControlService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F03.4+ {@link EmergencyClosureSubscriptionInterceptor} の購読認可 UT（純 UT）。
 *
 * <p>検証観点:</p>
 * <ul>
 *   <li>確認状況トピックの SUBSCRIBE で当該チーム ADMIN（{@code isAdmin=true}）→ 許可。</li>
 *   <li>非 ADMIN（{@code isAdmin=false}）→ 拒否。他チーム ADMIN は teamId 違いで isAdmin=false に委譲され拒否（IDOR 遮断）。</li>
 *   <li>未認証（session userId=null）→ isAdmin を呼ばずに拒否（チーム管理者専用）。</li>
 *   <li>確認状況トピック以外（chat/lobby/match live、確認状況以外の emergency-closures 配下）→ 素通し（isAdmin を呼ばない）。</li>
 *   <li>SUBSCRIBE 以外のコマンド（CONNECT/SEND）→ 素通し。</li>
 *   <li>teamId/closureId が数値でない宛先 → 本機能対象外として素通し。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmergencyClosureSubscriptionInterceptor 購読認可 UT (F03.4+)")
class EmergencyClosureSubscriptionInterceptorTest {

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 99L;
    private static final long CLOSURE_ID = 12345L;
    private static final long ADMIN_USER_ID = 42L;

    private static final String CONFIRMATIONS_DESTINATION =
            "/topic/teams/" + TEAM_ID + "/emergency-closures/" + CLOSURE_ID + "/confirmations";

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private EmergencyClosureSubscriptionInterceptor interceptor;

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
    // 購読認可（isAdmin 委譲）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("当該チーム ADMIN → 購読許可（メッセージはそのまま通過）")
    void 当該チームADMINで許可() {
        when(accessControlService.isAdmin(eq(ADMIN_USER_ID), eq(TEAM_ID), eq("TEAM"))).thenReturn(true);
        Message<byte[]> msg = subscribeMessage(CONFIRMATIONS_DESTINATION, ADMIN_USER_ID);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verify(accessControlService).isAdmin(ADMIN_USER_ID, TEAM_ID, "TEAM");
    }

    @Test
    @DisplayName("非 ADMIN（isAdmin=false）→ 購読拒否（MessagingException）")
    void 非ADMINで拒否() {
        when(accessControlService.isAdmin(eq(ADMIN_USER_ID), eq(TEAM_ID), eq("TEAM"))).thenReturn(false);
        Message<byte[]> msg = subscribeMessage(CONFIRMATIONS_DESTINATION, ADMIN_USER_ID);

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                .isInstanceOf(MessagingException.class);
        verify(accessControlService).isAdmin(ADMIN_USER_ID, TEAM_ID, "TEAM");
    }

    @Test
    @DisplayName("他チーム ADMIN が別チームの closure を購読 → isAdmin(他teamId)=false に委譲され拒否（IDOR 遮断）")
    void 他チームADMINは別チームで拒否() {
        String otherDest = "/topic/teams/" + OTHER_TEAM_ID + "/emergency-closures/" + CLOSURE_ID + "/confirmations";
        // 操作者は TEAM_ID の ADMIN だが、購読先は OTHER_TEAM_ID。OTHER_TEAM_ID に対しては isAdmin=false。
        when(accessControlService.isAdmin(eq(ADMIN_USER_ID), eq(OTHER_TEAM_ID), eq("TEAM"))).thenReturn(false);
        Message<byte[]> msg = subscribeMessage(otherDest, ADMIN_USER_ID);

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                .isInstanceOf(MessagingException.class);
        verify(accessControlService).isAdmin(ADMIN_USER_ID, OTHER_TEAM_ID, "TEAM");
    }

    @Test
    @DisplayName("未認証（userId=null）→ isAdmin を呼ばずに購読拒否（チーム管理者専用）")
    void 未認証で拒否() {
        Message<byte[]> msg = subscribeMessage(CONFIRMATIONS_DESTINATION, null);

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                .isInstanceOf(MessagingException.class);
        verifyNoInteractions(accessControlService);
    }

    // ─────────────────────────────────────────────
    // 宛先判定（確認状況トピック以外は素通し＝既存topic非破壊）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("chat トピックの SUBSCRIBE → 素通し（isAdmin を呼ばない）")
    void chat宛先は素通し() {
        Message<byte[]> msg = subscribeMessage("/topic/chat/channels/123", ADMIN_USER_ID);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verifyNoInteractions(accessControlService);
    }

    @Test
    @DisplayName("match live トピックの SUBSCRIBE → 素通し（他インターセプタの管轄・isAdmin を呼ばない）")
    void matchLive宛先は素通し() {
        Message<byte[]> msg = subscribeMessage(
                "/topic/matches/01890000-0000-7000-8000-000000000001/live", ADMIN_USER_ID);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verifyNoInteractions(accessControlService);
    }

    @Test
    @DisplayName("emergency-closures 配下でも confirmations 以外（confirm 等）の宛先は素通し")
    void confirmations以外は素通し() {
        Message<byte[]> msg = subscribeMessage(
                "/topic/teams/" + TEAM_ID + "/emergency-closures/" + CLOSURE_ID + "/confirm", ADMIN_USER_ID);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verifyNoInteractions(accessControlService);
    }

    @Test
    @DisplayName("teamId が数値でない confirmations 宛先 → 本機能対象外として素通し（isAdmin を呼ばない）")
    void 非数値teamIdは素通し() {
        Message<byte[]> msg = subscribeMessage(
                "/topic/teams/abc/emergency-closures/" + CLOSURE_ID + "/confirmations", ADMIN_USER_ID);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verifyNoInteractions(accessControlService);
    }

    @Test
    @DisplayName("closureId が数値でない confirmations 宛先 → 本機能対象外として素通し（isAdmin を呼ばない）")
    void 非数値closureIdは素通し() {
        Message<byte[]> msg = subscribeMessage(
                "/topic/teams/" + TEAM_ID + "/emergency-closures/xyz/confirmations", ADMIN_USER_ID);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verifyNoInteractions(accessControlService);
    }

    // ─────────────────────────────────────────────
    // コマンド判定（SUBSCRIBE 以外は素通し）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("CONNECT コマンド → 素通し（isAdmin を呼ばない）")
    void CONNECTは素通し() {
        Message<byte[]> msg = message(StompCommand.CONNECT, null);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verifyNoInteractions(accessControlService);
    }

    @Test
    @DisplayName("SEND コマンド（確認状況宛先でも）→ 素通し（購読のみ認可対象）")
    void SENDは素通し() {
        Message<byte[]> msg = message(StompCommand.SEND, CONFIRMATIONS_DESTINATION);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verify(accessControlService, never()).isAdmin(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("destination が null の SUBSCRIBE → 素通し（NPE 回避）")
    void destinationなしSUBSCRIBEは素通し() {
        Message<byte[]> msg = subscribeMessage(null, ADMIN_USER_ID);

        assertThatCode(() -> interceptor.preSend(msg, channel)).doesNotThrowAnyException();
        verifyNoInteractions(accessControlService);
    }
}
