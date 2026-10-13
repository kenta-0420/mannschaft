package com.mannschaft.app.social.announcement.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 AC-H17: お知らせ一覧（送信履歴）の DTO が、送信時の宛先指定の記録（target_audience）を公開する。
 * 記録は送信時の名前で残るので、グループを改名・削除しても変わらない。
 */
@DisplayName("AnnouncementFeedItemDto の targetAudience 公開（AC-H17）")
class AnnouncementFeedItemDtoTargetAudienceTest {

    private static final String AUDIENCE_JSON = "{\"mode\":\"GROUPS\","
            + "\"groups\":[{\"id\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"平成18年度\"},"
            + "{\"id\":\"22222222-2222-2222-2222-222222222222\",\"name\":\"北地区\"}],"
            + "\"range\":{\"fromGroupId\":null,\"fromGroupName\":null,"
            + "\"toGroupId\":\"11111111-1111-1111-1111-111111111111\",\"toGroupName\":\"平成18年度\"},"
            + "\"includeUnassigned\":true,\"teamCount\":12,\"directMemberCount\":3}";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private AnnouncementFeedEntity feed(String targetAudience) {
        return AnnouncementFeedEntity.builder().targetAudience(targetAudience).build();
    }

    @Test
    @DisplayName("FULL: 送信時の記録（グループ名・範囲・未分類・チーム数）がそのまま JSON に出る")
    void exposesRecordedAudience() throws Exception {
        var item = new AnnouncementFeedService.AnnouncementFeedItem(feed(AUDIENCE_JSON), false);

        JsonNode json = objectMapper.valueToTree(AnnouncementFeedItemDto.from(item));

        JsonNode audience = json.get("targetAudience");
        assertThat(audience).isNotNull();
        assertThat(audience.get("mode").asText()).isEqualTo("GROUPS");
        assertThat(audience.get("groups").get(0).get("name").asText()).isEqualTo("平成18年度");
        assertThat(audience.get("groups")).hasSize(2);
        assertThat(audience.get("range").get("toGroupName").asText()).isEqualTo("平成18年度");
        assertThat(audience.get("includeUnassigned").asBoolean()).isTrue();
        assertThat(audience.get("teamCount").asInt()).isEqualTo(12);
        assertThat(audience.get("directMemberCount").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("宛先を絞っていない告知（記録なし）は targetAudience が null")
    void nullWhenNoRecord() {
        var item = new AnnouncementFeedService.AnnouncementFeedItem(feed(null), false);

        assertThat(AnnouncementFeedItemDto.from(item).getTargetAudience()).isNull();
    }

    @Test
    @DisplayName("LOCKED: 本文と同様に宛先の記録も出さない")
    void hiddenWhenLocked() {
        var item = new AnnouncementFeedService.AnnouncementFeedItem(feed(AUDIENCE_JSON), false, "LOCKED", null);

        assertThat(AnnouncementFeedItemDto.from(item).getTargetAudience()).isNull();
    }

    @Test
    @DisplayName("記録の JSON が壊れていたら黙って null にせず例外にする")
    void failsLoudlyOnCorruptRecord() {
        var item = new AnnouncementFeedService.AnnouncementFeedItem(feed("{not json"), false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AnnouncementFeedItemDto.from(item))
                .isInstanceOf(IllegalStateException.class);
    }
}
