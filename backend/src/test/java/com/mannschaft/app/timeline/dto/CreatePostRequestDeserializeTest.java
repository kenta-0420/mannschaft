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
        // scopeId は String 化されたため、数値 0 は文字列 "0" にコアースされる
        assertThat(request.getScopeId()).isEqualTo("0");
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
        assertThat(request.getScopeId()).isEqualTo("42");
        assertThat(request.getPostedAsType()).isEqualTo("TEAM");
        assertThat(request.getPostedAsId()).isEqualTo(10L);
        assertThat(request.getStatus()).isEqualTo(PostStatus.DRAFT);
    }

    @Test
    @DisplayName("slug文字列のscopeIdをデシリアライズできる（FEが team-000092 等を送るケース・400 COMMON_001 根治）")
    void slug文字列scopeId_正常デシリアライズ() throws Exception {
        // FE はチーム/組織タイムラインで scope-id に slug 文字列を渡す。
        // scopeId が Long 型だと Jackson が "team-000092" を変換できず 400 COMMON_001 で落ちていた。
        String json = "{\"content\":\"チーム投稿\",\"scopeType\":\"TEAM\",\"scopeId\":\"team-000092\"}";

        CreatePostRequest request = objectMapper.readValue(json, CreatePostRequest.class);

        assertThat(request).isNotNull();
        assertThat(request.getScopeType()).isEqualTo("TEAM");
        assertThat(request.getScopeId()).isEqualTo("team-000092");
    }

    @Test
    @DisplayName("数値文字列のscopeIdをデシリアライズできる（後方互換: \"92\"）")
    void 数値文字列scopeId_正常デシリアライズ() throws Exception {
        String json = "{\"content\":\"投稿\",\"scopeType\":\"TEAM\",\"scopeId\":\"92\"}";

        CreatePostRequest request = objectMapper.readValue(json, CreatePostRequest.class);

        assertThat(request.getScopeId()).isEqualTo("92");
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
