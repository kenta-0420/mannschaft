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
 * {@link CitySegmentEvaluator} 単体テスト。
 */
class CitySegmentEvaluatorTest {

    private CitySegmentEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new CitySegmentEvaluator(new ObjectMapper());
    }

    @Test
    @DisplayName("supports: REGION_CITY のみ true")
    void supports_onlyCity() {
        assertThat(evaluator.supports(AdSegmentType.REGION_CITY)).isTrue();
        assertThat(evaluator.supports(AdSegmentType.REGION_PREFECTURE)).isFalse();
    }

    @Test
    @DisplayName("resolveUserIds: 正常な 5 桁コード → データソース未整備例外")
    void resolveUserIds_validCodes() {
        AdAudienceSegment seg = segment("{\"codes\":[\"13113\",\"13104\"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(SegmentDataSourceNotAvailableException.class)
                .satisfies(e -> assertThat(((SegmentDataSourceNotAvailableException) e).getSegmentType())
                        .isEqualTo(AdSegmentType.REGION_CITY));
    }

    @Test
    @DisplayName("resolveUserIds: 4 桁 → AD_AUDIENCE_INVALID")
    void resolveUserIds_fourDigit() {
        AdAudienceSegment seg = segment("{\"codes\":[\"1234\"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: 6 桁 → AD_AUDIENCE_INVALID")
    void resolveUserIds_sixDigit() {
        AdAudienceSegment seg = segment("{\"codes\":[\"123456\"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: 英字混入 → AD_AUDIENCE_INVALID")
    void resolveUserIds_alphabetMixed() {
        AdAudienceSegment seg = segment("{\"codes\":[\"1311A\"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: 配列欠落 → AD_AUDIENCE_INVALID")
    void resolveUserIds_missingArray() {
        AdAudienceSegment seg = segment("{}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    private static AdAudienceSegment segment(String json) {
        AdAudienceSegment s = AdAudienceSegment.builder()
                .campaignId(UUID.randomUUID())
                .segmentType(AdSegmentType.REGION_CITY)
                .segmentValue(json)
                .inclusionMode(AdSegmentInclusionMode.INCLUDE)
                .build();
        s.setId(UUID.randomUUID());
        return s;
    }
}
