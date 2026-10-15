package com.mannschaft.app.team.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link UpdateTeamRequest} のバリデーションテスト（F15.4 Phase 5-β）。
 *
 * <p>{@code mapEmbedUrl} の {@code @Pattern} 制約を検証する。
 * 設計書: docs/features/F15.4_phase5_team_public_detail.md §5.2</p>
 */
@DisplayName("UpdateTeamRequest バリデーション")
class UpdateTeamRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    @DisplayName("mapEmbedUrl=null は許容される（地図なしも OK）")
    void mapEmbedUrl_null_OK() {
        UpdateTeamRequest req = new UpdateTeamRequest(
                null, null, null, null, null, null, null, null, null,
                null, 1L);
        Set<ConstraintViolation<UpdateTeamRequest>> violations = validator.validate(req);
        assertThat(violations).noneMatch(v -> v.getPropertyPath().toString().equals("mapEmbedUrl"));
    }

    @Test
    @DisplayName("mapEmbedUrl=正しい Google Maps embed URL は許容される")
    void mapEmbedUrl_validEmbed_OK() {
        String embedUrl = "https://www.google.com/maps/embed?pb=!1m18!1m12!1m3!1d12345.67890!2d139.7!3d35.6";
        UpdateTeamRequest req = new UpdateTeamRequest(
                null, null, null, null, null, null, null, null, null,
                embedUrl, 1L);
        Set<ConstraintViolation<UpdateTeamRequest>> violations = validator.validate(req);
        assertThat(violations).noneMatch(v -> v.getPropertyPath().toString().equals("mapEmbedUrl"));
    }

    @Test
    @DisplayName("mapEmbedUrl=Google Maps 通常 URL（/maps/ のみ、/embed なし）はエラー")
    void mapEmbedUrl_invalidNonEmbed_NG() {
        String invalidUrl = "https://www.google.com/maps/place/Tokyo";
        UpdateTeamRequest req = new UpdateTeamRequest(
                null, null, null, null, null, null, null, null, null,
                invalidUrl, 1L);
        Set<ConstraintViolation<UpdateTeamRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("mapEmbedUrl"));
    }

    @Test
    @DisplayName("mapEmbedUrl=外部サイト URL はエラー")
    void mapEmbedUrl_externalSite_NG() {
        String invalidUrl = "https://evil.example.com/maps/embed?pb=xxx";
        UpdateTeamRequest req = new UpdateTeamRequest(
                null, null, null, null, null, null, null, null, null,
                invalidUrl, 1L);
        Set<ConstraintViolation<UpdateTeamRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("mapEmbedUrl"));
    }

    @Test
    @DisplayName("mapEmbedUrl=http スキーム（https でない）はエラー")
    void mapEmbedUrl_httpScheme_NG() {
        String invalidUrl = "http://www.google.com/maps/embed?pb=xxx";
        UpdateTeamRequest req = new UpdateTeamRequest(
                null, null, null, null, null, null, null, null, null,
                invalidUrl, 1L);
        Set<ConstraintViolation<UpdateTeamRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("mapEmbedUrl"));
    }

    // ════════════════════════════════════════════════════════════
    // Jackson デシリアライズ（PATCH /teams/{slug} の 500 根治の回帰テスト）
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("JSON から Jackson でデシリアライズできる（no Creators 500 の回帰防止）")
    void deserialize_fromJson_OK() throws Exception {
        // 以前は @RequiredArgsConstructor + final フィールド + Creator なしで
        // InvalidDefinitionException: no Creators となり PATCH が 500 になっていた。
        ObjectMapper mapper = new ObjectMapper();
        String json = """
                {
                  "name": "更新後チーム名",
                  "nickname1": "ニック",
                  "visibility": "MEMBERS_AND_ABOVE",
                  "prefectureCode": "13",
                  "cityCode": "13104",
                  "version": 3
                }
                """;

        UpdateTeamRequest req = mapper.readValue(json, UpdateTeamRequest.class);

        assertThat(req.getName()).isEqualTo("更新後チーム名");
        assertThat(req.getNickname1()).isEqualTo("ニック");
        assertThat(req.getVisibility()).isEqualTo("MEMBERS_AND_ABOVE");
        assertThat(req.getPrefectureCode()).isEqualTo("13");
        assertThat(req.getCityCode()).isEqualTo("13104");
        assertThat(req.getVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("未知フィールドを含む JSON でもデシリアライズできる（500 にならない）")
    void deserialize_withUnknownProperty_OK() {
        ObjectMapper mapper = new ObjectMapper();
        String json = """
                { "name": "X", "version": 1, "unknownField": "ignored" }
                """;
        assertThatCode(() -> mapper.readValue(json, UpdateTeamRequest.class))
                .doesNotThrowAnyException();
    }
}
