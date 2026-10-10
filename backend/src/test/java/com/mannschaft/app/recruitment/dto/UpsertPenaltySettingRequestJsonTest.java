package com.mannschaft.app.recruitment.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.config.JacksonConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ペナルティ設定の有効フラグ {@code isEnabled} が JSON と往復できることを、
 * アプリの ObjectMapper（{@link JacksonConfig}）で検証する。
 *
 * <p>boolean の {@code isEnabled} フィールドに @Getter だけを付けると、Lombok の getter は
 * {@code isEnabled()} となり Jackson はプロパティ名を {@code enabled} と解釈するため、
 * 画面が送る {@code isEnabled} キーが読めず既定値 true のままになっていた。</p>
 */
@DisplayName("ペナルティ設定 isEnabled の JSON 往復テスト")
class UpsertPenaltySettingRequestJsonTest {

    private final ObjectMapper objectMapper =
            new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder());

    @Test
    @DisplayName("リクエスト: isEnabled=false が読み込まれる")
    void リクエストのisEnabledがfalseで読める() throws Exception {
        UpsertPenaltySettingRequest req = objectMapper.readValue(
                "{\"isEnabled\":false,\"thresholdCount\":5}", UpsertPenaltySettingRequest.class);

        assertThat(req.isEnabled()).isFalse();
        assertThat(req.getThresholdCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("リクエスト: isEnabled を省略すると既定の true になる")
    void リクエストのisEnabled省略は既定true() throws Exception {
        UpsertPenaltySettingRequest req = objectMapper.readValue(
                "{\"thresholdCount\":5}", UpsertPenaltySettingRequest.class);

        assertThat(req.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("レスポンス: isEnabled キーで出力される（画面が s.isEnabled を読む）")
    void レスポンスはisEnabledキーで出力される() throws Exception {
        RecruitmentPenaltySettingResponse res = new RecruitmentPenaltySettingResponse(
                1L, "TEAM", 2L, false, 3, 180, 30, "THIS_SCOPE_ONLY", false, 30, "c", "u");

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(res));

        assertThat(node.has("isEnabled")).isTrue();
        assertThat(node.get("isEnabled").asBoolean()).isFalse();
    }
}
