package com.mannschaft.app.venue.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.venue.VenueErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Google Places API (New) クライアント。
 * Autocomplete + Place Details (Essentials) をセッション方式で呼び出す。
 */
@Slf4j
@Component
public class GooglePlacesClient {

    private final String apiKey;
    private final RestClient restClient;

    public GooglePlacesClient(
            @Value("${mannschaft.google.places-api-key:}") String apiKey,
            @Value("${mannschaft.google.places-api-base:https://places.googleapis.com/v1}") String apiBase) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder()
                .baseUrl(apiBase)
                .build();
    }

    /**
     * Google Places APIが利用可能かどうかを返す。
     */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Autocomplete (New) で施設候補を取得する。
     * 日本国内の施設に限定し、セッショントークンを利用。
     */
    public AutocompleteResponse autocomplete(String input, String sessionToken) {
        if (!isAvailable()) {
            return new AutocompleteResponse(List.of());
        }

        try {
            AutocompleteRequest request = new AutocompleteRequest(
                    input,
                    List.of("ja"),
                    List.of("JP"),
                    sessionToken
            );

            return restClient.post()
                    .uri("/places:autocomplete")
                    .header("X-Goog-Api-Key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(AutocompleteResponse.class);
        } catch (Exception e) {
            log.warn("Google Places Autocomplete呼び出し失敗: input={}", input, e);
            throw new BusinessException(VenueErrorCode.VENUE_002);
        }
    }

    /**
     * Place Details (Essentials) で施設の詳細情報を取得する。
     * セッショントークンを利用してAutocompleteとバンドルする。
     */
    public PlaceDetailsResponse getPlaceDetails(String placeId, String sessionToken) {
        if (!isAvailable()) {
            throw new BusinessException(VenueErrorCode.VENUE_002);
        }

        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/places/{placeId}")
                            .queryParam("sessionToken", sessionToken)
                            .build(placeId))
                    .header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask",
                            "id,displayName,formattedAddress,location,"
                                    + "addressComponents,types,nationalPhoneNumber,websiteUri")
                    .retrieve()
                    .body(PlaceDetailsResponse.class);
        } catch (Exception e) {
            log.warn("Google Places Details呼び出し失敗: placeId={}", placeId, e);
            throw new BusinessException(VenueErrorCode.VENUE_002);
        }
    }

    // --- Request/Response DTOs ---

    public record AutocompleteRequest(
            String input,
            List<String> languageCodes,
            List<String> includedRegionCodes,
            String sessionToken
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AutocompleteResponse(
            @JsonProperty("suggestions") List<Suggestion> suggestions
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Suggestion(
                @JsonProperty("placePrediction") PlacePrediction placePrediction
        ) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record PlacePrediction(
                @JsonProperty("placeId") String placeId,
                @JsonProperty("text") TextValue text,
                @JsonProperty("structuredFormat") StructuredFormat structuredFormat
        ) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record TextValue(
                @JsonProperty("text") String text
        ) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record StructuredFormat(
                @JsonProperty("mainText") TextValue mainText,
                @JsonProperty("secondaryText") TextValue secondaryText
        ) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlaceDetailsResponse(
            @JsonProperty("id") String id,
            @JsonProperty("displayName") DisplayName displayName,
            @JsonProperty("formattedAddress") String formattedAddress,
            @JsonProperty("location") LatLng location,
            @JsonProperty("addressComponents") List<AddressComponent> addressComponents,
            @JsonProperty("types") List<String> types,
            @JsonProperty("nationalPhoneNumber") String nationalPhoneNumber,
            @JsonProperty("websiteUri") String websiteUri
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record DisplayName(
                @JsonProperty("text") String text,
                @JsonProperty("languageCode") String languageCode
        ) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record LatLng(
                @JsonProperty("latitude") double latitude,
                @JsonProperty("longitude") double longitude
        ) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record AddressComponent(
                @JsonProperty("longText") String longText,
                @JsonProperty("shortText") String shortText,
                @JsonProperty("types") List<String> types
        ) {}

        /**
         * addressComponentsから都道府県を抽出する。
         */
        public String extractPrefecture() {
            if (addressComponents == null) return null;
            return addressComponents.stream()
                    .filter(c -> c.types() != null && c.types().contains("administrative_area_level_1"))
                    .findFirst()
                    .map(AddressComponent::longText)
                    .orElse(null);
        }

        /**
         * addressComponentsから市区町村を抽出する。
         */
        public String extractCity() {
            if (addressComponents == null) return null;
            return addressComponents.stream()
                    .filter(c -> c.types() != null && c.types().contains("locality"))
                    .findFirst()
                    .map(AddressComponent::longText)
                    .orElse(null);
        }

        /**
         * typesから主要カテゴリを抽出する。
         */
        public String extractCategory() {
            if (types == null || types.isEmpty()) return null;
            return types.stream()
                    .filter(t -> !t.equals("point_of_interest") && !t.equals("establishment"))
                    .findFirst()
                    .orElse(types.get(0));
        }
    }
}
