package com.mannschaft.app.signage.websocket;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** server側sessionとexact購読の束縛。保持する資格はIDと直接peerだけでraw tokenを持たない。 */
@Component
public class SignageSocketSessions {
    private static final String CLOSED_ATTRIBUTE = SignageSocketSessions.class.getName() + ".closed";
    private final ConcurrentMap<String, State> states = new ConcurrentHashMap<>();

    /** 外部へ渡せる不変の資格。JWT Principalとは独立する。 */
    record Access(Long tokenId, Long screenId, String peerIp) { }

    private record State(Access access, ConcurrentMap<String, String> destinations) { }

    /** 認可完了後の登録と切断を同じ接続寿命のmonitorで直列化する。DB照合は呼出側でlock外に置く。 */
    boolean establish(String sessionId, Map<String, Object> attributes, SignageSocketAccess projection, String peerIp) {
        if (sessionId == null || attributes == null) return false;
        synchronized (attributes) {
            if (Boolean.TRUE.equals(attributes.get(CLOSED_ATTRIBUTE))) return false;
            states.put(sessionId,
                    new State(new Access(projection.tokenId(), projection.screenId(), peerIp), new ConcurrentHashMap<>()));
            return true;
        }
    }

    Optional<Access> session(String sessionId) {
        State state = sessionId == null ? null : states.get(sessionId);
        return state == null ? Optional.empty() : Optional.of(state.access());
    }

    void bind(String sessionId, String subscriptionId, String destination) {
        if (sessionId == null || subscriptionId == null || destination == null) return;
        // disconnectと同時でも切り離されたStateをglobalな別mapへ残さない。
        states.computeIfPresent(sessionId, (id, state) -> {
            state.destinations().put(subscriptionId, destination);
            return state;
        });
    }

    Optional<Access> binding(String sessionId, String subscriptionId, String destination) {
        if (sessionId == null || subscriptionId == null || destination == null) return Optional.empty();
        State state = states.get(sessionId);
        return state != null && destination.equals(state.destinations().get(subscriptionId))
                ? Optional.of(state.access()) : Optional.empty();
    }

    void unsubscribe(String sessionId, String subscriptionId) {
        State state = sessionId == null ? null : states.get(sessionId);
        if (state != null && subscriptionId != null) state.destinations().remove(subscriptionId);
    }

    void clear(String sessionId) {
        if (sessionId != null) states.remove(sessionId);
    }

    /** 資格の消去では寿命を戻さず、切断後に遅れて返った認可結果による再登録を拒否する。 */
    void close(String sessionId, Map<String, Object> attributes) {
        if (attributes == null) {
            clear(sessionId);
            return;
        }
        synchronized (attributes) {
            attributes.put(CLOSED_ATTRIBUTE, true);
            clear(sessionId);
        }
    }

    /** abrupt closeも回収する。実CONNECTと同じsession属性mapで重複通知も冪等に処理する。 */
    @EventListener
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "機能停止後も切断済み接続の資格と購読bindingを破棄し、遅いCONNECTによる接続寿命の復活を防ぐ必要がある")
    public void disconnected(SessionDisconnectEvent event) {
        close(event.getSessionId(), SimpMessageHeaderAccessor.getSessionAttributes(event.getMessage().getHeaders()));
    }
}
