package com.mannschaft.app.advertising.campaign.service.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentInclusionMode;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link InterestTagSegmentEvaluator} 単体テスト。
 */
class InterestTagSegmentEvaluatorTest {

    private InterestTagSegmentEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new InterestTagSegmentEvaluator(new ObjectMapper());
    }

    @Test
    @DisplayName("supports: INTEREST_TAG のみ true")
    void supports_onlyInterestTag() {
        assertThat(evaluator.supports(AdSegmentType.INTEREST_TAG)).isTrue();
        assertThat(evaluator.supports(AdSegmentType.LOCALE)).isFalse();
    }

    @Test
    @DisplayName("resolveUserIds: 正常な tag_ids → データソース未整備例外")
    void resolveUserIds_validTagIds() {
        AdAudienceSegment seg = segment("{\"tag_ids\":[\"sports_football\",\"neighborhood_event\"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(SegmentDataSourceNotAvailableException.class)
                .satisfies(e -> assertThat(((SegmentDataSourceNotAvailableException) e).getSegmentType())
                        .isEqualTo(AdSegmentType.INTEREST_TAG));
    }

    @Test
    @DisplayName("resolveUserIds: tag_ids 配列欠落 → AD_AUDIENCE_INVALID")
    void resolveUserIds_missingArray() {
        AdAudienceSegment seg = segment("{}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: 空配列 → AD_AUDIENCE_INVALID")
    void resolveUserIds_emptyArray() {
        AdAudienceSegment seg = segment("{\"tag_ids\":[]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: 過長な tag_id (60超) → AD_AUDIENCE_INVALID")
    void resolveUserIds_tooLongTagId() {
        String longId = "a".repeat(61);
        AdAudienceSegment seg = segment("{\"tag_ids\":[\"" + longId + "\"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: 全て空文字 → AD_AUDIENCE_INVALID")
    void resolveUserIds_allBlank() {
        AdAudienceSegment seg = segment("{\"tag_ids\":[\"\",\"  \"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: 不正な JSON → AD_AUDIENCE_INVALID")
    void resolveUserIds_malformedJson() {
        AdAudienceSegment seg = segment("not-json");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    private static AdAudienceSegment segment(String json) {
        AdAudienceSegment s = AdAudienceSegment.builder()
                .campaignId(UUID.randomUUID())
                .segmentType(AdSegmentType.INTEREST_TAG)
                .segmentValue(json)
                .inclusionMode(AdSegmentInclusionMode.INCLUDE)
                .build();
        s.setId(UUID.randomUUID());
        return s;
    }
}
