package com.mannschaft.app.signage.websocket;

import com.mannschaft.app.common.websocket.StompErrorCode;
import com.mannschaft.app.common.websocket.StompRejectedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/** CONNECTの端末capabilityを確定し、掲示SUBSCRIBE/SEND境界を強制する。共通JWTには触れない。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SignageSubscriptionInterceptor implements ChannelInterceptor {
    static final String TOKEN_HEADER = "X-Signage-Token";
    private final SignageSocketSessions sessions;
    private final SignageSocketAccessReader reader;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;
        StompCommand command = accessor.getCommand();
        String sessionId = accessor.getSessionId();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            return connect(message, accessor);
        }
        if (command == StompCommand.DISCONNECT) sessions.close(sessionId, accessor.getSessionAttributes());
        if (command == StompCommand.UNSUBSCRIBE) sessions.unsubscribe(sessionId, accessor.getSubscriptionId());
        String destination = accessor.getDestination();
        if (command == StompCommand.SEND && SignageSocketDestination.inNamespace(destination)) {
            throw reject(StompErrorCode.FORBIDDEN);
        }
        if (command != StompCommand.SUBSCRIBE) return message;
        // 同IDの差替えで、過去のexact購読資格をwildcard等へ引き継がせない。
        sessions.unsubscribe(sessionId, accessor.getSubscriptionId());
        if (!SignageSocketDestination.inNamespace(destination)) return message;
        Long screenId = SignageSocketDestination.screenId(destination)
                .orElseThrow(() -> reject(StompErrorCode.INVALID_DESTINATION));
        var access = sessions.session(sessionId).orElseThrow(() -> reject(StompErrorCode.UNAUTHENTICATED));
        if (!screenId.equals(access.screenId()) || accessor.getSubscriptionId() == null
                || accessor.getSubscriptionId().isBlank()) throw reject(StompErrorCode.FORBIDDEN);
        try {
            if (!reader.permits(access.tokenId(), screenId, access.peerIp())) throw reject(StompErrorCode.FORBIDDEN);
        } catch (StompRejectedException denied) {
            throw denied;
        } catch (RuntimeException failure) {
            log.warn("掲示WSの購読照合を拒否: {}", failure.getClass().getSimpleName());
            throw reject(StompErrorCode.FORBIDDEN);
        }
        sessions.bind(sessionId, accessor.getSubscriptionId(), destination);
        return message;
    }

    private Message<?> connect(Message<?> message, StompHeaderAccessor original) {
        var values = original.getNativeHeader(TOKEN_HEADER);
        var accessor = original.isMutable() ? original : StompHeaderAccessor.wrap(message);
        accessor.removeNativeHeader(TOKEN_HEADER);
        Message<?> sanitized = original.isMutable() ? message
                : MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
        sessions.clear(accessor.getSessionId());
        if (values == null) return sanitized;
        if (values.size() != 1 || values.get(0) == null || values.get(0).isBlank()) {
            throw reject(StompErrorCode.UNAUTHENTICATED);
        }
        var attributes = accessor.getSessionAttributes();
        Object peer = attributes == null ? null : attributes.get(SignagePeerHandshakeInterceptor.PEER_IP_ATTRIBUTE);
        if (!(peer instanceof String peerIp) || accessor.getSessionId() == null) throw reject(StompErrorCode.UNAUTHENTICATED);
        try {
            var projection = reader.authenticate(values.get(0), peerIp)
                    .orElseThrow(() -> reject(StompErrorCode.UNAUTHENTICATED));
            if (!sessions.establish(accessor.getSessionId(), attributes, projection, peerIp)) {
                throw reject(StompErrorCode.UNAUTHENTICATED);
            }
        } catch (StompRejectedException denied) {
            throw denied;
        } catch (RuntimeException failure) {
            // DB例外のmessage/causeに入力値が含まれても、ログやSTOMP ERRORへ転記しない。
            log.warn("掲示WSのCONNECT照合を拒否: {}", failure.getClass().getSimpleName());
            throw reject(StompErrorCode.UNAUTHENTICATED);
        }
        return sanitized;
    }

    private static StompRejectedException reject(StompErrorCode code) {
        return new StompRejectedException(code, "掲示WebSocketの資格または宛先が許可されていません");
    }
}
