package com.mannschaft.app.advertising.campaign.service.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentInclusionMode;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.auth.repository.UserInterestTagRepository;
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
 * {@link InterestTagSegmentEvaluator} 単体テスト。
 *
 * <p>Phase A 本実装後: user_interest_tags テーブルを使った HMAC 検索を行う。
 * EncryptionService と UserInterestTagRepository は Mockito でモック化する。</p>
 */
@ExtendWith(MockitoExtension.class)
class InterestTagSegmentEvaluatorTest {

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private UserInterestTagRepository userInterestTagRepository;

    private InterestTagSegmentEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new InterestTagSegmentEvaluator(new ObjectMapper(), encryptionService, userInterestTagRepository);
    }

    @Test
    @DisplayName("supports: INTEREST_TAG のみ true")
    void supports_onlyInterestTag() {
        assertThat(evaluator.supports(AdSegmentType.INTEREST_TAG)).isTrue();
        assertThat(evaluator.supports(AdSegmentType.LOCALE)).isFalse();
    }

    @Test
    @DisplayName("resolveUserIds: 正常な tag_ids → マッチしたユーザーIDを返す")
    void resolveUserIds_validTagIds_returnsMatchedUserIds() {
        when(encryptionService.hmac("sports_football")).thenReturn("hash1");
        when(encryptionService.hmac("neighborhood_event")).thenReturn("hash2");
        when(userInterestTagRepository.findUserIdsByTagHashIn(List.of("hash1", "hash2")))
                .thenReturn(List.of(101L, 202L));

        AdAudienceSegment seg = segment("{\"tag_ids\":[\"sports_football\",\"neighborhood_event\"]}");
        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).containsExactlyInAnyOrder(101L, 202L);
    }

    @Test
    @DisplayName("resolveUserIds: マッチなし → 空集合を返す（正常）")
    void resolveUserIds_noMatch_returnsEmpty() {
        when(encryptionService.hmac(anyString())).thenReturn("some_hash");
        when(userInterestTagRepository.findUserIdsByTagHashIn(anyList())).thenReturn(List.of());

        AdAudienceSegment seg = segment("{\"tag_ids\":[\"unknown_tag\"]}");
        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).isEmpty();
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
    @DisplayName("resolveUserIds: 過長な tag (50超) → AD_AUDIENCE_INVALID")
    void resolveUserIds_tooLongTag() {
        String longTag = "a".repeat(51);
        AdAudienceSegment seg = segment("{\"tag_ids\":[\"" + longTag + "\"]}");
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

    @Test
    @DisplayName("countUserIds: 正常な tag_ids → COUNT クエリで件数を返す")
    void countUserIds_validTagIds_returnsCount() {
        when(encryptionService.hmac("sports_football")).thenReturn("hash1");
        when(encryptionService.hmac("neighborhood_event")).thenReturn("hash2");
        when(userInterestTagRepository.countUserIdsByTagHashIn(List.of("hash1", "hash2")))
                .thenReturn(2L);

        AdAudienceSegment seg = segment("{\"tag_ids\":[\"sports_football\",\"neighborhood_event\"]}");
        long count = evaluator.countUserIds(seg);

        assertThat(count).isEqualTo(2L);
    }

    @Test
    @DisplayName("countUserIds: tag_ids 配列欠落 → resolveUserIds と同じ AD_AUDIENCE_INVALID")
    void countUserIds_missingArray_sameValidationAsResolve() {
        AdAudienceSegment seg = segment("{}");
        assertThatThrownBy(() -> evaluator.countUserIds(seg))
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
