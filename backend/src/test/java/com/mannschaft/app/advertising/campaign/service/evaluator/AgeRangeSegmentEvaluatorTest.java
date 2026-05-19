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
 * {@link AgeRangeSegmentEvaluator} 単体テスト。
 *
 * <p>データソース未整備のため、構造バリデーション網羅と
 * {@link SegmentDataSourceNotAvailableException} 投げ分けを検証する。</p>
 */
class AgeRangeSegmentEvaluatorTest {

    private AgeRangeSegmentEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new AgeRangeSegmentEvaluator(new ObjectMapper());
    }

    @Test
    @DisplayName("supports: AGE_RANGE のみ true、それ以外は false")
    void supports_onlyAgeRange() {
        assertThat(evaluator.supports(AdSegmentType.AGE_RANGE)).isTrue();
        assertThat(evaluator.supports(AdSegmentType.GENDER)).isFalse();
        assertThat(evaluator.supports(AdSegmentType.LOCALE)).isFalse();
        assertThat(evaluator.supports(AdSegmentType.DEVICE)).isFalse();
    }

    @Test
    @DisplayName("resolveUserIds: 正常な min/max → データソース未整備例外")
    void resolveUserIds_validValueThrowsDataSourceUnavailable() {
        AdAudienceSegment seg = segment("{\"min\":20,\"max\":39}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(SegmentDataSourceNotAvailableException.class)
                .satisfies(e -> {
                    SegmentDataSourceNotAvailableException ex = (SegmentDataSourceNotAvailableException) e;
                    assertThat(ex.getSegmentType()).isEqualTo(AdSegmentType.AGE_RANGE);
                    assertThat(ex.getMissingDataSource()).contains("birth_year");
                });
    }

    @Test
    @DisplayName("resolveUserIds: min のみ指定でもデータソース未整備例外（構造的には有効）")
    void resolveUserIds_minOnlyThrowsDataSourceUnavailable() {
        AdAudienceSegment seg = segment("{\"min\":60}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(SegmentDataSourceNotAvailableException.class);
    }

    @Test
    @DisplayName("resolveUserIds: min/max 両方欠落 → AD_AUDIENCE_INVALID")
    void resolveUserIds_bothMissing() {
        AdAudienceSegment seg = segment("{}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: min > max → AD_AUDIENCE_INVALID")
    void resolveUserIds_minGreaterThanMax() {
        AdAudienceSegment seg = segment("{\"min\":50,\"max\":20}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: 負の値 → AD_AUDIENCE_INVALID")
    void resolveUserIds_negativeAge() {
        AdAudienceSegment seg = segment("{\"min\":-5,\"max\":20}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: 異常に高齢な値 → AD_AUDIENCE_INVALID")
    void resolveUserIds_unrealisticallyHighAge() {
        AdAudienceSegment seg = segment("{\"min\":20,\"max\":300}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: 不正な JSON → AD_AUDIENCE_INVALID")
    void resolveUserIds_malformedJson() {
        AdAudienceSegment seg = segment("not-a-json");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: 文字列で渡された数値もパースする")
    void resolveUserIds_stringNumbers() {
        AdAudienceSegment seg = segment("{\"min\":\"20\",\"max\":\"39\"}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(SegmentDataSourceNotAvailableException.class);
    }

    private static AdAudienceSegment segment(String json) {
        AdAudienceSegment s = AdAudienceSegment.builder()
                .campaignId(UUID.randomUUID())
                .segmentType(AdSegmentType.AGE_RANGE)
                .segmentValue(json)
                .inclusionMode(AdSegmentInclusionMode.INCLUDE)
                .build();
        s.setId(UUID.randomUUID());
        return s;
    }
}
