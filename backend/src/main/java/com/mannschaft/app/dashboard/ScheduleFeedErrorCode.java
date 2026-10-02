package com.mannschaft.app.dashboard;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.18 スケジュール変更フィードのエラーコード定義（設計書 §7）。
 *
 * <p>いずれも {@link Severity#WARN} であり、{@code GlobalExceptionHandler} の既定写像により
 * HTTP 400 で返る（個別の STATUS_MAP 登録は不要＝既定が設計意図と一致するため）。</p>
 *
 * <p><strong>デッドコードにしない</strong>: 本 enum は {@code DashboardController#getActivity} の
 * 入口バリデーションから実際に throw される。検証はエントリポイントに一元化し、
 * {@code ActivityFeedService} 側は「妥当な cursor / limit が渡ってくる」ことを前提としてよい
 * （memory: feedback_authz_gate_on_public_entry_not_shared_method と同じ思想）。</p>
 */
@Getter
@RequiredArgsConstructor
public enum ScheduleFeedErrorCode implements ErrorCode {

    /** カーソルパラメータが不正（0 以下）。400 */
    INVALID_CURSOR("SCHEDULE_FEED_001", "カーソルの指定が不正です", Severity.WARN),

    /** limit パラメータが許容範囲外（0 以下）。400 */
    INVALID_LIMIT("SCHEDULE_FEED_002", "取得件数の指定が不正です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
