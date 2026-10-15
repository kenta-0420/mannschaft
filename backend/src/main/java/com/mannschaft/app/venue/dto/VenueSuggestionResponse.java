package com.mannschaft.app.venue.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 施設候補レスポンス（Autocomplete用）。
 * DBキャッシュとGoogle Places APIの結果を統一フォーマットで返す。
 */
@Getter
@RequiredArgsConstructor
public class VenueSuggestionResponse {

    /** DBに存在する場合はvenueのID、Google Places候補の場合はnull */
    private final Long venueId;

    /** Google Places place_id（DB候補でも保持している場合あり） */
    private final String googlePlaceId;

    private final String name;
    private final String address;

    /** DBキャッシュからの候補か、Google Places APIからの候補か */
    private final String source;
}
