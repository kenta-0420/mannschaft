package com.mannschaft.app.matching.service;

import com.mannschaft.app.matching.entity.CityEntity;
import com.mannschaft.app.matching.repository.CityRepository;
import com.mannschaft.app.matching.repository.PrefectureRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("RegionMasterLookupService 単体テスト")
class RegionMasterLookupServiceTest {

    @Mock
    private PrefectureRepository prefectureRepository;

    @Mock
    private CityRepository cityRepository;

    @Test
    @DisplayName("市区町村を不変値として1回の参照で返す")
    void 市区町村を不変値として返す() {
        CityEntity city = mock(CityEntity.class);
        given(city.getCode()).willReturn("44202");
        given(city.getPrefectureCode()).willReturn("44");
        given(city.getName()).willReturn("Beppu");
        given(cityRepository.findById("44202")).willReturn(Optional.of(city));

        RegionMasterLookupService service = new RegionMasterLookupService(
                prefectureRepository, cityRepository);

        assertThat(service.findCityByCode("44202"))
                .contains(new RegionMasterLookupService.City("44202", "44", "Beppu"));
        verify(cityRepository).findById("44202");
    }

    @Test
    @DisplayName("都道府県名が不在なら空を返す")
    void 都道府県名が不在なら空を返す() {
        given(prefectureRepository.findById("99")).willReturn(Optional.empty());

        RegionMasterLookupService service = new RegionMasterLookupService(
                prefectureRepository, cityRepository);

        assertThat(service.findPrefectureByCode("99")).isEmpty();
        verify(prefectureRepository).findById("99");
    }
}
