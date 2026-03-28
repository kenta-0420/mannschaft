package com.mannschaft.app.venue.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.venue.VenueErrorCode;
import com.mannschaft.app.venue.VenueMapper;
import com.mannschaft.app.venue.dto.RegisterVenueRequest;
import com.mannschaft.app.venue.dto.VenueResponse;
import com.mannschaft.app.venue.dto.VenueSuggestionResponse;
import com.mannschaft.app.venue.entity.VenueEntity;
import com.mannschaft.app.venue.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 施設サービス。
 * DB優先 → Google Places APIフォールバックで施設候補を提供する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class VenueService {

    private static final int MIN_KEYWORD_LENGTH = 2;
    private static final int MAX_DB_SUGGESTIONS = 10;

    private final VenueRepository venueRepository;
    private final VenueMapper venueMapper;
    private final GooglePlacesClient googlePlacesClient;

    /**
     * 施設候補を検索する（DB優先 → Google Places APIフォールバック）。
     */
    public List<VenueSuggestionResponse> suggest(String keyword, String sessionToken) {
        if (keyword == null || keyword.length() < MIN_KEYWORD_LENGTH) {
            throw new BusinessException(VenueErrorCode.VENUE_003);
        }

        // 1. DBキャッシュから検索
        List<VenueEntity> dbResults = venueRepository.searchByKeyword(keyword);
        List<VenueSuggestionResponse> suggestions = new ArrayList<>(
                dbResults.stream()
                        .limit(MAX_DB_SUGGESTIONS)
                        .map(venueMapper::toSuggestion)
                        .toList()
        );

        // 2. DB候補が少ない場合、Google Places APIで補完
        if (suggestions.size() < MAX_DB_SUGGESTIONS && googlePlacesClient.isAvailable()) {
            try {
                GooglePlacesClient.AutocompleteResponse response =
                        googlePlacesClient.autocomplete(keyword, sessionToken);
                if (response != null && response.suggestions() != null) {
                    for (var suggestion : response.suggestions()) {
                        if (suggestion.placePrediction() == null) continue;
                        var prediction = suggestion.placePrediction();

                        // 既にDBに存在するplace_idはスキップ
                        boolean alreadyInDb = dbResults.stream()
                                .anyMatch(v -> prediction.placeId().equals(v.getGooglePlaceId()));
                        if (alreadyInDb) continue;

                        String name = prediction.structuredFormat() != null
                                && prediction.structuredFormat().mainText() != null
                                ? prediction.structuredFormat().mainText().text()
                                : prediction.text().text();
                        String address = prediction.structuredFormat() != null
                                && prediction.structuredFormat().secondaryText() != null
                                ? prediction.structuredFormat().secondaryText().text()
                                : null;

                        suggestions.add(new VenueSuggestionResponse(
                                null, prediction.placeId(), name, address, "GOOGLE"));
                    }
                }
            } catch (BusinessException e) {
                log.debug("Google Places APIフォールバック失敗、DB候補のみ返却: keyword={}", keyword);
            }
        }

        return suggestions;
    }

    /**
     * 施設を取得する。
     */
    public VenueResponse getVenue(Long venueId) {
        VenueEntity entity = venueRepository.findById(venueId)
                .orElseThrow(() -> new BusinessException(VenueErrorCode.VENUE_001));
        return venueMapper.toResponse(entity);
    }

    /**
     * Google Placesの候補選択時に施設をDBに登録（or既存取得）する。
     * place_idで重複チェックし、既存なら利用回数をインクリメントして返す。
     */
    @Transactional
    public VenueResponse registerFromGooglePlace(String googlePlaceId, String sessionToken) {
        // 既にDB登録済みならそれを返す
        return venueRepository.findByGooglePlaceId(googlePlaceId)
                .map(existing -> {
                    venueRepository.incrementUsageCount(existing.getId());
                    return venueMapper.toResponse(existing);
                })
                .orElseGet(() -> registerNewFromGoogle(googlePlaceId, sessionToken));
    }

    /**
     * 手動で施設を登録する（Google Places未使用時）。
     */
    @Transactional
    public VenueResponse registerManual(RegisterVenueRequest request) {
        // google_place_idが指定されていて既に存在する場合は既存を返す
        if (request.getGooglePlaceId() != null && !request.getGooglePlaceId().isBlank()) {
            var existing = venueRepository.findByGooglePlaceId(request.getGooglePlaceId());
            if (existing.isPresent()) {
                venueRepository.incrementUsageCount(existing.get().getId());
                return venueMapper.toResponse(existing.get());
            }
        }

        VenueEntity entity = venueMapper.toEntity(request);
        VenueEntity saved = venueRepository.save(entity);
        return venueMapper.toResponse(saved);
    }

    /**
     * 施設選択時に利用回数をインクリメントする。
     */
    @Transactional
    public void recordUsage(Long venueId) {
        venueRepository.incrementUsageCount(venueId);
    }

    private VenueResponse registerNewFromGoogle(String googlePlaceId, String sessionToken) {
        GooglePlacesClient.PlaceDetailsResponse details =
                googlePlacesClient.getPlaceDetails(googlePlaceId, sessionToken);

        VenueEntity entity = VenueEntity.builder()
                .googlePlaceId(googlePlaceId)
                .name(details.displayName() != null ? details.displayName().text() : "不明")
                .address(details.formattedAddress())
                .latitude(details.location() != null
                        ? BigDecimal.valueOf(details.location().latitude()) : null)
                .longitude(details.location() != null
                        ? BigDecimal.valueOf(details.location().longitude()) : null)
                .prefecture(details.extractPrefecture())
                .city(details.extractCity())
                .category(details.extractCategory())
                .phoneNumber(details.nationalPhoneNumber())
                .websiteUrl(details.websiteUri())
                .usageCount(1)
                .build();

        VenueEntity saved = venueRepository.save(entity);
        log.info("施設マスタ登録: id={}, name={}, googlePlaceId={}",
                saved.getId(), saved.getName(), googlePlaceId);
        return venueMapper.toResponse(saved);
    }
}
