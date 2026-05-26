package com.mannschaft.app.proxyvote.listener;

import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.event.EventDelegationAcceptedEvent;
import com.mannschaft.app.event.service.EventDelegationService;
import com.mannschaft.app.proxyvote.service.ProxyDelegationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link EventDelegationAcceptedListener} の単体テスト（F03.10 §5.5）。
 *
 * <p>AFTER_COMMIT のトランザクション境界自体は Spring 統合テスト（DB 必須）の領域だが、
 * 受信時のルーティング（連携作成 → 逆設定 / null セッションでのスキップ / 例外の握り潰し）は
 * Mockito で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventDelegationAcceptedListener 単体テスト")
class EventDelegationAcceptedListenerTest {

    @Mock private ProxyDelegationService proxyDelegationService;
    @Mock private EventDelegationService eventDelegationService;

    @InjectMocks
    private EventDelegationAcceptedListener listener;

    private static final UUID DELEGATION_ID = UUID.randomUUID();

    private EventDelegationAcceptedEvent event(Long proxyVoteSessionId) {
        return new EventDelegationAcceptedEvent(
                DELEGATION_ID, 10L, 100L, 200L, EventScopeType.TEAM, 1L, proxyVoteSessionId);
    }

    @Test
    @DisplayName("proxyVoteSessionId なし: 連携処理を行わない")
    void セッションなしスキップ() {
        listener.handleEventDelegationAccepted(event(null));

        verify(proxyDelegationService, never()).createFromEventDelegation(any(), any(), any(), any(), any());
        verify(eventDelegationService, never()).linkProxyDelegation(any(), any());
    }

    @Test
    @DisplayName("セッションあり: proxy_delegation を作成し event_delegations に逆設定する")
    void 連携作成成功() {
        given(proxyDelegationService.createFromEventDelegation(eq(99L), eq(100L), eq(200L), eq("TEAM"), eq(1L)))
                .willReturn(555L);

        listener.handleEventDelegationAccepted(event(99L));

        verify(proxyDelegationService).createFromEventDelegation(99L, 100L, 200L, "TEAM", 1L);
        verify(eventDelegationService).linkProxyDelegation(DELEGATION_ID, 555L);
    }

    @Test
    @DisplayName("連携スキップ（null 返却）でも逆設定を null で呼び no-op に委ねる")
    void 連携スキップ時null逆設定() {
        given(proxyDelegationService.createFromEventDelegation(any(), any(), any(), any(), any()))
                .willReturn(null);

        listener.handleEventDelegationAccepted(event(99L));

        verify(eventDelegationService).linkProxyDelegation(DELEGATION_ID, null);
    }

    @Test
    @DisplayName("連携処理の例外は握り潰す（AFTER_COMMIT のため呼び出し元へ伝播させない）")
    void 例外握り潰し() {
        given(proxyDelegationService.createFromEventDelegation(any(), any(), any(), any(), any()))
                .willThrow(new RuntimeException("boom"));

        // 例外がスローされないこと
        listener.handleEventDelegationAccepted(event(99L));

        verify(eventDelegationService, never()).linkProxyDelegation(any(), any());
    }
}
