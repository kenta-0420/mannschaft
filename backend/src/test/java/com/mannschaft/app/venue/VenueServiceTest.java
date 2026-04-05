package com.mannschaft.app.venue;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.venue.dto.RegisterVenueRequest;
import com.mannschaft.app.venue.dto.VenueResponse;
import com.mannschaft.app.venue.dto.VenueSuggestionResponse;
import com.mannschaft.app.venue.entity.VenueEntity;
import com.mannschaft.app.venue.repository.VenueRepository;
import com.mannschaft.app.venue.service.GooglePlacesClient;
import com.mannschaft.app.venue.service.VenueService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("VenueService 単体テスト")
class VenueServiceTest {

    @Mock private VenueRepository venueRepository;
    @Mock private VenueMapper venueMapper;
    @Mock private GooglePlacesClient googlePlacesClient;
    @InjectMocks private VenueService service;

    @Nested
    @DisplayName("suggest")
    class Suggest {

        @Test
        @DisplayName("正常系: DB候補が返る")
        void suggest_DB候補あり_返却() {
            // Given
            VenueEntity entity = VenueEntity.builder()
                    .name("西部運動公園").prefecture("東京都").city("新宿区").build();
            given(venueRepository.searchByKeyword("西部")).willReturn(List.of(entity));
            given(venueMapper.toSuggestion(entity)).willReturn(
                    new VenueSuggestionResponse(1L, null, "西部運動公園", null, "DB"));
            given(googlePlacesClient.isAvailable()).willReturn(false);

            // When
            List<VenueSuggestionResponse> result = service.suggest("西部", null);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("西部運動公園");
            assertThat(result.get(0).getSource()).isEqualTo("DB");
        }

        @Test
        @DisplayName("正常系: DB候補少数時にGoogle Places APIで補完")
        void suggest_DB少数_Google補完() {
            // Given
            given(venueRepository.searchByKeyword("西部")).willReturn(List.of());
            given(googlePlacesClient.isAvailable()).willReturn(true);

            var prediction = new GooglePlacesClient.AutocompleteResponse.PlacePrediction(
                    "ChIJ_test123",
                    new GooglePlacesClient.AutocompleteResponse.TextValue("西部運動公園"),
                    new GooglePlacesClient.AutocompleteResponse.StructuredFormat(
                            new GooglePlacesClient.AutocompleteResponse.TextValue("西部運動公園"),
                            new GooglePlacesClient.AutocompleteResponse.TextValue("東京都新宿区")
                    )
            );
            var suggestion = new GooglePlacesClient.AutocompleteResponse.Suggestion(prediction);
            given(googlePlacesClient.autocomplete("西部", "session-token"))
                    .willReturn(new GooglePlacesClient.AutocompleteResponse(List.of(suggestion)));

            // When
            List<VenueSuggestionResponse> result = service.suggest("西部", "session-token");

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getGooglePlaceId()).isEqualTo("ChIJ_test123");
            assertThat(result.get(0).getSource()).isEqualTo("GOOGLE");
        }

        @Test
        @DisplayName("異常系: キーワードが短すぎてVENUE_003例外")
        void suggest_キーワード短い_例外() {
            // When / Then
            assertThatThrownBy(() -> service.suggest("西", null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("VENUE_003"));
        }
    }

    @Nested
    @DisplayName("getVenue")
    class GetVenue {

        @Test
        @DisplayName("正常系: 施設が取得できる")
        void 取得_正常() {
            // Given
            VenueEntity entity = VenueEntity.builder().name("中央体育館").build();
            given(venueRepository.findById(1L)).willReturn(Optional.of(entity));
            given(venueMapper.toResponse(entity)).willReturn(
                    new VenueResponse(1L, null, "中央体育館", null, null, null, null, null, null));

            // When
            VenueResponse result = service.getVenue(1L);

            // Then
            assertThat(result.getName()).isEqualTo("中央体育館");
        }

