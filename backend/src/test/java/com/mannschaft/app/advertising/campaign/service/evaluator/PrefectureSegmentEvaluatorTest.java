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
 * {@link PrefectureSegmentEvaluator} 単体テスト。
 *
 * <p>Phase B 本実装後: users.prefecture_code_hash（HMAC-SHA256 ブラインドインデックス）を
 * 使った SQL 検索を行う。EncryptionService と UserRepository は Mockito でモック化する。</p>
 */
@ExtendWith(MockitoExtension.class)
class PrefectureSegmentEvaluatorTest {

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private UserRepository userRepository;

    private PrefectureSegmentEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new PrefectureSegmentEvaluator(new ObjectMapper(), encryptionService, userRepository);
    }

    @Test
    @DisplayName("supports: REGION_PREFECTURE のみ true")
    void supports_onlyPrefecture() {
        assertThat(evaluator.supports(AdSegmentType.REGION_PREFECTURE)).isTrue();
        assertThat(evaluator.supports(AdSegmentType.REGION_CITY)).isFalse();
    }

    @Test
    @DisplayName("resolveUserIds: 正常な 2 桁コード → HMAC ハッシュで Repository が呼ばれてユーザーIDを返す")
    void resolveUserIds_validCodes_returnsMatchedUserIds() {
        when(encryptionService.hmac("13")).thenReturn("hash_tokyo");
        when(encryptionService.hmac("14")).thenReturn("hash_kanagawa");
        when(userRepository.findUserIdsByPrefectureCodeHashIn(List.of("hash_tokyo", "hash_kanagawa")))
                .thenReturn(List.of(101L, 202L));

        AdAudienceSegment seg = segment("{\"codes\":[\"13\",\"14\"]}");
        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).containsExactlyInAnyOrder(101L, 202L);
    }

    @Test
    @DisplayName("resolveUserIds: 01・47 境界値も受理して Repository を呼ぶ")
    void resolveUserIds_boundaryCodes_calledRepository() {
        when(encryptionService.hmac(anyString())).thenReturn("some_hash");
        when(userRepository.findUserIdsByPrefectureCodeHashIn(anyList())).thenReturn(List.of(1L));

        AdAudienceSegment seg = segment("{\"codes\":[\"01\",\"47\"]}");
        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).containsExactly(1L);
    }

    @Test
    @DisplayName("resolveUserIds: マッチなし → 空集合を返す（正常）")
    void resolveUserIds_noMatch_returnsEmpty() {
        when(encryptionService.hmac(anyString())).thenReturn("some_hash");
        when(userRepository.findUserIdsByPrefectureCodeHashIn(anyList())).thenReturn(List.of());

        AdAudienceSegment seg = segment("{\"codes\":[\"13\"]}");
        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).isEmpty();
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
    @DisplayName("countUserIds: 正常な 2 桁コード → COUNT クエリで件数を返す")
    void countUserIds_validCodes_returnsCount() {
        when(encryptionService.hmac("13")).thenReturn("hash_tokyo");
        when(encryptionService.hmac("14")).thenReturn("hash_kanagawa");
        when(userRepository.countUserIdsByPrefectureCodeHashIn(List.of("hash_tokyo", "hash_kanagawa")))
                .thenReturn(2L);

        AdAudienceSegment seg = segment("{\"codes\":[\"13\",\"14\"]}");
        long count = evaluator.countUserIds(seg);

        assertThat(count).isEqualTo(2L);
    }

    @Test
    @DisplayName("countUserIds: 48以上の存在しないコード → resolveUserIds と同じ AD_AUDIENCE_INVALID")
    void countUserIds_nonExistentCode_sameValidationAsResolve() {
        AdAudienceSegment seg = segment("{\"codes\":[\"48\"]}");
        assertThatThrownBy(() -> evaluator.countUserIds(seg))
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
