package com.mannschaft.app.advertising.campaign.dto;

import com.mannschaft.app.advertising.campaign.enums.EstimatedReachRange;

/**
 * F09.17 推定リーチのレンジ表示レスポンス。
 *
 * <p>個別ユーザー特定リスクを避けるため、人数は enum {@link EstimatedReachRange} の
 * レンジで返却し、user_id 等の個別識別情報は一切含めない。</p>
 *
 * @param range レンジ enum (UNDER_100〜OVER_100K)
 * @param label 表示用ラベル（日本語 "約500〜1,000人" 等）
 */
public record EstimatedReachRangeResponse(
        EstimatedReachRange range,
        String label
) {
    public static EstimatedReachRangeResponse of(EstimatedReachRange range) {
        return new EstimatedReachRangeResponse(range, range.getLabel());
    }
}
