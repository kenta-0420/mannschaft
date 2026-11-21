package com.mannschaft.app.signage.websocket;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** 許可IP判定のためhandshakeの直接peerだけをserver属性へ保存する。forwarded/native headerは読まない。 */
@Component
public class SignagePeerHandshakeInterceptor implements HandshakeInterceptor {
    static final String PEER_IP_ATTRIBUTE = SignagePeerHandshakeInterceptor.class.getName() + ".peerIp";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        var remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            attributes.put(PEER_IP_ATTRIBUTE, remote.getAddress().getHostAddress());
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 資格照合はCONNECT時に行う。ここにはtokenやクライアント由来値を保持しない。
    }
}
