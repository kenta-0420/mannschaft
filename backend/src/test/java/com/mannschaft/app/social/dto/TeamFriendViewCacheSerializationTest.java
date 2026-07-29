package com.mannschaft.app.social.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TeamFriendView} のキャッシュ往復シリアライズ回帰テスト（issue #2496）。
 *
 * <h2>なぜ必要なのか</h2>
 * <p>{@code teamFriendList} キャッシュが実際に発火するようになったため、本 DTO は
 * Valkey へ JSON シリアライズされ、ヒット時に JSON から復元される。
 * Lombok の {@code @Builder} が生成する暗黙コンストラクタの引数名は {@code isPublic} だが、
 * {@code @Getter} が生成する {@code isPublic()} を Jackson は {@code "public"} という名前で
 * 書き出す。この非対称を放置すると、往復で {@code isPublic} が<b>常に {@code false} に化ける</b>
 * （非公開フレンドが公開扱いになる／公開フレンドが非公開表示になる）。
 * さらに {@code LoggingCacheErrorHandler} の fail-open によりデシリアライズ失敗系は
 * WARN に握り潰されるため、実運用では極めて気付きにくい。</p>
 *
 * <p>本テストは {@code RedisConfig#redisCacheConfiguration()} と同じモジュール構成
 * （{@link JavaTimeModule} + {@link ParameterNamesModule}）の {@link ObjectMapper} で
 * 往復させ、全フィールドが保存されることを固定する。</p>
 */
@DisplayName("TeamFriendView キャッシュ往復シリアライズ回帰テスト (issue #2496)")
class TeamFriendViewCacheSerializationTest {

    /** {@code RedisConfig} と同じモジュール構成の ObjectMapper。 */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new ParameterNamesModule());

    private static TeamFriendView sample(boolean isPublic) {
        return TeamFriendView.builder()
                .teamFriendId(1L)
                .friendTeamId(20L)
                .friendTeamName("フレンドチーム")
                .isPublic(isPublic)
                .establishedAt(LocalDateTime.of(2026, 7, 29, 12, 34, 56))
                .build();
    }

    @Test
    @DisplayName("isPublic=true が JSON 往復で保存される（false に化けない）")
    void isPublicTrueが往復で保存される() throws Exception {
        TeamFriendView original = sample(true);

        String json = objectMapper.writeValueAsString(original);
        TeamFriendView restored = objectMapper.readValue(json, TeamFriendView.class);

        assertThat(restored.isPublic())
                .as("往復で isPublic が false に化けると、非公開フレンドが公開表示される")
                .isTrue();
    }

    @Test
    @DisplayName("isPublic=false も JSON 往復で保存される")
    void isPublicFalseが往復で保存される() throws Exception {
        TeamFriendView restored = objectMapper.readValue(
                objectMapper.writeValueAsString(sample(false)), TeamFriendView.class);

        assertThat(restored.isPublic()).isFalse();
    }

    @Test
    @DisplayName("全フィールドが JSON 往復で保存される")
    void 全フィールドが往復で保存される() throws Exception {
        TeamFriendView original = sample(true);

        TeamFriendView restored = objectMapper.readValue(
                objectMapper.writeValueAsString(original), TeamFriendView.class);

        assertThat(restored.getTeamFriendId()).isEqualTo(original.getTeamFriendId());
        assertThat(restored.getFriendTeamId()).isEqualTo(original.getFriendTeamId());
        assertThat(restored.getFriendTeamName()).isEqualTo(original.getFriendTeamName());
        assertThat(restored.isPublic()).isEqualTo(original.isPublic());
        assertThat(restored.getEstablishedAt()).isEqualTo(original.getEstablishedAt());
    }

    @Test
    @DisplayName("公開フィールド名は従来どおり \"public\"（API 互換・OpenAPI スキーマ不変）")
    void 公開フィールド名はpublicのまま() throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(
                objectMapper.writeValueAsString(sample(true)));

        assertThat(node.has("public"))
                .as("フロントエンド／OpenAPI が参照するフィールド名を変えてはならない")
                .isTrue();
        assertThat(node.get("public").asBoolean()).isTrue();
        assertThat(node.has("isPublic"))
                .as("isPublic という別名が増えると二重表現になり API 契約が揺れる")
                .isFalse();
    }
}
