package com.mannschaft.app.websocket.relay;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RelayEnvelope} の確定 JSON スキーマ（設計書 §4.5.1）を固定する番人テスト。
 *
 * <p><b>意図的に green</b>（RelayEnvelope はスキーマ DTO であり skeleton 段階で完成している）。
 * これは AC の red 駆動ではなく、出陣・将来改修で §4.5.1 のキー名（camelCase）・フィールド集合が
 * 崩れないよう固定するためのガードである。キー追加・改名が起きたら本テストが検知する。</p>
 */
@DisplayName("RelayEnvelope 確定 JSON スキーマ番人（§4.5.1・意図的 green）")
class RelayEnvelopeSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    @DisplayName("camelCase の 6 キーで round-trip し、未知キーは無視される（前方互換）")
    void serializesWithExactCamelCaseKeys() throws Exception {
        RelayEnvelope envelope = new RelayEnvelope(
                "3f9b2c9e-6a1d-4b7f-9c1e-2d8a5b4c7e10",
                WebSocketRelayConstants.MESSAGE_TYPE_BROADCAST,
                "/topic/channels/42",
                null,
                "application/json",
                "eyJpZCI6MX0=");

        String json = objectMapper.writeValueAsString(envelope);
        ObjectNode node = (ObjectNode) objectMapper.readTree(json);

        assertThat(node.fieldNames()).toIterable()
                .as("§4.5.1 の 6 キー（camelCase）のみであること")
                .containsExactlyInAnyOrder(
                        "originNodeId", "messageType", "destination", "userId", "contentType", "body");

        // 未知キーを足しても前方互換（無視して復元できる）
        node.put("futureField", "x");
        RelayEnvelope restored = objectMapper.readValue(node.toString(), RelayEnvelope.class);
        assertThat(restored.getOriginNodeId()).isEqualTo("3f9b2c9e-6a1d-4b7f-9c1e-2d8a5b4c7e10");
        assertThat(restored.getMessageType()).isEqualTo("BROADCAST");
        assertThat(restored.getDestination()).isEqualTo("/topic/channels/42");
        assertThat(restored.getContentType()).isEqualTo("application/json");
        assertThat(restored.getBody()).isEqualTo("eyJpZCI6MX0=");
    }
}
