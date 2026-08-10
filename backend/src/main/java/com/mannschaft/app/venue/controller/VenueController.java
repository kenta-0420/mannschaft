package com.mannschaft.app.venue.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.venue.dto.RegisterVenueRequest;
import com.mannschaft.app.venue.dto.VenueResponse;
import com.mannschaft.app.venue.dto.VenueSuggestionResponse;
import com.mannschaft.app.venue.service.VenueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;

import java.util.List;

/**
 * 施設検索・登録コントローラー。
 *
 * <p>{@link com.mannschaft.app.venue.entity.VenueEntity} は組織/チームに属さない
 * 全テナント共有のマスタデータ（Google Places 由来 or 手動登録の住所録）であり、
 * organizationId/teamId のようなテナントスコープを持たない。そのため各 EP は
 * SecurityConfig の deny-by-default（anyRequest().authenticated()）による認証必須のみで足り、
 * 全認証済みユーザーに同一の応答を返す（ユーザー固有情報を含まない）。</p>
 */
@RestController
@RequestMapping("/api/v1/venues")
@RequiredArgsConstructor
@AuthorizedByPathConfig("anyRequest().authenticated()")
public class VenueController {

    private final VenueService venueService;

    /**
     * 施設候補を検索する（Autocomplete用）。
     * DB優先 → Google Places APIフォールバック。
     */
    @GetMapping("/suggest")
    public ApiResponse<List<VenueSuggestionResponse>> suggest(
            @RequestParam String keyword,
            @RequestParam(required = false) String sessionToken) {
        return ApiResponse.of(venueService.suggest(keyword, sessionToken));
    }

    /**
     * 施設詳細を取得する。
     */
    @GetMapping("/{id}")
    public ApiResponse<VenueResponse> getVenue(@PathVariable Long id) {
        return ApiResponse.of(venueService.getVenue(id));
    }

    /**
     * Google Places候補を選択して施設を登録する。
     * 既にDBに存在する場合は既存を返す（利用回数インクリメント）。
     */
    @PostMapping("/register-from-google")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<VenueResponse> registerFromGoogle(
            @RequestParam String googlePlaceId,
            @RequestParam(required = false) String sessionToken) {
        return ApiResponse.of(venueService.registerFromGooglePlace(googlePlaceId, sessionToken));
    }

    /**
     * 施設を手動登録する（Google Placesを使わない場合）。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<VenueResponse> registerManual(
            @Valid @RequestBody RegisterVenueRequest request) {
        return ApiResponse.of(venueService.registerManual(request));
    }
}
