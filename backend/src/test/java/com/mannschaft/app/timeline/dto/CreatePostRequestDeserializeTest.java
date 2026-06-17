package com.mannschaft.app.timeline.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.mannschaft.app.timeline.PostStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link CreatePostRequest} の Jackson デシリアライズ再発防止テスト。
 *
 * <p>複数コンストラクタが存在するにもかかわらず {@code @JsonCreator} が付いていない状態では、
 * {@code POST /api/v1/timeline/posts} が 500（no suitable creator）で落ちていた。
 * 本テストは camelCase JSON を {@link CreatePostRequest} にデシリアライズし、
 * 例外が発生しないこと・フィールドが正しくマップされることを固定する。</p>
 *
 * <p>ObjectMapper の設定は {@link com.mannschaft.app.config.JacksonConfig} と合わせている
 * （{@link ParameterNamesModule} + {@link JavaTimeModule} + WRITE_DATES_AS_TIMESTAMPS 無効）。</p>
 */
@DisplayName("CreatePostRequest Jackson デシリアライズ テスト")
class CreatePostRequestDeserializeTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // JacksonConfig#objectMapper と同等の設定を再現
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new ParameterNamesModule(JsonCreator.Mode.DEFAULT))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    @DisplayName("最小フィールドのcamelCase JSONをデシリアライズできる（no-Creators 500 が再発しないことを保証）")
    void 最小フィールドJSON_正常デシリアライズ() throws Exception {
        String json = "{\"content\":\"テスト投稿\",\"scopeType\":\"PUBLIC\",\"scopeId\":0}";

        CreatePostRequest request = objectMapper.readValue(json, CreatePostRequest.class);

        assertThat(request).isNotNull();
        assertThat(request.getContent()).isEqualTo("テスト投稿");
        assertThat(request.getScopeType()).isEqualTo("PUBLIC");
        assertThat(request.getScopeId()).isEqualTo(0L);
    }

    @Test
    @DisplayName("全フィールドを含む camelCase JSON をデシリアライズできる")
    void 全フィールドJSON_正常デシリアライズ() throws Exception {
        String json = "{"
                + "\"content\":\"フル投稿\","
                + "\"scopeType\":\"TEAM\","
                + "\"scopeId\":42,"
                + "\"postedAsType\":\"TEAM\","
                + "\"postedAsId\":10,"
                + "\"parentId\":null,"
                + "\"repostOfId\":null,"
                + "\"scheduledAt\":null,"
                + "\"poll\":null,"
                + "\"attachments\":null,"
                + "\"status\":\"DRAFT\","
                + "\"scopeVillageId\":null"
                + "}";

        CreatePostRequest request = objectMapper.readValue(json, CreatePostRequest.class);

        assertThat(request).isNotNull();
        assertThat(request.getContent()).isEqualTo("フル投稿");
        assertThat(request.getScopeType()).isEqualTo("TEAM");
        assertThat(request.getScopeId()).isEqualTo(42L);
        assertThat(request.getPostedAsType()).isEqualTo("TEAM");
        assertThat(request.getPostedAsId()).isEqualTo(10L);
        assertThat(request.getStatus()).isEqualTo(PostStatus.DRAFT);
    }

    @Test
    @DisplayName("オプショナルフィールドが null でもデシリアライズ成功する")
    void オプショナルフィールドnull_デシリアライズ成功() {
        String json = "{\"content\":\"最小投稿\"}";

        assertThatCode(() -> objectMapper.readValue(json, CreatePostRequest.class))
                .as("@JsonCreator が正しく機能し、no suitable creator 例外が発生しないこと")
                .doesNotThrowAnyException();
    }
}
