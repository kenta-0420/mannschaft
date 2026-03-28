package com.mannschaft.app.venue;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 施設機能のエラーコード。
 */
@Getter
@RequiredArgsConstructor
public enum VenueErrorCode implements ErrorCode {

    /** 施設が見つからない */
    VENUE_001("VENUE_001", "指定された施設が見つかりません", Severity.WARN),

    /** Google Places API呼び出し失敗 */
    VENUE_002("VENUE_002", "施設情報の取得に失敗しました", Severity.ERROR),

    /** 検索キ��ワードが短すぎる */
    VENUE_003("VENUE_003", "検索キーワードは2文字以上入力してください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
