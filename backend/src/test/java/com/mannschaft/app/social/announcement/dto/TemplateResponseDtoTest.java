package com.mannschaft.app.social.announcement.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TemplateResponseDto} の JSON シリアライズ契約テスト。
 *
 * <p>告知ウィザード Step1 のテンプレート一覧/既定選択/適用は FE が camelCase キー
 * （targetRole / preferredChannel / isDefault / scopeType / scopeId / targetTeamIds /
 * createdBy / createdAt）で読む。@JsonProperty(snake_case) 撤去後に確実に camelCase で
 * 出力されること、特に {@code isDefault} が Jackson の is-getter 規則で "default" に
 * 化けないことを検証する（旧 snake_case 契約への逆戻り検出）。</p>
 */
@DisplayName("TemplateResponseDto JSON シリアライズ契約テスト")
class TemplateResponseDtoTest {

    // JavaTimeModule 等を登録して LocalDateTime をシリアライズ可能にする
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("全フィールドが camelCase キーで出力され、snake_case キーは含まれないこと")
    void serializesAllFieldsAsCamelCase() throws Exception {
        // given
        TemplateResponseDto dto = TemplateResponseDto.builder()
                .id(1L)
                .scopeType("TEAM")
                .scopeId(10L)
                .name("既定テンプレート")
                .targetRole("MEMBERS_AND_ABOVE")
                .targetTeamIds(List.of(1L, 2L))
                .preferredChannel("SCHEDULE")
                .isDefault(true)
                .createdBy(99L)
                .createdAt(LocalDateTime.of(2026, 6, 30, 10, 0))
                .build();

        // when
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(dto));

        // then: camelCase キーが存在
        assertThat(json.has("scopeType")).isTrue();
        assertThat(json.has("scopeId")).isTrue();
        assertThat(json.has("targetRole")).isTrue();
        assertThat(json.has("targetTeamIds")).isTrue();
        assertThat(json.has("preferredChannel")).isTrue();
        assertThat(json.has("isDefault")).isTrue();
        assertThat(json.has("createdBy")).isTrue();
        assertThat(json.has("createdAt")).isTrue();
        assertThat(json.get("isDefault").asBoolean()).isTrue();

        // snake_case キー・is-getter 化け（"default"）は存在しない
        assertThat(json.has("scope_type")).isFalse();
        assertThat(json.has("scope_id")).isFalse();
        assertThat(json.has("target_role")).isFalse();
        assertThat(json.has("target_team_ids")).isFalse();
        assertThat(json.has("preferred_channel")).isFalse();
        assertThat(json.has("is_default")).isFalse();
        assertThat(json.has("default")).isFalse();
        assertThat(json.has("created_by")).isFalse();
        assertThat(json.has("created_at")).isFalse();
    }
}
