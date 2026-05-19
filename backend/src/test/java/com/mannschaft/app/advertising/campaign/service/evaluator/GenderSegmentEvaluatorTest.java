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
 * {@link GenderSegmentEvaluator} 単体テスト。データソース未整備のため
 * 構造バリデーション + データソース未整備例外を検証する。
 */
class GenderSegmentEvaluatorTest {

    private GenderSegmentEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new GenderSegmentEvaluator(new ObjectMapper());
    }

    @Test
    @DisplayName("supports: GENDER のみ true")
    void supports_onlyGender() {
        assertThat(evaluator.supports(AdSegmentType.GENDER)).isTrue();
        assertThat(evaluator.supports(AdSegmentType.AGE_RANGE)).isFalse();
        assertThat(evaluator.supports(AdSegmentType.LOCALE)).isFalse();
    }

    @Test
    @DisplayName("resolveUserIds: 正常値 → データソース未整備例外")
    void resolveUserIds_validValue() {
        AdAudienceSegment seg = segment("{\"genders\":[\"MALE\",\"FEMALE\"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(SegmentDataSourceNotAvailableException.class)
                .satisfies(e -> assertThat(((SegmentDataSourceNotAvailableException) e).getSegmentType())
                        .isEqualTo(AdSegmentType.GENDER));
    }

    @Test
    @DisplayName("resolveUserIds: 大文字小文字許容（lowercase → 正規化）")
    void resolveUserIds_lowercaseNormalized() {
        AdAudienceSegment seg = segment("{\"genders\":[\"male\"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(SegmentDataSourceNotAvailableException.class);
    }

    @Test
    @DisplayName("resolveUserIds: 4 種すべて許容")
    void resolveUserIds_allFourGendersAllowed() {
        AdAudienceSegment seg = segment(
                "{\"genders\":[\"MALE\",\"FEMALE\",\"OTHER\",\"PREFER_NOT_TO_SAY\"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(SegmentDataSourceNotAvailableException.class);
    }

    @Test
    @DisplayName("resolveUserIds: 不正な enum 値 → AD_AUDIENCE_INVALID")
    void resolveUserIds_invalidValue() {
        AdAudienceSegment seg = segment("{\"genders\":[\"ALIEN\"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: genders 配列欠落 → AD_AUDIENCE_INVALID")
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
        AdAudienceSegment seg = segment("{\"genders\":[]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: 空文字列のみ → AD_AUDIENCE_INVALID")
    void resolveUserIds_onlyBlankString() {
        AdAudienceSegment seg = segment("{\"genders\":[\"\",\"  \"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    private static AdAudienceSegment segment(String json) {
        AdAudienceSegment s = AdAudienceSegment.builder()
                .campaignId(UUID.randomUUID())
                .segmentType(AdSegmentType.GENDER)
                .segmentValue(json)
                .inclusionMode(AdSegmentInclusionMode.INCLUDE)
                .build();
        s.setId(UUID.randomUUID());
        return s;
    }
}
