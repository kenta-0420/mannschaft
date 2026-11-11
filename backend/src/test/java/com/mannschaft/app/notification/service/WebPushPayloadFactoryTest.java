package com.mannschaft.app.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.config.JacksonConfig;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link WebPushPayloadFactory} の試験（CMP-261029-0620）。
 *
 * <p>本番と同じ {@link JacksonConfig} の ObjectMapper を使い、Service Worker が読む JSON の形
 * （設計書 F04.3 の Push ペイロード節）を検査する。見本 JSON
 * {@code contracts/web-push-payload.json} は FE の SW 試験も読む（AC-15）。</p>
 */
@DisplayName("WebPushPayloadFactory（CMP-261029-0620）")
class WebPushPayloadFactoryTest {

    private static final String BASE_URL = "https://app.example.com";
    private static final int MAX_PAYLOAD_BYTES = 2048;

    private ObjectMapper objectMapper;
    private WebPushPayloadFactory factory;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper(Jackson2ObjectMapperBuilder.json());
        factory = new WebPushPayloadFactory(objectMapper, BASE_URL);
    }

    private NotificationEntity.NotificationEntityBuilder<?, ?> base() {
        return NotificationEntity.builder()
                .id(100L)
                .userId(1L)
                .notificationType("SCHEDULE_REMINDER")
                .priority(NotificationPriority.NORMAL)
                .title("出欠のリマインド")
                .body("明日の練習の出欠が未回答です")
                .sourceType("SCHEDULE")
                .sourceId(10L)
                .scopeType(NotificationScopeType.TEAM)
                .scopeId(5L)
                .actionUrl("/teams/fc-u-18/schedules/10")
                .actorId(2L);
    }

    private JsonNode build(NotificationEntity n) throws IOException {
        JsonNode json = objectMapper.readTree(factory.build(n));
        assertThat(json.path("data").isObject()).as("ペイロードに data オブジェクトがあること: %s", json).isTrue();
        return json;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String repeat(String s, int n) {
        return s.repeat(n);
    }

    @Test
    @DisplayName("AC-15: 見本 JSON（contracts/web-push-payload.json）と同じ JSON を生成する")
    void ac15_見本JSONと一致する() throws IOException {
        JsonNode expected;
        try (InputStream in = getClass().getResourceAsStream("/contracts/web-push-payload.json")) {
            assertThat(in).as("見本 JSON が classpath にあること").isNotNull();
            expected = objectMapper.readTree(in);
        }
        assertThat(build(base().build())).isEqualTo(expected);
    }

    @Nested
    @DisplayName("形（AC-1〜3）")
    class Shape {

        @Test
        @DisplayName("AC-1: 最上位は title/body/data のみ、data は notification_id/action_url/priority のみ")
        void ac1_キーは設計書どおり() throws IOException {
            JsonNode json = build(base().build());
            assertThat(fieldNames(json)).containsExactlyInAnyOrder("title", "body", "data");
            assertThat(fieldNames(json.get("data")))
                    .containsExactlyInAnyOrder("notification_id", "action_url", "priority");
            String raw = factory.build(base().build());
            assertThat(raw).doesNotContain("userId", "actorId", "channelsSent", "isRead", "actionUrl", "sourceType");
        }

        @Test
        @DisplayName("AC-2: action_url は actionUrl と同値、notification_id は id")
        void ac2_値の写し() throws IOException {
            JsonNode json = build(base().actionUrl("/teams/x/schedules/10").build());
            assertThat(json.get("title").asText()).isEqualTo("出欠のリマインド");
            assertThat(json.get("body").asText()).isEqualTo("明日の練習の出欠が未回答です");
            assertThat(json.get("data").get("action_url").asText()).isEqualTo("/teams/x/schedules/10");
            assertThat(json.get("data").get("notification_id").asLong()).isEqualTo(100L);
            assertThat(json.get("data").get("priority").asText()).isEqualTo("NORMAL");
        }

        @Test
        @DisplayName("AC-2: priority は優先度の名前（URGENT）")
        void ac2_priority() throws IOException {
            JsonNode json = build(base().priority(NotificationPriority.URGENT).build());
            assertThat(json.get("data").get("priority").asText()).isEqualTo("URGENT");
        }

        @Test
        @DisplayName("AC-3: actionUrl が null なら action_url は null、body が null なら \"\"")
        void ac3_null() throws IOException {
            JsonNode json = build(base().actionUrl(null).body(null).build());
            assertThat(json.get("data").has("action_url")).isTrue();
            assertThat(json.get("data").get("action_url").isNull()).isTrue();
            assertThat(json.get("body").isTextual()).isTrue();
            assertThat(json.get("body").asText()).isEmpty();
        }
    }

    @Nested
    @DisplayName("切り詰めとサイズ（AC-4・AC-5a）")
    class Truncation {

        @Test
        @DisplayName("AC-4: title 100字は不変、101字は 99字＋…")
        void ac4_title() throws IOException {
            String t100 = repeat("あ", 100);
            assertThat(build(base().title(t100).build()).get("title").asText()).isEqualTo(t100);
            String t101 = repeat("あ", 101);
            assertThat(build(base().title(t101).build()).get("title").asText())
                    .isEqualTo(repeat("あ", 99) + "…");
        }

        @Test
        @DisplayName("AC-4: body 200字は不変、201字は 199字＋…")
        void ac4_body() throws IOException {
            String b200 = repeat("い", 200);
            assertThat(build(base().body(b200).build()).get("body").asText()).isEqualTo(b200);
            String b201 = repeat("い", 201);
            assertThat(build(base().body(b201).build()).get("body").asText())
                    .isEqualTo(repeat("い", 199) + "…");
        }

        @Test
        @DisplayName("AC-4: サロゲートペアを途中で切らない（コードポイント単位）")
        void ac4_surrogate() throws IOException {
            String emoji = "😀"; // 😀
            JsonNode json = build(base().title(repeat(emoji, 101)).body(repeat(emoji, 200)).build());
            String title = json.get("title").asText();
            assertThat(title).isEqualTo(repeat(emoji, 99) + "…");
            assertThat(title.codePointCount(0, title.length())).isEqualTo(100);
            String body = json.get("body").asText();
            assertThat(body).isEqualTo(repeat(emoji, 200));
            for (int i = 0; i < title.length(); i++) {
                char c = title.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    assertThat(Character.isLowSurrogate(title.charAt(i + 1))).isTrue();
                }
            }
        }

        @Test
        @DisplayName("AC-5a: title200字・body1000字の日本語と最大長 actionUrl でも UTF-8 2KB 以下")
        void ac5a_最大入力でも2KB以下() {
            String url = "/" + repeat("a", 499); // entity 列長 500
            String payload = factory.build(base()
                    .title(repeat("漢", 200))
                    .body(repeat("字", 1000))
                    .actionUrl(url)
                    .build());
            assertThat(payload.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(MAX_PAYLOAD_BYTES);
            assertThat(payload).contains(url);
        }

        @Test
        @DisplayName("AC-5a: 4バイト文字（絵文字）の最大入力でも UTF-8 2KB 以下")
        void ac5a_絵文字でも2KB以下() {
            String emoji = "😀";
            String payload = factory.build(base()
                    .title(repeat(emoji, 200))
                    .body(repeat(emoji, 1000))
                    .actionUrl("/" + repeat("b", 511))
                    .build());
            assertThat(payload.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(MAX_PAYLOAD_BYTES);
        }

        @Test
        @DisplayName("AC-5a: JSON エスケープで膨らむ入力（制御文字・二重引用符）でも UTF-8 2KB 以下")
        void ac5a_エスケープで膨らむ入力でも2KB以下() {
            String payload = factory.build(base()
                    .title(repeat("\u0001", 200))
                    .body(repeat("\u0001", 1000))
                    .actionUrl("/" + repeat("\"", 511))
                    .build());
            assertThat(payload.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(MAX_PAYLOAD_BYTES);
        }

        @Test
        @DisplayName("AC-5a: action_url は 512 バイトまで通し、513 バイトなら null")
        void ac5a_actionUrl512バイト境界() throws IOException {
            String url512 = "/" + repeat("c", 511);
            assertThat(build(base().actionUrl(url512).build()).get("data").get("action_url").asText())
                    .isEqualTo(url512);
            String url513 = "/" + repeat("c", 512);
            assertThat(build(base().actionUrl(url513).build()).get("data").get("action_url").isNull()).isTrue();
            // 日本語は UTF-8 で 3 バイト。171字 + "/" = 514 バイト → null
            String jp = "/" + repeat("あ", 171);
            assertThat(build(base().actionUrl(jp).build()).get("data").get("action_url").isNull()).isTrue();
        }
    }

    @Nested
    @DisplayName("シリアライズ失敗（AC-6）")
    class Fallback {

        @Test
        @DisplayName("AC-6: 失敗時の代替 JSON も AC-1 と同じ形（title は既定文言、action_url は null）、例外を投げない")
        void ac6_代替JSON() throws IOException {
            ObjectMapper failing = new ObjectMapper() {
                @Override
                public String writeValueAsString(Object value) throws JsonProcessingException {
                    throw new JsonProcessingException("boom") { };
                }
            };
            WebPushPayloadFactory broken = new WebPushPayloadFactory(failing, BASE_URL);
            NotificationEntity n = base().title("題\"\\").body("本文").build();

            assertThatCode(() -> broken.build(n)).doesNotThrowAnyException();
            JsonNode json = objectMapper.readTree(broken.build(n));
            assertThat(json.path("data").isObject()).as("代替 JSON に data オブジェクトがあること: %s", json).isTrue();
            assertThat(fieldNames(json)).containsExactlyInAnyOrder("title", "body", "data");
            assertThat(fieldNames(json.get("data")))
                    .containsExactlyInAnyOrder("notification_id", "action_url", "priority");
            assertThat(json.get("title").asText()).isEqualTo("Mannschaft");
            assertThat(json.get("data").get("action_url").isNull()).isTrue();
        }
    }

    @Nested
    @DisplayName("遷移先の判定（AC-7）")
    class UrlPolicy {

        @ParameterizedTest(name = "AC-7: 拒否して null: {0}")
        @ValueSource(strings = {
                "//evil.example/x",
                "/\\evil.example",
                "/a\\b",
                "\\\\evil.example",
                "https://evil.example/x",
                "http://app.example.com/x",
                "https://app.example.com:8443/x",
                "https://app.example.com.evil.example/x",
                "https://app.example.com@evil.example/x",
                "javascript:alert(1)",
                "data:text/html,<script>alert(1)</script>",
                "teams/x",
                "/\t/evil.example",
                "/\n/evil.example",
                " /teams/x",
                ""
        })
        void ac7_拒否(String url) throws IOException {
            JsonNode json = build(base().actionUrl(url).build());
            assertThat(json.get("data").get("action_url").isNull())
                    .as("action_url=%s は null になること", url)
                    .isTrue();
        }

        @ParameterizedTest(name = "AC-7: 通す: {0}")
        @ValueSource(strings = {
                "/",
                "/teams/fc-u-18/schedules/10",
                "/notifications?tab=unread#top",
                "https://app.example.com/teams/x",
                "https://app.example.com"
        })
        void ac7_通す(String url) throws IOException {
            JsonNode json = build(base().actionUrl(url).build());
            assertThat(json.get("data").get("action_url").asText()).isEqualTo(url);
        }
    }
}
