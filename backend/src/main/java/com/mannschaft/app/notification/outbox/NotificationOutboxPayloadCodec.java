package com.mannschaft.app.notification.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

/**
 * {@link NotificationOutboxPayload} と {@code payload_json} の版つき変換（docs/architecture/notification_outbox.md §6）。
 *
 * <p>版の展開は reader を先、writer を後の2段階で行う。relay は claim した行の版を {@link #supports(int)} で確かめ、
 * 読めない版は失敗に数えず PENDING のまま先送りする（ローリングデプロイ中に古いノードが新しい版を掴んだ場合）。</p>
 *
 * <p>JSON 化には Web 層の {@code ObjectMapper} を使わず、本クラス専用の既定設定の mapper を使う。
 * outbox の行は永続化された契約であり、API の直列化設定の変更（命名規則・日付の書式など）に引きずられて
 * 書いた版と読む版の形が食い違わないようにするため。</p>
 */
@Component
public class NotificationOutboxPayloadCodec {

    /** writer が書く現在の版。 */
    public static final int CURRENT_VERSION = 1;

    /** 版1の形: {@link NotificationOutboxPayload} の record をそのまま JSON にしたもの。 */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** この版を読めるか。 */
    public boolean supports(int payloadVersion) {
        return payloadVersion == CURRENT_VERSION;
    }

    /** 現在の版（{@link #CURRENT_VERSION}）で JSON にする。 */
    public String encode(NotificationOutboxPayload payload) {
        if (payload == null || payload.kind() == null) {
            throw new IllegalArgumentException("outbox の依頼が空、または種類が無い: " + payload);
        }
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("outbox の依頼を JSON にできない: kind=" + payload.kind(), e);
        }
    }

    /** 指定の版の JSON を読む。読めない版なら {@link IllegalArgumentException}。 */
    public NotificationOutboxPayload decode(int payloadVersion, String payloadJson) {
        if (!supports(payloadVersion)) {
            throw new IllegalArgumentException("読めない payload_version: " + payloadVersion);
        }
        try {
            NotificationOutboxPayload payload = MAPPER.readValue(payloadJson, NotificationOutboxPayload.class);
            if (payload == null || payload.kind() == null) {
                throw new IllegalArgumentException("outbox の依頼に種類が無い（版" + payloadVersion + "）");
            }
            return payload;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("outbox の依頼を読めない（版" + payloadVersion + "）", e);
        }
    }
}
