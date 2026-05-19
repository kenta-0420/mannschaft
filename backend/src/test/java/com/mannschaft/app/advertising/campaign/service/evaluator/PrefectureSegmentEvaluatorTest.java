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
 * {@link PrefectureSegmentEvaluator} 単体テスト。
 */
class PrefectureSegmentEvaluatorTest {

    private PrefectureSegmentEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new PrefectureSegmentEvaluator(new ObjectMapper());
    }

    @Test
    @DisplayName("supports: REGION_PREFECTURE のみ true")
    void supports_onlyPrefecture() {
        assertThat(evaluator.supports(AdSegmentType.REGION_PREFECTURE)).isTrue();
        assertThat(evaluator.supports(AdSegmentType.REGION_CITY)).isFalse();
    }

    @Test
    @DisplayName("resolveUserIds: 正常な 2 桁コード → データソース未整備例外")
    void resolveUserIds_validCodes() {
        AdAudienceSegment seg = segment("{\"codes\":[\"13\",\"14\"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(SegmentDataSourceNotAvailableException.class)
                .satisfies(e -> assertThat(((SegmentDataSourceNotAvailableException) e).getSegmentType())
                        .isEqualTo(AdSegmentType.REGION_PREFECTURE));
    }

    @Test
    @DisplayName("resolveUserIds: 01・47 境界値も受理")
    void resolveUserIds_boundaryCodes() {
        AdAudienceSegment seg = segment("{\"codes\":[\"01\",\"47\"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(SegmentDataSourceNotAvailableException.class);
    }

    @Test
    @DisplayName("resolveUserIds: 形式不正 (3桁) → AD_AUDIENCE_INVALID")
    void resolveUserIds_invalidFormat() {
        AdAudienceSegment seg = segment("{\"codes\":[\"123\"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: 48以上の存在しないコード → AD_AUDIENCE_INVALID")
    void resolveUserIds_nonExistentCode() {
        AdAudienceSegment seg = segment("{\"codes\":[\"48\"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: 00 コード → AD_AUDIENCE_INVALID")
    void resolveUserIds_zeroCode() {
        AdAudienceSegment seg = segment("{\"codes\":[\"00\"]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: codes 配列欠落 → AD_AUDIENCE_INVALID")
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
        AdAudienceSegment seg = segment("{\"codes\":[]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    private static AdAudienceSegment segment(String json) {
        AdAudienceSegment s = AdAudienceSegment.builder()
                .campaignId(UUID.randomUUID())
                .segmentType(AdSegmentType.REGION_PREFECTURE)
                .segmentValue(json)
                .inclusionMode(AdSegmentInclusionMode.INCLUDE)
                .build();
        s.setId(UUID.randomUUID());
        return s;
    }
}
