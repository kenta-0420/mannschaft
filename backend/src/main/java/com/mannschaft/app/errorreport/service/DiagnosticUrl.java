package com.mannschaft.app.errorreport.service;

import java.net.URI;
import java.util.Locale;

/** FE診断だけのURL境界。生値fallbackせず、保存するpathはdecodeしない。 */
final class DiagnosticUrl {
    private static final String INVALID_URL = "/invalid-url";

    private DiagnosticUrl() { }

    static String normalize(String input) {
        if (input == null || input.isBlank() || input.codePoints().anyMatch(character ->
                Character.isISOControl(character) || Character.isWhitespace(character)
                        || Character.isSpaceChar(character) || character == '\\' || character == 0xfeff)) {
            return INVALID_URL;
        }
        try {
            URI uri = URI.create(input);
            if (input.startsWith("/") && !input.startsWith("//")) {
                String path = uri.getRawPath();
                return path == null || path.isEmpty() ? INVALID_URL : path;
            }
            String scheme = uri.getScheme();
            String authority = uri.getRawAuthority();
            String host = uri.getHost();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || authority == null || host == null || uri.getPort() > 65535
                    || authority.chars().anyMatch(character -> character > 126)) {
                return INVALID_URL;
            }
            // WHATWGが補正する省略IPv4やoctal表記を、別hostとして救済しない。
            if (host.matches("[0-9.]+")) {
                String[] octets = host.split("\\.", -1);
                if (octets.length != 4) {
                    return INVALID_URL;
                }
                for (String octet : octets) {
                    if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) {
                        return INVALID_URL;
                    }
                }
            }
            String hostAndPort = authority.substring(authority.lastIndexOf('@') + 1);
            String path = uri.getRawPath();
            return scheme.toLowerCase(Locale.ROOT) + "://" + hostAndPort
                    + (path == null || path.isEmpty() ? "/" : path);
        } catch (IllegalArgumentException exception) {
            return INVALID_URL;
        }
    }
}
