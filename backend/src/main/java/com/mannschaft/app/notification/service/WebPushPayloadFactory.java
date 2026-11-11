package com.mannschaft.app.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.dto.WebPushPayload;
import com.mannschaft.app.notification.entity.NotificationEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 通知から Web Push ペイロード JSON（{@link WebPushPayload}）を組み立てる。
 *
 * <p>設計書 F04.3「Push ペイロード」と「遷移先 URL の方針」に従う。</p>
 * <ul>
 *   <li>形は {@code {title, body, data:{notification_id, action_url, priority}}} に固定し、
 *       userId 等の内部情報は載せない（WebSocket 用の {@code NotificationResponse} とは別契約）。</li>
 *   <li>title は 100、body は 200 コードポイントで切り詰める（超えたら末尾を「…」に）。</li>
 *   <li>action_url は「/ 始まりの相対パス」か「FE の公開オリジン（{@code app.base-url}）と同じ絶対 URL」だけを通し、
 *       それ以外と 512 バイト超は null にする。null のとき SW は {@code /} へ遷移する。</li>
 *   <li>JSON 全体は UTF-8 で 2KB 以下に収める（Push サービスの上限 4KB と暗号化のオーバーヘッドに余裕を持たせる）。</li>
 * </ul>
 */
@Slf4j
@Component
public class WebPushPayloadFactory {

    /** title が無いときと、JSON 生成に失敗したときの既定の題（ブランド名）。 */
    static final String DEFAULT_TITLE = "Mannschaft";
    static final int TITLE_MAX_CODE_POINTS = 100;
    static final int BODY_MAX_CODE_POINTS = 200;
    static final int ACTION_URL_MAX_BYTES = 512;
    static final int PAYLOAD_MAX_BYTES = 2048;
    private static final String ELLIPSIS = "…";

    private final ObjectMapper objectMapper;
    /** FE の公開オリジン（scheme://host:port に正規化）。解釈できない設定なら null（相対パスだけを通す）。 */
    private final String frontendOrigin;

    public WebPushPayloadFactory(ObjectMapper objectMapper, @Value("${app.base-url}") String appBaseUrl) {
        this.objectMapper = objectMapper;
        this.frontendOrigin = originOf(appBaseUrl);
        if (this.frontendOrigin == null) {
            log.warn("app.base-url をオリジンとして解釈できないため、Web Push の遷移先は相対パスだけを通す: {}", appBaseUrl);
        }
    }

    /**
     * 通知から Web Push ペイロード JSON を生成する。失敗しても例外は投げず、同じ形の代替 JSON を返す。
     *
     * @param notification 通知
     * @return UTF-8 で 2KB 以下の JSON 文字列
     */
    public String build(NotificationEntity notification) {
        String priority = notification.getPriority() != null
                ? notification.getPriority().name()
                : NotificationPriority.NORMAL.name();
        String title = notification.getTitle() == null || notification.getTitle().isEmpty()
                ? DEFAULT_TITLE
                : truncate(notification.getTitle(), TITLE_MAX_CODE_POINTS);
        String body = notification.getBody() == null ? "" : truncate(notification.getBody(), BODY_MAX_CODE_POINTS);
        String actionUrl = sanitizeActionUrl(notification.getActionUrl());

        try {
            String json = serialize(title, body, notification.getId(), actionUrl, priority);
            if (actionUrl != null && utf8Length(json) > PAYLOAD_MAX_BYTES) {
                // JSON エスケープで膨らんだ場合は遷移先を諦めて上限に収める（title/body だけなら必ず収まる）
                log.warn("WebPushペイロードが上限を超えたため action_url を外す: notificationId={}, bytes={}",
                        notification.getId(), utf8Length(json));
                json = serialize(title, body, notification.getId(), null, priority);
            }
            return json;
        } catch (JsonProcessingException e) {
            log.warn("WebPushペイロードJSON生成失敗: notificationId={}, error={}",
                    notification.getId(), e.getMessage());
            return fallbackJson(notification.getId(), priority);
        }
    }

    private String serialize(String title, String body, Long id, String actionUrl, String priority)
            throws JsonProcessingException {
        return objectMapper.writeValueAsString(
                new WebPushPayload(title, body, new WebPushPayload.Data(id, actionUrl, priority)));
    }

    /** 代替 JSON。値は既定文言・数値・enum 名だけなので手で組んでもエスケープは要らない。 */
    private static String fallbackJson(Long id, String priority) {
        return "{\"title\":\"" + DEFAULT_TITLE + "\",\"body\":\"\",\"data\":{\"notification_id\":"
                + (id == null ? "null" : id.toString())
                + ",\"action_url\":null,\"priority\":\"" + priority + "\"}}";
    }

    /** コードポイント単位で切り詰める（サロゲートペアを途中で切らない）。 */
    static String truncate(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxCodePoints - 1);
        return text.substring(0, end) + ELLIPSIS;
    }

    /**
     * 遷移先を判定する。「/ 始まりの相対パス」か「FE の公開オリジンと同じ絶対 URL」だけを通す。
     * 制御文字・空白・バックスラッシュを含むもの（ブラウザの URL 解析で "//" に化けうる）、
     * "//" 始まり（プロトコル相対）、別オリジン、javascript: 等は null。512 バイト超も null。
     */
    String sanitizeActionUrl(String actionUrl) {
        if (actionUrl == null || actionUrl.isEmpty()) {
            return null;
        }
        if (utf8Length(actionUrl) > ACTION_URL_MAX_BYTES) {
            return null;
        }
        for (int i = 0; i < actionUrl.length(); i++) {
            char c = actionUrl.charAt(i);
            if (c <= 0x20 || c == 0x7F || c == '\\') {
                return null;
            }
        }
        if (actionUrl.startsWith("//")) {
            return null;
        }
        if (actionUrl.startsWith("/")) {
            return actionUrl;
        }
        if (frontendOrigin == null) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(actionUrl);
        } catch (java.net.URISyntaxException e) {
            return null;
        }
        if (uri.getRawUserInfo() != null) {
            return null;
        }
        return frontendOrigin.equals(originOf(uri)) ? actionUrl : null;
    }

    private static String originOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return originOf(new URI(url.trim()));
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    private static String originOf(URI uri) {
        if (!uri.isAbsolute() || uri.getHost() == null) {
            return null;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if ("https".equals(scheme)) {
            port = port == -1 ? 443 : port;
        } else if ("http".equals(scheme)) {
            port = port == -1 ? 80 : port;
        } else {
            return null;
        }
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
