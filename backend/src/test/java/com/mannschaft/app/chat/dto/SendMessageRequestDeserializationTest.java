package com.mannschaft.app.chat.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SendMessageRequest の Jackson デシリアライズ単体テスト。
 *
 * <p>チャットメッセージ送信 POST エンドポイントが 500 COMMON_999 を返す根本原因の確認：
 * SendMessageRequest に Jackson Creator（デフォルトコンストラクタ・@JsonCreator 等）が存在せず
 * 複数コンストラクタがあるため、Jackson がどれを使えばよいか判断できない。</p>
 *
 * <p>Red フェーズ: 修正前は InvalidDefinitionException が発生する（バグの実証）。
 * Green フェーズ: 修正後は正常にデシリアライズできる。</p>
 */
@DisplayName("SendMessageRequest Jackson デシリアライズ テスト")
class SendMessageRequestDeserializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("body のみの JSON をデシリアライズできる（最小ペイロード）")
    void body_のみのJSONをデシリアライズできる() throws Exception {
        // given: チャット送信の最小ペイロード
        String json = """
                {"body":"こんにちは"}
                """;

        // when: デシリアライズ
        SendMessageRequest result = objectMapper.readValue(json, SendMessageRequest.class);

        // then: body が取れること
        assertThat(result.getBody()).isEqualTo("こんにちは");
        assertThat(result.getParentId()).isNull();
        assertThat(result.getScheduledAt()).isNull();
        assertThat(result.getAttachments()).isNull();
    }

    @Test
    @DisplayName("parentId を指定した JSON もデシリアライズできる（スレッド返信）")
    void parentId_指定のJSONをデシリアライズできる() throws Exception {
        // given: スレッド返信ペイロード
        String json = """
                {"body":"返信です","parentId":42}
                """;

        // when: デシリアライズ
        SendMessageRequest result = objectMapper.readValue(json, SendMessageRequest.class);

        // then
        assertThat(result.getBody()).isEqualTo("返信です");
        assertThat(result.getParentId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("postedAsSubjectType を指定した JSON もデシリアライズできる（村ロビー投稿）")
    void postedAsSubjectType_指定のJSONをデシリアライズできる() throws Exception {
        // given: F17.1 Phase 3 の村ロビー投稿ペイロード
        String json = """
                {"body":"チーム代表発言","postedAsSubjectType":"TEAM","postedAsSubjectId":99}
                """;

        // when: デシリアライズ
        SendMessageRequest result = objectMapper.readValue(json, SendMessageRequest.class);

        // then
        assertThat(result.getBody()).isEqualTo("チーム代表発言");
        assertThat(result.getPostedAsSubjectType().name()).isEqualTo("TEAM");
        assertThat(result.getPostedAsSubjectId()).isEqualTo(99L);
    }
}
