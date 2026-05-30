package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.matching.entity.CityEntity;
import com.mannschaft.app.matching.repository.CityRepository;
import com.mannschaft.app.matching.repository.PrefectureRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link MarketRegionValidator} の単体テスト（MARKET_001・地域整合）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarketRegionValidator 単体テスト")
class MarketRegionValidatorTest {

    @Mock
    private PrefectureRepository prefectureRepository;

    @Mock
    private CityRepository cityRepository;

    @InjectMocks
    private MarketRegionValidator validator;

    private static CityEntity city(String code, String prefCode) {
        try {
            java.lang.reflect.Constructor<CityEntity> ctor =
                    CityEntity.class.getDeclaredConstructor(String.class, String.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(code, prefCode, "テスト市");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("両方 null → 地域なし（prefecture/city ともに null）")
    void bothNull_returnsEmpty() {
        MarketRegionValidator.ResolvedRegion r = validator.validateAndNormalize(null, null);
        assertThat(r.prefectureCode()).isNull();
        assertThat(r.cityCode()).isNull();
    }

    @Test
    @DisplayName("city 指定・prefecture 未指定 → 上位2桁で自動補完")
    void cityOnly_autoFillsPrefecture() {
        given(cityRepository.findById("44202")).willReturn(Optional.of(city("44202", "44")));

        MarketRegionValidator.ResolvedRegion r = validator.validateAndNormalize(null, "44202");
        assertThat(r.prefectureCode()).isEqualTo("44");
        assertThat(r.cityCode()).isEqualTo("44202");
    }

    @Test
    @DisplayName("city がマスタ不在 → MARKET_001")
    void cityNotFound_throws() {
        given(cityRepository.findById("99999")).willReturn(Optional.empty());

        assertThatThrownBy(() -> validator.validateAndNormalize(null, "99999"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MarketErrorCode.REGION_INVALID);
    }

    @Test
    @DisplayName("city の上位2桁 != prefecture → MARKET_001")
    void cityPrefectureMismatch_throws() {
        given(cityRepository.findById("44202")).willReturn(Optional.of(city("44202", "44")));

        assertThatThrownBy(() -> validator.validateAndNormalize("13", "44202"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MarketErrorCode.REGION_INVALID);
    }

    @Test
    @DisplayName("prefecture のみ・存在 → そのまま返す")
    void prefectureOnly_exists() {
        given(prefectureRepository.existsById("44")).willReturn(true);

        MarketRegionValidator.ResolvedRegion r = validator.validateAndNormalize("44", null);
        assertThat(r.prefectureCode()).isEqualTo("44");
        assertThat(r.cityCode()).isNull();
    }

    @Test
    @DisplayName("prefecture のみ・不在 → MARKET_001")
    void prefectureOnly_notFound_throws() {
        given(prefectureRepository.existsById("99")).willReturn(false);

        assertThatThrownBy(() -> validator.validateAndNormalize("99", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MarketErrorCode.REGION_INVALID);
    }
}
