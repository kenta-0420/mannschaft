package com.mannschaft.app.signage.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.signage.repository.SignageAccessTokenRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 掲示WSの資格を、publisherの古いsnapshotに参加しない独立readで照合する。 */
@Service
@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
public class SignageSocketAccessReader {
    private final SignageAccessTokenRepository tokens;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public SignageSocketAccessReader(SignageAccessTokenRepository tokens, @Qualifier("wallClock") Clock clock,
                                    ObjectMapper objectMapper) {
        this.tokens = tokens;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** CONNECT専用headerのtokenを1projectionで照合する。値を状態や例外へ保管しない。 */
    public Optional<SignageSocketAccess> authenticate(String token, String peerIp) {
        if (token == null || token.isBlank() || token.length() > 36 || !literalAddress(peerIp)) {
            return Optional.empty();
        }
        return tokens.findSocketAccessByToken(token).filter(access -> usable(access, peerIp));
    }

    /** 保存済みIDの現在の資格を1projectionで照合し、呼出元の古いTXへ参加しない。 */
    public boolean permits(Long tokenId, Long screenId, String peerIp) {
        return tokens.findSocketAccessById(tokenId, screenId).filter(access -> usable(access, peerIp)).isPresent();
    }

    private boolean usable(SignageSocketAccess access, String peerIp) {
        if (!literalAddress(peerIp)
                || (access.expiredAt() != null && !clock.instant().isBefore(access.expiredAt()))) {
            return false;
        }
        if (access.allowedIps() == null) {
            return true;
        }
        try {
            String[] ranges = objectMapper.readValue(access.allowedIps(), String[].class);
            if (ranges == null || ranges.length == 0) {
                return false;
            }
            List<IpAddressMatcher> matchers = new ArrayList<>();
            for (String range : ranges) {
                // hostnameのDNS解決を認可へ混ぜない。設定の一部でも壊れていればfail closed。
                if (range == null || !range.matches("[0-9a-fA-F:.]+(?:/[0-9]{1,3})?")
                        || !literalAddress(range.split("/", -1)[0])) {
                    return false;
                }
                matchers.add(new IpAddressMatcher(range));
            }
            return matchers.stream().anyMatch(matcher -> matcher.matches(peerIp));
        } catch (java.io.IOException | IllegalArgumentException invalidConfiguration) {
            return false;
        }
    }

    private static boolean literalAddress(String ip) {
        return ip != null && (ip.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")
                || (ip.contains(":") && ip.matches("[0-9a-fA-F:.]+")));
    }
}
