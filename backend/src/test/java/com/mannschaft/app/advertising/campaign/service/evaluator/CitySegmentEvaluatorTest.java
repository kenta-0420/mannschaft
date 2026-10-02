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
 * {@link CitySegmentEvaluator} 単体テスト。
 *
 * <p>Phase B 本実装後: users.city_code_hash（HMAC-SHA256 ブラインドインデックス）を
 * 使った SQL 検索を行う。EncryptionService と UserRepository は Mockito でモック化する。</p>
 */
@ExtendWith(MockitoExtension.class)
class CitySegmentEvaluatorTest {

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private UserRepository userRepository;

    private CitySegmentEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new CitySegmentEvaluator(new ObjectMapper(), encryptionService, userRepository);
    }

    @Test
    @DisplayName("supports: REGION_CITY のみ true")
    void supports_onlyCity() {
        assertThat(evaluator.supports(AdSegmentType.REGION_CITY)).isTrue();
        assertThat(evaluator.supports(AdSegmentType.REGION_PREFECTURE)).isFalse();
    }

    @Test
    @DisplayName("resolveUserIds: 正常な 5 桁コード → HMAC ハッシュで Repository が呼ばれてユーザーIDを返す")
    void resolveUserIds_validCodes_returnsMatchedUserIds() {
        when(encryptionService.hmac("13113")).thenReturn("hash_shinjuku");
        when(encryptionService.hmac("13104")).thenReturn("hash_shinjuku2");
        when(userRepository.findUserIdsByCityCodeHashIn(List.of("hash_shinjuku", "hash_shinjuku2")))
                .thenReturn(List.of(101L, 202L));

        AdAudienceSegment seg = segment("{\"codes\":[\"13113\",\"13104\"]}");
        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).containsExactlyInAnyOrder(101L, 202L);
    }

    @Test
    @DisplayName("resolveUserIds: マッチなし → 空集合を返す（正常）")
    void resolveUserIds_noMatch_returnsEmpty() {
        when(encryptionService.hmac(anyString())).thenReturn("some_hash");
        when(userRepository.findUserIdsByCityCodeHashIn(anyList())).thenReturn(List.of());

        AdAudienceSegment seg = segment("{\"codes\":[\"13113\"]}");
        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).isEmpty();
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

    @Test
    @DisplayName("resolveUserIds: 空配列 → AD_AUDIENCE_INVALID")
    void resolveUserIds_emptyArray() {
        AdAudienceSegment seg = segment("{\"codes\":[]}");
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
    @DisplayName("countUserIds: 正常な 5 桁コード → COUNT クエリで件数を返す")
    void countUserIds_validCodes_returnsCount() {
        when(encryptionService.hmac("13113")).thenReturn("hash_shinjuku");
        when(encryptionService.hmac("13104")).thenReturn("hash_shinjuku2");
        when(userRepository.countUserIdsByCityCodeHashIn(List.of("hash_shinjuku", "hash_shinjuku2")))
                .thenReturn(2L);

        AdAudienceSegment seg = segment("{\"codes\":[\"13113\",\"13104\"]}");
        long count = evaluator.countUserIds(seg);

        assertThat(count).isEqualTo(2L);
    }

    @Test
    @DisplayName("countUserIds: 4 桁 → resolveUserIds と同じ AD_AUDIENCE_INVALID")
    void countUserIds_fourDigit_sameValidationAsResolve() {
        AdAudienceSegment seg = segment("{\"codes\":[\"1234\"]}");
        assertThatThrownBy(() -> evaluator.countUserIds(seg))
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
