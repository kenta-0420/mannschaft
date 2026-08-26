package com.mannschaft.app.advertising.campaign.service.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentInclusionMode;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.common.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link DeviceSegmentEvaluator} 単体テスト。
 *
 * <p>EntityManager を Mock し、{@code push_subscriptions.user_agent} の解析が
 * {@link com.mannschaft.app.auth.util.UserAgentParser} で正しくデバイス種別へマップされることを検証。</p>
 */
@ExtendWith(MockitoExtension.class)
class DeviceSegmentEvaluatorTest {

    private static final String UA_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    private static final String UA_WINDOWS_CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String UA_IPAD =
            "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query query;

    private DeviceSegmentEvaluator evaluator;

    @BeforeEach
    void setUp() throws Exception {
        evaluator = new DeviceSegmentEvaluator(new ObjectMapper());
        // @PersistenceContext で注入される EntityManager をリフレクションで差し替え
        Field f = DeviceSegmentEvaluator.class.getDeclaredField("entityManager");
        f.setAccessible(true);
        f.set(evaluator, entityManager);
    }

    @Test
    @DisplayName("supports: DEVICE のみ true")
    void supports_onlyDevice() {
        assertThat(evaluator.supports(AdSegmentType.DEVICE)).isTrue();
        assertThat(evaluator.supports(AdSegmentType.LOCALE)).isFalse();
    }

    @Test
    @DisplayName("resolveUserIds: MOBILE 指定 → iPhone ユーザーがヒット、Windows ユーザーは除外")
    void resolveUserIds_mobileOnly() {
        AdAudienceSegment seg = segment("{\"devices\":[\"MOBILE\"]}");

        given(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).willReturn(query);
        given(query.getResultList()).willReturn(List.<Object[]>of(
                new Object[]{1L, UA_IPHONE},
                new Object[]{2L, UA_WINDOWS_CHROME},
                new Object[]{3L, UA_IPAD}
        ));

        Set<Long> result = evaluator.resolveUserIds(seg);

        // MOBILE = iPhone のみ。iPad は TABLET 判定、Windows は DESKTOP 判定
        assertThat(result).containsExactly(1L);
    }

    @Test
    @DisplayName("resolveUserIds: DESKTOP + TABLET 指定 → Windows / iPad ユーザーが入る、iPhone は除外")
    void resolveUserIds_desktopAndTablet() {
        AdAudienceSegment seg = segment("{\"devices\":[\"DESKTOP\",\"TABLET\"]}");

        given(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).willReturn(query);
        given(query.getResultList()).willReturn(List.<Object[]>of(
                new Object[]{1L, UA_IPHONE},
                new Object[]{2L, UA_WINDOWS_CHROME},
                new Object[]{3L, UA_IPAD}
        ));

        Set<Long> result = evaluator.resolveUserIds(seg);

        assertThat(result).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    @DisplayName("resolveUserIds: 小文字も正規化されて受理")
    void resolveUserIds_lowercaseNormalized() {
        AdAudienceSegment seg = segment("{\"devices\":[\"mobile\"]}");

        given(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).willReturn(query);
        given(query.getResultList()).willReturn(List.<Object[]>of(
                new Object[]{1L, UA_IPHONE}
        ));

        Set<Long> result = evaluator.resolveUserIds(seg);
        assertThat(result).containsExactly(1L);
    }

    @Test
    @DisplayName("resolveUserIds: 同一ユーザーが複数 UA を持つ場合、いずれかの UA が target に該当すれば含まれる")
    void resolveUserIds_multipleUaPerUser() {
        AdAudienceSegment seg = segment("{\"devices\":[\"MOBILE\"]}");

        given(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).willReturn(query);
        given(query.getResultList()).willReturn(List.<Object[]>of(
                new Object[]{1L, UA_WINDOWS_CHROME}, // DESKTOP
                new Object[]{1L, UA_IPHONE}          // MOBILE - こちらでヒットすればよい
        ));

        Set<Long> result = evaluator.resolveUserIds(seg);
        assertThat(result).containsExactly(1L);
    }

    @Test
    @DisplayName("resolveUserIds: devices 配列欠落 → AD_AUDIENCE_INVALID")
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
        AdAudienceSegment seg = segment("{\"devices\":[]}");
        assertThatThrownBy(() -> evaluator.resolveUserIds(seg))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
    }

    @Test
    @DisplayName("resolveUserIds: 不正な enum 値 → AD_AUDIENCE_INVALID")
    void resolveUserIds_invalidEnumValue() {
        AdAudienceSegment seg = segment("{\"devices\":[\"SMARTWATCH\"]}");
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
    @DisplayName("countUserIds: MOBILE 指定 → resolveUserIds と同じ件数を返す")
    void countUserIds_mobileOnly_returnsSameCountAsResolve() {
        AdAudienceSegment seg = segment("{\"devices\":[\"MOBILE\"]}");

        given(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).willReturn(query);
        given(query.getResultList()).willReturn(List.<Object[]>of(
                new Object[]{1L, UA_IPHONE},
                new Object[]{2L, UA_WINDOWS_CHROME},
                new Object[]{3L, UA_IPAD}
        ));

        long count = evaluator.countUserIds(seg);

        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("countUserIds: devices 配列欠落 → resolveUserIds と同じ AD_AUDIENCE_INVALID")
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
                .segmentType(AdSegmentType.DEVICE)
                .segmentValue(json)
                .inclusionMode(AdSegmentInclusionMode.INCLUDE)
                .build();
        s.setId(UUID.randomUUID());
        return s;
    }
}
