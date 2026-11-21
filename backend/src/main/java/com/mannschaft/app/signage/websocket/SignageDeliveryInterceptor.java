package com.mannschaft.app.signage.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/** 実recipientのsession/subscription/exact宛先と現在のDB資格を照合し、当該frameだけを拒否する。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SignageDeliveryInterceptor implements ChannelInterceptor {
    private final SignageSocketSessions sessions;
    private final SignageSocketAccessReader reader;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var accessor = SimpMessageHeaderAccessor.wrap(message);
        String destination = accessor.getDestination();
        if (accessor.getMessageType() != SimpMessageType.MESSAGE
                || !SignageSocketDestination.inNamespace(destination)) return message;
        // publisher/sessionAttributes/nativeHeadersから資格を復元しない。brokerが付けたrecipient IDでだけ引く。
        var access = sessions.binding(accessor.getSessionId(), accessor.getSubscriptionId(), destination);
        if (access.isEmpty()) return null;
        var bound = access.orElseThrow();
        if (!SignageSocketDestination.screenId(destination).filter(bound.screenId()::equals).isPresent()) return null;
        try {
            return reader.permits(bound.tokenId(), bound.screenId(), bound.peerIp()) ? message : null;
        } catch (RuntimeException failure) {
            log.warn("掲示WSの当該配送を拒否: {}", failure.getClass().getSimpleName());
            return null;
        }
    }
}
