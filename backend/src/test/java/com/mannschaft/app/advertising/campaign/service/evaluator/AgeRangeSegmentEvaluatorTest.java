package com.mannschaft.app.advertising.campaign.service.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentInclusionMode;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Year;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgeRangeSegmentEvaluator} 単体テスト（Phase B 本実装）。
 *
 * <p>Phase B で users.birth_year を使った実装に差し替えたため、
 * UserRepository をモック化して年齢→生年変換の正確性と異常系を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgeRangeSegmentEvaluatorTest {

    @Mock
    private UserRepository userRepository;

    private AgeRangeSegmentEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new AgeRangeSegmentEvaluator(new ObjectMapper(), userRepository);
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
    @DisplayName("resolveUserIds: min=20, max=39 → birthYear 範囲に変換してユーザーIDを返す")
    void resolveUserIds_minMax_convertsTobirthYearRange() {
        int currentYear = Year.now().getValue();
        int expectedMinBirthYear = currentYear - 39;
        int expectedMaxBirthYear = currentYear - 20;
        when(userRepository.findUserIdsByBirthYearBetween(expectedMinBirthYear, expectedMaxBirthYear))
                .thenReturn(List.of(101L, 202L, 303L));

        AdAudienceSegment seg = segment("{\"min\":20,\"max\":39}");
        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).containsExactlyInAnyOrder(101L, 202L, 303L);
        verify(userRepository).findUserIdsByBirthYearBetween(expectedMinBirthYear, expectedMaxBirthYear);
    }

    @Test
    @DisplayName("resolveUserIds: min=0, max=100 の広範囲でも正常に動作する")
    void resolveUserIds_wideRange_works() {
        int currentYear = Year.now().getValue();
        int expectedMinBirthYear = currentYear - 100;
        int expectedMaxBirthYear = currentYear;
        when(userRepository.findUserIdsByBirthYearBetween(expectedMinBirthYear, expectedMaxBirthYear))
                .thenReturn(List.of(1L, 2L, 3L, 4L, 5L));

        AdAudienceSegment seg = segment("{\"min\":0,\"max\":100}");
        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).hasSize(5);
        verify(userRepository).findUserIdsByBirthYearBetween(expectedMinBirthYear, expectedMaxBirthYear);
    }

    @Test
    @DisplayName("resolveUserIds: min のみ指定 → max を MAX_PLAUSIBLE_AGE(130) で補完")
    void resolveUserIds_minOnly_complementsMax() {
        int currentYear = Year.now().getValue();
        int expectedMinBirthYear = currentYear - 130;  // MAX_PLAUSIBLE_AGE=130
        int expectedMaxBirthYear = currentYear - 60;
        when(userRepository.findUserIdsByBirthYearBetween(expectedMinBirthYear, expectedMaxBirthYear))
                .thenReturn(List.of(501L));

        AdAudienceSegment seg = segment("{\"min\":60}");
        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).containsExactly(501L);
        verify(userRepository).findUserIdsByBirthYearBetween(expectedMinBirthYear, expectedMaxBirthYear);
    }

    @Test
    @DisplayName("resolveUserIds: マッチなし → 空集合を返す（正常）")
    void resolveUserIds_noMatch_returnsEmpty() {
        when(userRepository.findUserIdsByBirthYearBetween(anyInt(), anyInt()))
                .thenReturn(List.of());

        AdAudienceSegment seg = segment("{\"min\":20,\"max\":39}");
        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).isEmpty();
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
    @DisplayName("resolveUserIds: 異常に高齢な値（130超） → AD_AUDIENCE_INVALID")
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
    @DisplayName("resolveUserIds: 文字列で渡された数値もパースしてリポジトリを呼ぶ")
    void resolveUserIds_stringNumbers() {
        int currentYear = Year.now().getValue();
        when(userRepository.findUserIdsByBirthYearBetween(currentYear - 39, currentYear - 20))
                .thenReturn(List.of(100L));

        AdAudienceSegment seg = segment("{\"min\":\"20\",\"max\":\"39\"}");
        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).containsExactly(100L);
    }

    @Test
    @DisplayName("countUserIds: min=20, max=39 → COUNT クエリで件数を返す")
    void countUserIds_minMax_returnsCount() {
        int currentYear = Year.now().getValue();
        int expectedMinBirthYear = currentYear - 39;
        int expectedMaxBirthYear = currentYear - 20;
        when(userRepository.countUserIdsByBirthYearBetween(expectedMinBirthYear, expectedMaxBirthYear))
                .thenReturn(3L);

        AdAudienceSegment seg = segment("{\"min\":20,\"max\":39}");
        long count = evaluator.countUserIds(seg);

        assertThat(count).isEqualTo(3L);
        verify(userRepository).countUserIdsByBirthYearBetween(expectedMinBirthYear, expectedMaxBirthYear);
        verify(userRepository, org.mockito.Mockito.never()).findUserIdsByBirthYearBetween(anyInt(), anyInt());
    }

    @Test
    @DisplayName("countUserIds: min/max 両方欠落 → resolveUserIds と同じ AD_AUDIENCE_INVALID")
    void countUserIds_bothMissing_sameValidationAsResolve() {
        AdAudienceSegment seg = segment("{}");
        assertThatThrownBy(() -> evaluator.countUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
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
