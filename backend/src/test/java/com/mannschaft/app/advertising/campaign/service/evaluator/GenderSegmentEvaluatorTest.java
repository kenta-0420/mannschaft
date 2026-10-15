package com.mannschaft.app.advertising.campaign.service.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentInclusionMode;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link GenderSegmentEvaluator} 単体テスト。
 *
 * <p>Phase B 本実装後: users.gender_hash（HMAC-SHA256 ブラインドインデックス）を使った
 * SQL 検索を行う。EncryptionService と UserRepository は Mockito でモック化する。</p>
 */
@ExtendWith(MockitoExtension.class)
class GenderSegmentEvaluatorTest {

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private UserRepository userRepository;

    private GenderSegmentEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new GenderSegmentEvaluator(new ObjectMapper(), encryptionService, userRepository);
    }

    @Test
    @DisplayName("supports: GENDER のみ true")
    void supports_onlyGender() {
        assertThat(evaluator.supports(AdSegmentType.GENDER)).isTrue();
        assertThat(evaluator.supports(AdSegmentType.AGE_RANGE)).isFalse();
        assertThat(evaluator.supports(AdSegmentType.LOCALE)).isFalse();
    }

    @Test
    @DisplayName("resolveUserIds: 正常値 → HMAC ハッシュで Repository が呼ばれてユーザーIDを返す")
    void resolveUserIds_validValue_returnsMatchedUserIds() {
        when(encryptionService.hmac("MALE")).thenReturn("hash_male");
        when(encryptionService.hmac("FEMALE")).thenReturn("hash_female");
        when(userRepository.findUserIdsByGenderHashIn(List.of("hash_male", "hash_female")))
                .thenReturn(List.of(1L, 2L, 3L));

        AdAudienceSegment seg = segment("{\"genders\":[\"MALE\",\"FEMALE\"]}");
        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    @DisplayName("resolveUserIds: 大文字小文字を正規化して HMAC を計算する")
    void resolveUserIds_lowercaseNormalized() {
        when(encryptionService.hmac("MALE")).thenReturn("hash_male");
        when(userRepository.findUserIdsByGenderHashIn(anyList())).thenReturn(List.of(10L));

        AdAudienceSegment seg = segment("{\"genders\":[\"male\"]}");
        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).containsExactly(10L);
    }

    @Test
    @DisplayName("resolveUserIds: 4 種すべて許容 → マッチするユーザーIDを返す")
    void resolveUserIds_allFourGendersAllowed() {
        when(encryptionService.hmac(anyString())).thenReturn("some_hash");
        when(userRepository.findUserIdsByGenderHashIn(anyList())).thenReturn(List.of(1L));

        AdAudienceSegment seg = segment(
                "{\"genders\":[\"MALE\",\"FEMALE\",\"OTHER\",\"PREFER_NOT_TO_SAY\"]}");
        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).containsExactly(1L);
    }

    @Test
    @DisplayName("resolveUserIds: マッチなし → 空集合を返す（正常）")
    void resolveUserIds_noMatch_returnsEmpty() {
        when(encryptionService.hmac(anyString())).thenReturn("some_hash");
        when(userRepository.findUserIdsByGenderHashIn(anyList())).thenReturn(List.of());

        AdAudienceSegment seg = segment("{\"genders\":[\"OTHER\"]}");
        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).isEmpty();
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

    @Test
    @DisplayName("resolveUserIds: 不正な JSON → AD_AUDIENCE_INVALID")
    void resolveUserIds_malformedJson() {
        AdAudienceSegment seg = segment("not-json");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("countUserIds: 正常値 → COUNT クエリで件数を返す")
    void countUserIds_validValue_returnsCount() {
        when(encryptionService.hmac("MALE")).thenReturn("hash_male");
        when(encryptionService.hmac("FEMALE")).thenReturn("hash_female");
        when(userRepository.countUserIdsByGenderHashIn(List.of("hash_male", "hash_female")))
                .thenReturn(3L);

        AdAudienceSegment seg = segment("{\"genders\":[\"MALE\",\"FEMALE\"]}");
        long count = evaluator.countUserIds(seg);

        assertThat(count).isEqualTo(3L);
    }

    @Test
    @DisplayName("countUserIds: 不正な enum 値 → resolveUserIds と同じ AD_AUDIENCE_INVALID")
    void countUserIds_invalidValue_sameValidationAsResolve() {
        AdAudienceSegment seg = segment("{\"genders\":[\"ALIEN\"]}");
        assertThatThrownBy(() -> evaluator.countUserIds(seg))
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
