package com.mannschaft.app.auth.util;

import ua_parser.Client;
import ua_parser.Parser;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User-Agent 文字列をパースし、デバイス名とデバイス種別を返すユーティリティ。
 * uap-java を使用し、同一 UA 文字列の再パースを防ぐキャッシュを備える。
 */
public final class UserAgentParser {

    static final Parser UA_PARSER = new Parser();

    private static final Map<String, ParseResult> CACHE = new ConcurrentHashMap<>();

    private UserAgentParser() {
        // ユーティリティクラス
    }

    /**
     * パース結果を保持するレコード。
     *
     * @param deviceName 表示用デバイス名（例: "Chrome on Windows 11"）
     * @param deviceType デバイス種別
     */
    public record ParseResult(String deviceName, DeviceType deviceType) {}

    /**
     * User-Agent 文字列をパースし、デバイス名とデバイス種別を返す。
     *
     * @param userAgent User-Agent 文字列
     * @return パース結果
     */
    public static ParseResult parse(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return new ParseResult("Unknown Device", DeviceType.UNKNOWN);
        }

        if (CACHE.size() > 1000) {
            clearCache();
        }

        return CACHE.computeIfAbsent(userAgent, UserAgentParser::doParse);
    }

    private static ParseResult doParse(String userAgent) {
        Client client = UA_PARSER.parse(userAgent);

        String browser = client.userAgent.family;
        String osFamily = client.os.family;
        String osVersion = client.os.major;

        // browser と OS が両方 "Other" の場合は Unknown Device
        if ("Other".equals(browser) && "Other".equals(osFamily)) {
            return new ParseResult("Unknown Device", DeviceType.UNKNOWN);
        }

        // OS表示名を組み立て（バージョンがあれば付与）
        String os = osVersion != null && !osVersion.isEmpty()
                ? osFamily + " " + osVersion
                : osFamily;

        String deviceName = browser + " on " + os;
        DeviceType deviceType = classifyDeviceType(client.device.family, osFamily);

        return new ParseResult(deviceName, deviceType);
    }

    private static DeviceType classifyDeviceType(String deviceFamily, String osFamily) {
        String deviceLower = deviceFamily != null ? deviceFamily.toLowerCase() : "";
        String osLower = osFamily != null ? osFamily.toLowerCase() : "";

        // タブレット判定（iPad, tablet）
        if (deviceLower.contains("ipad") || deviceLower.contains("tablet")) {
            return DeviceType.TABLET;
        }

        // モバイル判定（iPhone, Android, mobile）
        if (deviceLower.contains("iphone") || deviceLower.contains("android") || deviceLower.contains("mobile")) {
            return DeviceType.MOBILE;
        }

        // iOS はモバイル
        if (osLower.contains("ios")) {
            return DeviceType.MOBILE;
        }

        return DeviceType.DESKTOP;
    }

    /**
     * キャッシュをクリアする。
     */
    public static void clearCache() {
        CACHE.clear();
    }
}