        @Test
        @DisplayName("異常系: 施設不在でVENUE_001例外")
        void 取得_不在_例外() {
            // Given
            given(venueRepository.findById(99L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getVenue(99L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("VENUE_001"));
        }
    }

    @Nested
    @DisplayName("registerFromGooglePlace")
    class RegisterFromGooglePlace {

        @Test
        @DisplayName("正常系: 既存place_idはインクリメントして返す")
        void 登録_既存_インクリメント() {
            // Given
            VenueEntity existing = VenueEntity.builder()
                    .googlePlaceId("ChIJ_existing").name("既存施設").build();
            given(venueRepository.findByGooglePlaceId("ChIJ_existing"))
                    .willReturn(Optional.of(existing));
            given(venueMapper.toResponse(existing)).willReturn(
                    new VenueResponse(1L, "ChIJ_existing", "既存施設", null, null, null, null, null, null));

            // When
            VenueResponse result = service.registerFromGooglePlace("ChIJ_existing", "token");

            // Then
            assertThat(result.getName()).isEqualTo("既存施設");
            verify(venueRepository).incrementUsageCount(existing.getId());
        }

        @Test
        @DisplayName("正常系: 新規place_idはGoogle APIから取得して保存")
        void 登録_新規_Google取得() {
            // Given
            given(venueRepository.findByGooglePlaceId("ChIJ_new")).willReturn(Optional.empty());

            var details = new GooglePlacesClient.PlaceDetailsResponse(
                    "ChIJ_new",
                    new GooglePlacesClient.PlaceDetailsResponse.DisplayName("新規グラウンド", "ja"),
                    "東京都新宿区1-1",
                    new GooglePlacesClient.PlaceDetailsResponse.LatLng(35.6895, 139.6917),
                    List.of(
                            new GooglePlacesClient.PlaceDetailsResponse.AddressComponent(
                                    "東京都", "東京都", List.of("administrative_area_level_1")),
                            new GooglePlacesClient.PlaceDetailsResponse.AddressComponent(
                                    "新宿区", "新宿区", List.of("locality"))
                    ),
                    List.of("park", "point_of_interest"),
                    "03-1234-5678",
                    "https://example.com"
            );
            given(googlePlacesClient.getPlaceDetails("ChIJ_new", "token")).willReturn(details);

            VenueEntity saved = VenueEntity.builder()
                    .googlePlaceId("ChIJ_new").name("新規グラウンド")
                    .prefecture("東京都").city("新宿区").build();
            given(venueRepository.save(any(VenueEntity.class))).willReturn(saved);
            given(venueMapper.toResponse(saved)).willReturn(
                    new VenueResponse(2L, "ChIJ_new", "新規グラウンド", "東京都新宿区1-1",
                            BigDecimal.valueOf(35.6895), BigDecimal.valueOf(139.6917),
                            "東京都", "新宿区", "park"));

            // When
            VenueResponse result = service.registerFromGooglePlace("ChIJ_new", "token");

            // Then
            assertThat(result.getName()).isEqualTo("新規グラウンド");
            assertThat(result.getPrefecture()).isEqualTo("東京都");
            verify(venueRepository).save(any(VenueEntity.class));
        }
    }

    @Nested
    @DisplayName("registerManual")
    class RegisterManual {

        @Test
        @DisplayName("正常系: 手動登録が成功する")
        void 手動登録_正常() {
            // Given
            RegisterVenueRequest req = new RegisterVenueRequest(
                    null, "手動登録グラウンド", "東京都渋谷区1-1",
                    BigDecimal.valueOf(35.6580), BigDecimal.valueOf(139.7016),
                    "東京都", "渋谷区", "stadium", null, null);
            VenueEntity entity = VenueEntity.builder().name("手動登録グラウンド").build();
            given(venueMapper.toEntity(req)).willReturn(entity);
            given(venueRepository.save(entity)).willReturn(entity);
            given(venueMapper.toResponse(entity)).willReturn(
                    new VenueResponse(3L, null, "手動登録グラウンド", "東京都渋谷区1-1",
                            null, null, "東京都", "渋谷区", "stadium"));

            // When
            VenueResponse result = service.registerManual(req);

            // Then
            assertThat(result.getName()).isEqualTo("手動登録グラウンド");
            verify(venueRepository).save(entity);
        }
    }
}
