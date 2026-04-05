package com.mannschaft.app.matching;

import com.mannschaft.app.matching.dto.CityResponse;
import com.mannschaft.app.matching.dto.PrefectureResponse;
import com.mannschaft.app.matching.entity.CityEntity;
import com.mannschaft.app.matching.entity.PrefectureEntity;
import com.mannschaft.app.matching.mapper.MatchingMapper;
import com.mannschaft.app.matching.repository.CityRepository;
import com.mannschaft.app.matching.repository.PrefectureRepository;
import com.mannschaft.app.matching.service.MasterDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link MasterDataService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MasterDataService 単体テスト")
class MasterDataServiceTest {

    @Mock
    private PrefectureRepository prefectureRepository;

    @Mock
    private CityRepository cityRepository;

    @Mock
    private MatchingMapper matchingMapper;

    @InjectMocks
    private MasterDataService service;

    @Nested
    @DisplayName("listPrefectures")
    class ListPrefectures {

        @Test
        @DisplayName("正常系: 全都道府県一覧が返却される")
        void 全都道府県一覧が返却される() {
            // Given
            List<PrefectureEntity> entities = List.of(mock(PrefectureEntity.class));
            List<PrefectureResponse> responses = List.of(new PrefectureResponse("01", "北海道"));
            given(prefectureRepository.findAllByOrderByCodeAsc()).willReturn(entities);
            given(matchingMapper.toPrefectureResponseList(entities)).willReturn(responses);

            // When
            List<PrefectureResponse> result = service.listPrefectures();

            // Then
            assertThat(result).hasSize(1);
            verify(prefectureRepository).findAllByOrderByCodeAsc();
        }
    }

    @Nested
    @DisplayName("listCities")
    class ListCities {

        @Test
        @DisplayName("正常系: 都道府県コード指定で市区町村一覧が返却される")
        void 市区町村一覧が返却される() {
            // Given
            String prefectureCode = "13";
            List<CityEntity> entities = List.of(mock(CityEntity.class));
            List<CityResponse> responses = List.of(new CityResponse("13101", "千代田区"));
            given(cityRepository.findByPrefectureCodeOrderByCodeAsc(prefectureCode)).willReturn(entities);
            given(matchingMapper.toCityResponseList(entities)).willReturn(responses);

            // When
            List<CityResponse> result = service.listCities(prefectureCode);

            // Then
            assertThat(result).hasSize(1);
            verify(cityRepository).findByPrefectureCodeOrderByCodeAsc(prefectureCode);
        }
    }
}
