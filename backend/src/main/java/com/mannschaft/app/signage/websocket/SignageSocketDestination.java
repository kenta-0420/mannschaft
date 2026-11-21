package com.mannschaft.app.signage.websocket;

import java.util.Optional;
import java.util.regex.Pattern;

/** 掲示の実2topicだけをcanonicalな正のLong IDとして解釈する。 */
final class SignageSocketDestination {
    private static final Pattern EXACT = Pattern.compile("^/topic/signage/([1-9][0-9]*)/(?:emergency|update)$");

    private SignageSocketDestination() { }

    static boolean inNamespace(String destination) {
        return destination != null && (destination.equals("/topic/signage") || destination.startsWith("/topic/signage/"));
    }

    static Optional<Long> screenId(String destination) {
        if (destination == null) return Optional.empty();
        var matcher = EXACT.matcher(destination);
        if (!matcher.matches()) return Optional.empty();
        try { return Optional.of(Long.parseLong(matcher.group(1))); }
        catch (NumberFormatException overflow) { return Optional.empty(); }
    }
}
